package com.example.pcsteuerung

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Live-Anzeige fuer die Statusleiste / Samsung "Now Bar".
 *
 * Der Dienst zeigt genau dann eine laufende (ONGOING) Fortschritts-Karte, wenn
 * es etwas zu zeigen gibt:
 *
 *  a) Steam-Download  -> `GET /steam` (Fallback `GET /downloads`)
 *  b) Herunterfahren  -> `pending_seconds` / `pending_shutdown` aus `GET /status`
 *  c) Startphase      -> "PC startet ...", bis `/status` antwortet
 *
 * Er pollt alle 2 s, laesst sich ueber die Service-Actions steuern und beendet
 * sich selbst, sobald nichts mehr anzuzeigen ist. Alle Netzaufrufe laufen in
 * `Dispatchers.IO`; kaputte Antworten (401/404/JSON-Fehler, PC aus) fuehren
 * hoechstens zum Zustand "unbekannt" - nie zu einem Absturz.
 */
class LiveStatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** Profil aus dem Start-Intent oder aus den gespeicherten Profilen. */
    @Volatile
    private var profile: Profile? = null

    /** Startphase angefordert (z. B. direkt nach dem WoL-Paket). */
    @Volatile
    private var waitOnline = false
    private var waitDeadlineMs = 0L

    /** Wurde der PC schon einmal nicht erreicht? (0 = nein) */
    private var unreachableSinceMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC

        // Zuerst eine Benachrichtigung setzen: ein per startForegroundService
        // gestarteter Dienst muss das innerhalb von 5 s tun (sonst stuerzt
        // Android 14+ die App ab).
        postForeground(notificationChecking())

        profile = intent?.let { readProfile(it) } ?: ProfileStore.active(this)
        val active = profile
        if (active == null || !active.isComplete) {
            Log.d(LIVE_TAG, "Kein vollstaendiges Profil - Live-Anzeige beendet")
            teardown()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP -> {
                teardown()
                return START_NOT_STICKY
            }
            ACTION_ABORT -> {
                abortShutdown(active)
                return START_NOT_STICKY
            }
            ACTION_WAIT_ONLINE -> {
                waitOnline = true
                val seconds = (intent?.getIntExtra(EXTRA_WAIT_SECONDS, DEFAULT_WAIT_SECONDS)
                    ?: DEFAULT_WAIT_SECONDS).coerceIn(10, 600)
                waitDeadlineMs = SystemClock.elapsedRealtime() + seconds * 1000L
                unreachableSinceMs = 0L
            }
        }

        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Poll-Schleife
    // -----------------------------------------------------------------------

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val keepRunning = runCatching { poll() }.getOrElse { error ->
                    Log.d(LIVE_TAG, "Live-Abfrage fehlgeschlagen: ${error.javaClass.simpleName}")
                    false
                }
                if (!keepRunning) {
                    teardown()
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    /**
     * Eine Runde: Zustand holen, Karte aktualisieren.
     * @return false, wenn nichts (mehr) anzuzeigen ist und sich der Dienst
     *         beenden soll.
     */
    private suspend fun poll(): Boolean {
        val active = profile ?: return false
        val auth = active.authHeader()
        val now = SystemClock.elapsedRealtime()

        val statusResponse = httpGet(active.base, "/status", auth)
        val status = if (statusResponse.code in 200..299) {
            parseLiveStatus(statusResponse.body)
        } else {
            null
        }
        val reachable = status != null

        // Nur fragen, wenn der Agent ueberhaupt antwortet. 404 = Route fehlt
        // (aeltere Agent-Version) -> Steam-Zustand ehrlich ueberspringen.
        val downloads = if (reachable) readDownloads(active, auth) else emptyList()
        val running = downloads.filter { !it.isFinished }

        val remaining = status?.pendingRemaining ?: 0
        val total = status?.pendingTotal ?: 0

        return when {
            remaining > 0 -> {
                clearGrace()
                postForeground(notificationCountdown(total, remaining))
                true
            }
            running.isNotEmpty() -> {
                clearGrace()
                postForeground(notificationDownload(running))
                true
            }
            // Antwortet der Agent und laeuft nichts, gibt es nichts anzuzeigen
            // (auch nicht nach einem erledigten Download/Countdown oder am Ende
            // der Startphase) -> sofort beenden, kein Dauerlauf ohne Zweck.
            reachable -> false
            else -> {
                // PC nicht erreichbar: Gnadenfrist, dann aufgeben.
                if (waitOnline) {
                    val keep = now < waitDeadlineMs
                    if (keep) postForeground(notificationStarting(active.name))
                    keep
                } else {
                    if (unreachableSinceMs == 0L) unreachableSinceMs = now
                    val keep = now - unreachableSinceMs < UNREACHABLE_GRACE_MS
                    if (keep) postForeground(notificationUnknown())
                    keep
                }
            }
        }
    }

    /** Etwas laeuft: die Unerreichbar-Frist zuruecksetzen. */
    private fun clearGrace() {
        unreachableSinceMs = 0L
    }

    /** `GET /steam`, bei fehlender Route `GET /downloads`; im Zweifel leer. */
    private suspend fun readDownloads(active: Profile, auth: AuthHeader?): List<LiveDownload> {
        val steam = httpGet(active.base, "/steam", auth)
        if (steam.code in 200..299) return parseLiveDownloads(steam.body).orEmpty()
        if (steam.code != 404) return emptyList()
        val fallback = httpGet(active.base, "/downloads", auth)
        if (fallback.code !in 200..299) return emptyList()
        return parseLiveDownloads(fallback.body).orEmpty()
    }

    // -----------------------------------------------------------------------
    // Abbrechen (Action-Button der Karte)
    // -----------------------------------------------------------------------

    private fun abortShutdown(active: Profile) {
        scope.launch {
            val response = httpPost(active.base, "/abort", active.authHeader())
            Log.d(LIVE_TAG, "POST /abort -> ${response.code}")
            if (response.code in 200..299) {
                teardown()
            } else {
                // Ehrlich bleiben: der Countdown laeuft weiter, also weiter anzeigen.
                postForeground(notificationAbortFailed())
                startLoop()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Benachrichtigungen
    // -----------------------------------------------------------------------

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.live_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.live_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun postForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { Log.d(LIVE_TAG, "startForeground fehlgeschlagen: ${it.javaClass.simpleName}") }
    }

    private fun teardown() {
        loop?.cancel()
        loop = null
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFF1565C0.toInt())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, REQUEST_OPEN, intent, PENDING_FLAGS)
    }

    private fun abortIntent(): PendingIntent {
        val intent = Intent(this, LiveStatusService::class.java).setAction(ACTION_ABORT)
        // Ab Android 8 darf ein Dienst aus dem Hintergrund nur als
        // Foreground-Dienst gestartet werden - der Knopf ist eine Nutzeraktion.
        return PendingIntent.getForegroundService(this, REQUEST_ABORT, intent, PENDING_FLAGS)
    }

    private fun notificationChecking(): Notification = baseBuilder()
        .setContentTitle(getString(R.string.live_title_checking))
        .setContentText(getString(R.string.live_text_checking))
        .setProgress(0, 0, true)
        .build()

    private fun notificationUnknown(): Notification = baseBuilder()
        .setContentTitle(getString(R.string.live_title_unknown))
        .setContentText(getString(R.string.live_text_unknown))
        .build()

    private fun notificationAbortFailed(): Notification = baseBuilder()
        .setContentTitle(getString(R.string.live_title_shutdown))
        .setContentText(getString(R.string.live_text_abort_failed))
        .addAction(0, getString(R.string.live_action_abort), abortIntent())
        .build()

    private fun notificationStarting(deviceName: String): Notification = baseBuilder()
        .setContentTitle(getString(R.string.live_title_starting))
        .setContentText(
            if (deviceName.isBlank()) {
                getString(R.string.live_text_starting_plain)
            } else {
                getString(R.string.live_text_starting, deviceName)
            }
        )
        .setProgress(0, 0, true)
        .build()

    /** Countdown: Fortschritt laeuft rueckwaerts (max = Startwert, progress = Rest). */
    private fun notificationCountdown(totalSeconds: Int, remainingSeconds: Int): Notification {
        val max = maxOf(totalSeconds, remainingSeconds, 1)
        val remaining = remainingSeconds.coerceIn(0, max)
        return baseBuilder()
            .setContentTitle(getString(R.string.live_title_shutdown))
            .setContentText(getString(R.string.live_text_shutdown, remaining))
            .setProgress(max, remaining, false)
            .addAction(0, getString(R.string.live_action_abort), abortIntent())
            .build()
    }

    private fun notificationDownload(running: List<LiveDownload>): Notification {
        val single = running.singleOrNull()
        val title: String
        val percent: Double
        val downloaded: Long
        val total: Long

        if (single != null) {
            percent = single.percent.coerceIn(0.0, 100.0)
            downloaded = single.downloaded
            total = single.total
            title = if (single.name.isBlank()) {
                getString(R.string.live_title_download_plain)
            } else {
                getString(R.string.live_title_download_one, single.name)
            }
        } else {
            percent = overallPercent(running)
            downloaded = running.sumOf { it.downloaded }
            total = running.sumOf { it.total }
            title = resources.getQuantityString(
                R.plurals.live_title_download_many,
                running.size,
                running.size,
            )
        }

        val percentText = String.format(Locale.US, "%.1f %%", percent)
        val text = when {
            total > 0L && single == null ->
                getString(R.string.live_text_download_overall, percentText, sizesText(downloaded, total))
            total > 0L ->
                getString(R.string.live_text_download, percentText, sizesText(downloaded, total))
            single == null ->
                getString(R.string.live_text_download_overall_plain, percentText)
            else ->
                getString(R.string.live_text_download_plain, percentText)
        }

        val promille = (percent * 10.0).roundToInt().coerceIn(0, 1000)
        val known = total > 0L || percent > 0.0
        return baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(1000, promille, !known)
            .build()
    }

    // -----------------------------------------------------------------------
    // Kleine Helfer
    // -----------------------------------------------------------------------

    /** "12.3 / 29.0 MB" (bzw. GB ab 1 GiB). */
    private fun sizesText(downloaded: Long, total: Long): String {
        val useGib = total >= 1_073_741_824L
        val scale = if (useGib) 1024.0 * 1024.0 * 1024.0 else 1024.0 * 1024.0
        val unit = if (useGib) "GB" else "MB"
        val done = String.format(Locale.US, "%.1f", downloaded.coerceAtLeast(0L) / scale)
        val all = String.format(Locale.US, "%.1f", total / scale)
        return getString(R.string.live_sizes, done, all, unit)
    }

    private fun overallPercent(downloads: List<LiveDownload>): Double {
        if (downloads.isEmpty()) return 0.0
        val done = downloads.sumOf { it.downloaded }
        val total = downloads.sumOf { it.total }
        if (total > 0L) return (done.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
        val average = downloads.map { it.percent.coerceIn(0.0, 100.0) }.average()
        return if (average.isNaN()) 0.0 else average
    }

    private fun readProfile(intent: Intent): Profile? {
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = intent.getStringExtra(EXTRA_PORT)?.trim().orEmpty().ifBlank { "8000" }
        return Profile(
            id = "live",
            name = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty().ifBlank { "PC" },
            host = host,
            port = port,
            user = intent.getStringExtra(EXTRA_USER).orEmpty(),
            pass = intent.getStringExtra(EXTRA_PASS).orEmpty(),
        )
    }

    companion object {

        const val ACTION_SYNC = "com.example.pcsteuerung.action.LIVE_SYNC"
        const val ACTION_WAIT_ONLINE = "com.example.pcsteuerung.action.LIVE_WAIT_ONLINE"
        const val ACTION_ABORT = "com.example.pcsteuerung.action.LIVE_ABORT"
        const val ACTION_STOP = "com.example.pcsteuerung.action.LIVE_STOP"

        const val EXTRA_HOST = "live_host"
        const val EXTRA_PORT = "live_port"
        const val EXTRA_NAME = "live_name"
        const val EXTRA_USER = "live_user"
        const val EXTRA_PASS = "live_pass"
        const val EXTRA_WAIT_SECONDS = "live_wait_seconds"

        /** Kanal-ID; Name/Beschreibung stehen in `res/values/strings_live.xml`. */
        const val CHANNEL_ID = "pc_steuerung_live"
        const val DEFAULT_WAIT_SECONDS = 120

        private const val NOTIFICATION_ID = 4711
        private const val REQUEST_OPEN = 1
        private const val REQUEST_ABORT = 2

        private const val POLL_MS = 2_000L
        /** Antwortet der Agent nicht, nach 60 s aufgeben (kein Dauerlauf ohne Zweck). */
        private const val UNREACHABLE_GRACE_MS = 60_000L

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Live-Anzeige pruefen: zeigt Download/Countdown, sonst Ende nach 60 s. */
        fun startSync(context: Context, profile: Profile? = null) {
            start(context, Intent(context, LiveStatusService::class.java).setAction(ACTION_SYNC), profile)
        }

        /** Startphase anzeigen ("PC startet ..."), z. B. direkt nach dem WoL-Paket. */
        fun startWaitOnline(
            context: Context,
            profile: Profile? = null,
            waitSeconds: Int = DEFAULT_WAIT_SECONDS,
        ) {
            val intent = Intent(context, LiveStatusService::class.java)
                .setAction(ACTION_WAIT_ONLINE)
                .putExtra(EXTRA_WAIT_SECONDS, waitSeconds)
            start(context, intent, profile)
        }

        /** Live-Anzeige beenden (inklusive Benachrichtigung). */
        fun stop(context: Context) {
            val intent = Intent(context, LiveStatusService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(intent) } }
        }

        private fun start(context: Context, intent: Intent, profile: Profile?) {
            if (profile != null) {
                intent.putExtra(EXTRA_HOST, profile.host)
                intent.putExtra(EXTRA_PORT, profile.port)
                intent.putExtra(EXTRA_NAME, profile.name)
                intent.putExtra(EXTRA_USER, profile.user)
                intent.putExtra(EXTRA_PASS, profile.pass)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.d(LIVE_TAG, "Live-Dienst nicht gestartet: ${it.javaClass.simpleName}") }
        }
    }
}

private const val LIVE_TAG = "PCSteuerungLive"

/** Zeitlimit fuer eine einzelne Abfrage (der Dienst pollt alle 2 s). */
private const val LIVE_TIMEOUT_MS = 2_500

// ---------------------------------------------------------------------------
// Daten und Parser. Bewusst tolerant: fehlende Felder sind erlaubt, kaputtes
// JSON liefert null/leer statt einer Ausnahme.
// ---------------------------------------------------------------------------

/** Ein laufender Download aus `GET /steam` bzw. `GET /downloads`. */
data class LiveDownload(
    val name: String = "",
    val percent: Double = 0.0,
    val downloaded: Long = 0L,
    val total: Long = 0L,
) {
    val isFinished: Boolean
        get() = (total > 0L && downloaded >= total) || percent >= 100.0
}

/** Was `GET /status` fuer die Live-Karte hergibt. */
data class LiveStatusSnapshot(
    val deviceName: String = "",
    /** Geplante Gesamtdauer des Countdowns (`pending_seconds`). */
    val pendingTotal: Int = 0,
    /** Restsekunden (`pending_shutdown`). */
    val pendingRemaining: Int = 0,
)

/** `GET /status` auswerten; null = nicht erreichbar oder unlesbar. */
fun parseLiveStatus(body: String): LiveStatusSnapshot? {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return null
    return runCatching {
        val json = JSONObject(text)
        LiveStatusSnapshot(
            deviceName = json.optString("device_name").trim().ifBlank {
                json.optString("host").trim()
            },
            pendingTotal = json.optInt("pending_seconds", 0),
            pendingRemaining = json.optInt("pending_shutdown", 0),
        )
    }.getOrNull()
}

/**
 * `GET /steam` bzw. `GET /downloads` auswerten.
 * null = Route fehlt / Antwort unlesbar (z. B. aeltere Agent-Version).
 */
fun parseLiveDownloads(body: String): List<LiveDownload>? {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return null
    return runCatching {
        if (text.startsWith("[")) {
            liveDownloadsFrom(JSONArray(text))
        } else {
            val json = JSONObject(text)
            val list = liveDownloadsFrom(json.optJSONArray("downloads"))
            if (list.isNotEmpty()) {
                list
            } else {
                // Einzeldownload: /steam liefert die Werte auch auf oberster Ebene.
                val total = json.optLong("bytes_total", 0L)
                val percent = json.optDouble("progress_percent", 0.0)
                if (json.optBoolean("downloading", false) && (total > 0L || percent > 0.0)) {
                    listOf(
                        LiveDownload(
                            name = json.optString("app_name").trim(),
                            percent = percent,
                            downloaded = json.optLong("bytes_downloaded", 0L),
                            total = total,
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
    }.getOrNull()
}

private fun liveDownloadsFrom(array: JSONArray?): List<LiveDownload> {
    if (array == null || array.length() == 0) return emptyList()
    val out = ArrayList<LiveDownload>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val name = item.optString("name").trim().ifBlank { item.optString("app_name").trim() }
        out.add(
            LiveDownload(
                name = name,
                percent = item.optDouble("percent", 0.0),
                downloaded = item.optLong("bytes_downloaded", 0L),
                total = item.optLong("bytes_total", 0L),
            )
        )
    }
    return out
}

// ---------------------------------------------------------------------------
// HTTP (blockierend; immer ueber Dispatchers.IO aufrufen)
// ---------------------------------------------------------------------------

private class LiveResponse(val code: Int, val body: String)

private suspend fun httpGet(base: String, path: String, auth: AuthHeader?): LiveResponse =
    withContext(Dispatchers.IO) { liveRequest(base, path, auth, "GET") }

private suspend fun httpPost(base: String, path: String, auth: AuthHeader?): LiveResponse =
    withContext(Dispatchers.IO) { liveRequest(base, path, auth, "POST") }

private fun liveRequest(
    base: String,
    path: String,
    auth: AuthHeader?,
    method: String,
): LiveResponse {
    return try {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = LIVE_TIMEOUT_MS
            conn.readTimeout = LIVE_TIMEOUT_MS
            // Ein lokaler Agent verlangt die CSRF-Kopfzeile fuer schreibende
            // Aufrufe; ueber das LAN ist sie harmlos.
            conn.setRequestProperty("X-PC-Steuerung", "1")
            auth?.let { conn.setRequestProperty(it.name, it.value) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            LiveResponse(code, body)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.d(LIVE_TAG, "Netzfehler ${base + path}: ${e.javaClass.simpleName}")
        LiveResponse(0, "")
    }
}
