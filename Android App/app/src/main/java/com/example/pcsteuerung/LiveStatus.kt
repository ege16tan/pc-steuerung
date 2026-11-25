package com.example.pcsteuerung

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
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
 *                        Titel = Spiel, Text = Prozent, Tempo und Restzeit
 *  b) Herunterfahren  -> `pending_seconds` / `pending_shutdown` aus `GET /status`
 *  c) Startphase      -> "PC startet ...", bis `/status` antwortet
 *  d) Dauerkarte      -> Agent antwortet und es laeuft nichts: "PC ist an" mit
 *                        den Knoepfen "Herunterfahren" und "Ruhezustand"
 *  e) Warnungen       -> `GET /warnings` (Phase C): je ausgeloestem Ereignis
 *                        eine eigene, antippbare Benachrichtigung (kein Dauerlauf)
 *
 * Die Dauerkarte (d) ist bewusst eine normale ONGOING-Benachrichtigung ohne
 * Fortschrittsbalken (Kategorie STATUS): sie bleibt stehen, solange der PC
 * antwortet, und wird nur alle [IDLE_POLL_MS] nachgefragt, damit der Akku
 * nicht leidet. Die Knoepfe schicken dieselben Aufrufe wie die App
 * (`/shutdown?delay=` mit Countdown, `/hibernate`). Abschaltbar in den
 * Einstellungen (`AppSettings.onlineCard`).
 *
 * Auf Android 16 (API 36) wird die Karte als echtes "Live Update" mit
 * `Notification.ProgressStyle` und Kurztext fuer den Statusleisten-Chip
 * gebaut (`setShortCriticalText`). Aeltere Versionen bekommen denselben Inhalt
 * als normale Fortschritts-Benachrichtigung.
 *
 * Der Countdown laeuft lokal im Sekundentakt weiter (der Agent wird nur alle
 * [POLL_MS] gefragt), Downloads werden im ruhigen 3-s-Takt gelesen - Steam
 * schreibt die `.acf` ohnehin nicht oefter. Alle Netzaufrufe laufen in
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

    /** Dauerkarte aktiv? (Einstellung `onlineCard`, Default an) */
    private var onlineCard = true

    /** Steht gerade nur die Dauerkarte? (ruhiger Abfragetakt) */
    private var idleCard = false

    /** Wurde der PC schon einmal nicht erreicht? (0 = nein) */
    private var unreachableSinceMs = 0L

    /** Countdown aus `/status`: lokales Weiterzaehlen zwischen zwei Abfragen. */
    private var countdownTotal = 0
    private var countdownRemaining = 0
    private var countdownSyncedAt = 0L

    /** Zeitpunkt der letzten Netzabfrage (fuer den ruhigen Poll-Takt). */
    private var lastPollAt = 0L

    /** Zeitpunkt der letzten Warnungsabfrage (`/warnings`, Phase C). */
    private var lastWarningsAt = 0L

    /** Diagnose fuer die Live-Updates-Freigabe nur einmal ins Log. */
    private var promotionLogged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC

        // Zuerst eine Benachrichtigung setzen: ein per startForegroundService
        // gestarteter Dienst muss das innerhalb von 5 s tun (sonst stuerzt
        // Android 14+ die App ab). Laeuft die Schleife schon, bleibt die
        // aktuelle Karte stehen (kein Flackern bei jedem ACTION_SYNC).
        if (loop?.isActive != true) postForeground(notificationChecking())

        // Ein Knopf der Dauerkarte bringt keine Verbindungsdaten mit: dann gilt
        // das laufende Profil (die Karte zeigt genau diesen PC). Ein Anstoss
        // aus der App liest dagegen das aktive Profil neu (Profilwechsel).
        val fromCard =
            action == ACTION_SHUTDOWN || action == ACTION_HIBERNATE || action == ACTION_ABORT
        val stored = if (fromCard) profile else ProfileStore.active(this)
        profile = intent?.let { readProfile(it) } ?: stored ?: ProfileStore.active(this)
        // Die Dauerkarte laesst sich in den Einstellungen abschalten; der Wert
        // wird bei jedem Aufruf neu gelesen, damit Umschalten sofort wirkt.
        onlineCard = AppSettingsStore.load(this).onlineCard
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
            // Knoepfe der Dauerkarte: Aktion an den Agenten, Dienst laeuft weiter.
            ACTION_SHUTDOWN -> requestPower(active, PowerAction.SHUTDOWN)
            ACTION_HIBERNATE -> requestPower(active, PowerAction.HIBERNATE)
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
        // Sofort eine Runde drehen (z. B. nach einem fehlgeschlagenen Abbruch),
        // nicht erst nach dem naechsten Takt.
        lastPollAt = 0L
        loop = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                // Countdown tickt lokal jede Sekunde, die Dauerkarte braucht
                // dagegen nur einen ruhigen Takt (ein Knopf weckt die Schleife).
                val tick = when {
                    countdownRemaining > 0 -> COUNTDOWN_TICK_MS
                    idleCard -> IDLE_POLL_MS
                    else -> POLL_MS
                }
                val pollEvery = if (idleCard) IDLE_POLL_MS else POLL_MS
                val pollDue = now - lastPollAt >= pollEvery
                val keepRunning = if (pollDue) {
                    lastPollAt = now
                    runCatching { poll() }.getOrElse { error ->
                        Log.d(LIVE_TAG, "Live-Abfrage fehlgeschlagen: ${error.javaClass.simpleName}")
                        false
                    }
                } else {
                    // Zwischen zwei Abfragen zaehlt der Countdown lokal weiter,
                    // damit die Karte nicht in 3-s-Spruengen springt.
                    tickCountdown()
                    true
                }
                if (!keepRunning) {
                    teardown()
                    return@launch
                }
                delay(tick)
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
        // Antwortet der PC, ist die Startphase vorbei - auch wenn sie vorher
        // angefordert wurde.
        if (reachable) waitOnline = false

        // Nur fragen, wenn der Agent ueberhaupt antwortet. 404 = Route fehlt
        // (aeltere Agent-Version) -> Steam-Zustand ehrlich ueberspringen.
        val downloads = if (reachable) readDownloads(active, auth) else emptyList()
        val running = downloads.filter { !it.isFinished }

        val remaining = status?.pendingRemaining ?: 0
        val total = status?.pendingTotal ?: 0
        idleCard = false

        // Warnungen (Phase C): der Agent prueft alle 30 s. Ein eigener, ruhiger
        // Takt reicht - ein Handy im Standby soll nicht dauernd fragen.
        if (reachable && now - lastWarningsAt >= WARNINGS_POLL_MS) {
            lastWarningsAt = now
            checkWarnings(active, auth)
        }

        return when {
            remaining > 0 -> {
                clearGrace()
                countdownTotal = maxOf(total, remaining)
                countdownRemaining = remaining
                countdownSyncedAt = now
                postForeground(notificationCountdown(countdownTotal, remaining))
                true
            }
            running.isNotEmpty() -> {
                clearGrace()
                countdownTotal = 0
                countdownRemaining = 0
                postForeground(notificationDownload(running))
                true
            }
            // Antwortet der Agent und laeuft nichts, uebernimmt die Dauerkarte
            // (falls abgeschaltet: sofort beenden, kein Dauerlauf ohne Zweck).
            reachable -> {
                countdownTotal = 0
                countdownRemaining = 0
                if (!onlineCard) {
                    false
                } else {
                    clearGrace()
                    idleCard = true
                    postForeground(notificationOnline(active.name))
                    true
                }
            }
            else -> {
                // PC nicht erreichbar: Gnadenfrist, dann aufgeben. Ein lokaler
                // Countdown waere hier geraten - deshalb sofort auf "unbekannt".
                countdownTotal = 0
                countdownRemaining = 0
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

    /** Countdown zwischen zwei Abfragen lokal weiterzaehlen. */
    private fun tickCountdown() {
        if (countdownRemaining <= 0) return
        val elapsed = ((SystemClock.elapsedRealtime() - countdownSyncedAt) / 1000L).toInt()
        val left = (countdownRemaining - elapsed).coerceAtLeast(0)
        postForeground(notificationCountdown(countdownTotal, left))
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
    // Warnungen (Phase C)
    // -----------------------------------------------------------------------

    /**
     * Neue Warnungen aus `GET /warnings` melden.
     *
     * Jedes Ereignis genau einmal: der Schluessel ist `since_epoch`. Beim ersten
     * Lauf (nach Installation/Update) wird nur gemerkt, damit alte Eintraege aus
     * frueheren Sitzungen keine Lawine ausloesen.
     */
    private suspend fun checkWarnings(active: Profile, auth: AuthHeader?) {
        val response = httpGet(active.base, "/warnings", auth)
        if (response.code !in 200..299) return
        val alerts = parseWarningAlerts(response.body)
        if (alerts.isEmpty()) return
        val prefs = getSharedPreferences(WARNING_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(WARNING_SEEN_READY, false)) {
            saveSeenWarnings(prefs, warningEpochs(alerts))
            return
        }
        val seen = prefs.getStringSet(WARNING_SEEN, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        val fresh = newWarningAlerts(alerts, seen)
        saveSeenWarnings(prefs, seen + warningEpochs(alerts))
        fresh.forEach { postWarning(it) }
    }

    private fun saveSeenWarnings(prefs: SharedPreferences, epochs: Set<Long>) {
        prefs.edit()
            .putBoolean(WARNING_SEEN_READY, true)
            .putStringSet(WARNING_SEEN, epochs.map { it.toString() }.toSet())
            .apply()
    }

    /** Eine Benachrichtigung je Warnung (antippbar = App), keine Dauerkarte. */
    private fun postWarning(alert: WarningAlert) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val label = alert.label.ifBlank { getString(R.string.live_warning_plain) }
        val notification = Notification.Builder(this, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFFC62828.toInt())
            .setContentTitle(getString(R.string.live_warning_title, label))
            .setContentText(alert.message)
            .setStyle(Notification.BigTextStyle().bigText(alert.message))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .build()
        // Eigene ID je Ereignis, damit eine neue Warnung die alte nicht ersetzt.
        runCatching {
            manager.notify(
                WARNING_NOTIFICATION_BASE + (alert.sinceEpoch.toLong() % 1000).toInt(),
                notification,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Abbrechen (Action-Button der Karte)
    // -----------------------------------------------------------------------

    private fun abortShutdown(active: Profile) {
        scope.launch {
            val response = httpPost(active.base, "/abort", active.authHeader())
            Log.d(LIVE_TAG, "POST /abort -> ${response.code}")
            if (response.code in 200..299) {
                // Zurueck zur Dauerkarte (bzw. Ende, wenn sie abgeschaltet ist).
                restartLoop()
            } else {
                // Ehrlich bleiben: der Countdown laeuft weiter, also weiter anzeigen.
                postForeground(notificationAbortFailed())
                startLoop()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Knoepfe der Dauerkarte (Herunterfahren / Ruhezustand)
    // -----------------------------------------------------------------------

    private enum class PowerAction { SHUTDOWN, HIBERNATE }

    /**
     * Schickt einen Knopfdruck der Dauerkarte an den Agenten - dieselben
     * Endpunkte wie in der App. "Herunterfahren" nutzt den Standard-Timer des
     * Profils (mindestens [MIN_SHUTDOWN_DELAY] s, damit ein versehentlicher
     * Druck auf dem Sperrbildschirm noch abgebrochen werden kann).
     * Ein Fehlschlag steht kurz in der Karte, statt still zu verpuffen.
     */
    private fun requestPower(active: Profile, action: PowerAction) {
        val path = when (action) {
            PowerAction.SHUTDOWN -> liveShutdownPath(active.defaultDelay)
            PowerAction.HIBERNATE -> "/hibernate"
        }
        scope.launch {
            val response = httpPost(active.base, path, active.authHeader())
            Log.d(LIVE_TAG, "POST $path -> ${response.code}")
            if (response.code in 200..299) {
                val seconds = liveShutdownDelay(active.defaultDelay)
                postForeground(notificationPowerSent(action, seconds))
                // Sofort neu fragen: bei "Herunterfahren" steht dann schon der
                // Countdown des Agenten in der Karte, sonst der neue Zustand.
                restartLoop()
            } else {
                postForeground(notificationPowerFailed(action, response.code))
            }
        }
    }

    /** Schleife neu starten, damit die naechste Abfrage sofort laeuft. */
    private fun restartLoop() {
        loop?.cancel()
        loop = null
        startLoop()
    }

    // -----------------------------------------------------------------------
    // Benachrichtigungen
    // -----------------------------------------------------------------------

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.live_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.live_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
        // Eigener Kanal fuer Warnungen (Phase C): hoerbar, aber ohne Dauerkarte.
        if (manager.getNotificationChannel(WARNING_CHANNEL_ID) == null) {
            val warnings = NotificationChannel(
                WARNING_CHANNEL_ID,
                getString(R.string.live_warning_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.live_warning_channel_desc)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(warnings)
        }
    }

    private fun postForeground(notification: Notification) {
        // Dauerkarte und Countdown laufen ohne festes Ende: deshalb ab Android 14
        // "specialUse" (kein 6-Stunden-Limit wie bei dataSync seit Android 15).
        // Aeltere Versionen kennen den Typ nicht und bleiben bei dataSync.
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { Log.d(LIVE_TAG, "startForeground fehlgeschlagen: ${it.javaClass.simpleName}") }
    }

    private fun teardown() {
        loop?.cancel()
        loop = null
        countdownTotal = 0
        countdownRemaining = 0
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Inhalt einer Live-Karte. Alle Zustaende (Download, Countdown, Startphase)
     * werden zuerst als [LiveCard] beschrieben und danach einmal gebaut - so
     * sehen Android 16 (Live Updates) und aeltere Versionen denselben Inhalt.
     */
    private data class LiveCard(
        val title: String,
        val text: String,
        /** Fortschritt in Promille (0..1000); null = unbestimmt. */
        val permille: Int? = null,
        /** Kurztext fuer den Chip der Statusleiste (Android 16 Live Updates). */
        val shortCritical: String? = null,
        val showAbort: Boolean = false,
        /** false = reine Statuskarte (Dauerkarte) ohne Fortschrittsbalken. */
        val progress: Boolean = true,
        /** Knoepfe "Herunterfahren" und "Ruhezustand" (Dauerkarte). */
        val powerActions: Boolean = false,
    )

    private fun buildNotification(card: LiveCard): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFF1565C0.toInt())
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(
                if (card.progress) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS
            )
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (card.progress) {
            val permille = card.permille?.coerceIn(0, 1000)
            if (Build.VERSION.SDK_INT >= ANDROID_16) {
                // Android 16 "Live Updates": Segment als Balken (Gewicht 1000 passt
                // zu Promille) plus Kurztext, den Samsung in der Now Bar als Chip
                // zeigt. Ohne Segment bliebe die Karte eine normale Notification.
                val style = Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
                    .setProgress(permille ?: 0)
                    .setProgressIndeterminate(permille == null)
                builder.setStyle(style)
                val shortCritical = card.shortCritical
                if (shortCritical != null) {
                    // Manche OEMs pruefen den Chip-Text streng - ein Fehler darf
                    // niemals die Benachrichtigung (und damit den Dienst) kippen.
                    runCatching { builder.setShortCriticalText(shortCritical) }
                        .onFailure { Log.d(LIVE_TAG, "Kurztext abgelehnt: ${it.javaClass.simpleName}") }
                }
            } else if (permille != null) {
                builder.setProgress(1000, permille, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }

        if (card.showAbort) {
            builder.addAction(0, getString(R.string.live_action_abort), abortIntent())
        }
        if (card.powerActions) {
            // Reihenfolge wie in der App: Herunterfahren (mit Countdown) zuerst.
            builder.addAction(
                0,
                getString(R.string.live_action_shutdown),
                powerIntent(ACTION_SHUTDOWN, REQUEST_SHUTDOWN),
            )
            builder.addAction(
                0,
                getString(R.string.live_action_hibernate),
                powerIntent(ACTION_HIBERNATE, REQUEST_HIBERNATE),
            )
        }
        val notification = builder.build()
        logPromotionOnce(notification)
        return notification
    }

    /** Einmal ins Log, ob das System Live Updates fuer die App zulaesst. */
    private fun logPromotionOnce(notification: Notification) {
        if (Build.VERSION.SDK_INT < ANDROID_16 || promotionLogged) return
        promotionLogged = true
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            Log.d(
                LIVE_TAG,
                "Live-Updates: erlaubt=${manager?.canPostPromotedNotifications()}, " +
                    "geeignet=${notification.hasPromotableCharacteristics()}",
            )
        }
    }

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

    /** Knopf der Dauerkarte; wie beim Abbrechen eine erlaubte Nutzeraktion. */
    private fun powerIntent(action: String, request: Int): PendingIntent {
        val intent = Intent(this, LiveStatusService::class.java).setAction(action)
        return PendingIntent.getForegroundService(this, request, intent, PENDING_FLAGS)
    }

    private fun notificationChecking(): Notification = buildNotification(
        LiveCard(
            title = getString(R.string.live_title_checking),
            text = getString(R.string.live_text_checking),
        )
    )

    private fun notificationUnknown(): Notification = buildNotification(
        LiveCard(
            title = getString(R.string.live_title_unknown),
            text = getString(R.string.live_text_unknown),
        )
    )

    private fun notificationAbortFailed(): Notification = buildNotification(
        LiveCard(
            title = getString(R.string.live_title_shutdown),
            text = getString(R.string.live_text_abort_failed),
            // Nach dem Fehlschlag laeuft der Countdown weiter: Balken unbestimmt,
            // Abbruch bleibt angeboten.
            showAbort = true,
        )
    )

    /**
     * Dauerkarte: der PC antwortet und es laeuft nichts. Zwei Knoepfe, damit
     * Herunterfahren/Ruhezustand ohne Oeffnen der App moeglich sind.
     */
    private fun notificationOnline(deviceName: String): Notification = buildNotification(
        LiveCard(
            title = if (deviceName.isBlank()) {
                getString(R.string.live_title_online_plain)
            } else {
                getString(R.string.live_title_online, deviceName)
            },
            text = getString(R.string.live_text_online),
            progress = false,
            powerActions = true,
        )
    )

    /** Rueckmeldung nach einem Knopf der Dauerkarte. */
    private fun notificationPowerSent(action: PowerAction, delaySeconds: Int): Notification =
        when (action) {
            PowerAction.SHUTDOWN -> buildNotification(
                LiveCard(
                    title = getString(R.string.live_title_shutdown),
                    text = getString(R.string.live_text_shutdown_sent, delaySeconds),
                    // Abbruch bleibt angeboten, bis der Countdown des Agenten
                    // in der naechsten Abfrage in der Karte steht.
                    showAbort = true,
                )
            )
            PowerAction.HIBERNATE -> buildNotification(
                LiveCard(
                    title = getString(R.string.live_title_hibernate),
                    text = getString(R.string.live_text_hibernate_sent),
                    progress = false,
                    powerActions = true,
                )
            )
        }

    /** Ehrlich bleiben: der Aufruf kam nicht durch (401, PC aus, ...). */
    private fun notificationPowerFailed(action: PowerAction, code: Int): Notification =
        buildNotification(
            LiveCard(
                title = when (action) {
                    PowerAction.SHUTDOWN -> getString(R.string.live_title_shutdown)
                    PowerAction.HIBERNATE -> getString(R.string.live_title_hibernate)
                },
                // code 0 = keine Verbindung (siehe `liveRequest`).
                text = if (code == 0) {
                    getString(R.string.live_text_action_unreachable)
                } else {
                    getString(R.string.live_text_action_failed, code)
                },
                progress = false,
                powerActions = true,
            )
        )

    private fun notificationStarting(deviceName: String): Notification = buildNotification(
        LiveCard(
            title = getString(R.string.live_title_starting),
            text = if (deviceName.isBlank()) {
                getString(R.string.live_text_starting_plain)
            } else {
                getString(R.string.live_text_starting, deviceName)
            },
        )
    )

    /**
     * Countdown: der Balken fuellt sich (abgelaufene Zeit), der Text und der
     * Chip zeigen die Restzeit.
     */
    private fun notificationCountdown(totalSeconds: Int, remainingSeconds: Int): Notification {
        val max = maxOf(totalSeconds, remainingSeconds, 1)
        val remaining = remainingSeconds.coerceIn(0, max)
        val permille = ((max - remaining) * 1000 / max).coerceIn(0, 1000)
        val remainingText = getString(R.string.live_text_shutdown, remaining)
        return buildNotification(
            LiveCard(
                title = getString(R.string.live_title_shutdown),
                text = remainingText,
                permille = permille,
                // Chip bewusst knapp ("42 s"), der Fliesstext bleibt "noch 42 s".
                shortCritical = getString(R.string.live_short_seconds, remaining),
                showAbort = true,
            )
        )
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

        // Tempo/Restzeit: bei einem Download direkt, bei mehreren als Summe.
        val speedBps = if (single != null) single.speedBps else running.sumOf { it.speedBps }
        val etaSeconds = if (single != null) {
            single.etaSeconds
        } else {
            overallEta(running, speedBps)
        }

        val percentText = String.format(Locale.US, "%.1f %%", percent)
        val parts = ArrayList<String>(4)
        parts.add(percentText)
        if (speedBps > 0L) {
            parts.add(speedText(speedBps))
            if (etaSeconds > 0L) parts.add(etaText(etaSeconds))
        } else if (total > 0L) {
            parts.add(sizesText(downloaded, total))
        }
        val text = parts.joinToString(" \u00b7 ")

        val permille = (percent * 10.0).roundToInt().coerceIn(0, 1000)
        val known = total > 0L || percent > 0.0
        return buildNotification(
            LiveCard(
                title = title,
                text = text,
                permille = if (known) permille else null,
                // Chip nur mit echtem Wert - "0 %" waere bei unbekanntem
                // Fortschritt eine Falschangabe.
                shortCritical = if (known) {
                    getString(R.string.live_short_percent, percent.roundToInt())
                } else {
                    null
                },
            )
        )
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

    /** "4.2 MB/s" (KB/s und GB/s mit passender Einheit, dezimal wie Steam). */
    private fun speedText(bps: Long): String = when {
        bps >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB/s", bps / 1_000_000_000.0)
        bps >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", bps / 1_000_000.0)
        bps >= 1_000L -> String.format(Locale.US, "%.0f KB/s", bps / 1_000.0)
        else -> "$bps B/s"
    }

    /** "noch 12 Min." / "noch 40 s" / "noch 1 Std. 5 Min." */
    private fun etaText(seconds: Long): String {
        val value = seconds.coerceAtLeast(0L)
        return when {
            value < 60L -> getString(R.string.live_eta_seconds, value.toInt())
            value < 3600L -> getString(R.string.live_eta_minutes, (value / 60L).toInt())
            else -> getString(
                R.string.live_eta_hours,
                (value / 3600L).toInt(),
                ((value % 3600L) / 60L).toInt(),
            )
        }
    }

    /** Restzeit fuer mehrere Downloads: Restbytes geteilt durch Summe der Tempi. */
    private fun overallEta(downloads: List<LiveDownload>, speedBps: Long): Long {
        if (speedBps <= 0L) return 0L
        val remaining = downloads.sumOf { (it.total - it.downloaded).coerceAtLeast(0L) }
        if (remaining <= 0L) return 0L
        return remaining / speedBps
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
            // Standard-Timer des Profils: bestimmt den Countdown, wenn
            // "Herunterfahren" direkt aus der Karte kommt.
            defaultDelay = intent.getIntExtra(EXTRA_DELAY, 60),
        )
    }

    companion object {

        const val ACTION_SYNC = "com.example.pcsteuerung.action.LIVE_SYNC"
        const val ACTION_WAIT_ONLINE = "com.example.pcsteuerung.action.LIVE_WAIT_ONLINE"
        const val ACTION_ABORT = "com.example.pcsteuerung.action.LIVE_ABORT"
        const val ACTION_SHUTDOWN = "com.example.pcsteuerung.action.LIVE_SHUTDOWN"
        const val ACTION_HIBERNATE = "com.example.pcsteuerung.action.LIVE_HIBERNATE"
        const val ACTION_STOP = "com.example.pcsteuerung.action.LIVE_STOP"

        const val EXTRA_HOST = "live_host"
        const val EXTRA_PORT = "live_port"
        const val EXTRA_NAME = "live_name"
        const val EXTRA_USER = "live_user"
        const val EXTRA_PASS = "live_pass"
        const val EXTRA_DELAY = "live_delay"
        const val EXTRA_WAIT_SECONDS = "live_wait_seconds"

        /** Kanal-ID; Name/Beschreibung stehen in `res/values/strings_live.xml`. */
        const val CHANNEL_ID = "pc_steuerung_live"
        /** Eigener Kanal fuer Warnungen (Phase C), ebenfalls in `strings_live.xml`. */
        const val WARNING_CHANNEL_ID = "pc_steuerung_warnings"
        const val DEFAULT_WAIT_SECONDS = 120

        private const val NOTIFICATION_ID = 4711
        private const val WARNING_NOTIFICATION_BASE = 7400
        private const val REQUEST_OPEN = 1
        private const val REQUEST_ABORT = 2
        private const val REQUEST_SHUTDOWN = 3
        private const val REQUEST_HIBERNATE = 4

        private const val POLL_MS = 3_000L
        /** Dauerkarte: ruhiger Takt, damit die Abfrage nicht am Akku zieht. */
        private const val IDLE_POLL_MS = 15_000L
        /** Warnungen: der Agent prueft alle 30 s (Phase C). */
        private const val WARNINGS_POLL_MS = 30_000L
        /** Merkt gemeldete Warnungen (Ereignis-Zeitpunkt) ueber Neustarts hinweg. */
        private const val WARNING_PREFS = "pc_warnings"
        private const val WARNING_SEEN = "seen_epochs"
        private const val WARNING_SEEN_READY = "seen_ready"
        /** Countdown lokal jede Sekunde neu zeichnen (ohne Netzabfrage). */
        private const val COUNTDOWN_TICK_MS = 1_000L
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

        /**
         * Einstellungen neu einlesen (Dauerkarte an/aus), ohne die laufende
         * Karte zu ersetzen. `startService` genuegt: der Aufruf kommt aus der
         * App im Vordergrund, und der Dienst beendet sich selbst, wenn nichts
         * mehr anzuzeigen ist.
         */
        fun refreshSettings(context: Context) {
            val intent = Intent(context, LiveStatusService::class.java).setAction(ACTION_SYNC)
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.d(LIVE_TAG, "Einstellungen nicht uebernommen: ${it.javaClass.simpleName}")
                }
        }

        private fun start(context: Context, intent: Intent, profile: Profile?) {
            if (profile != null) {
                intent.putExtra(EXTRA_HOST, profile.host)
                intent.putExtra(EXTRA_PORT, profile.port)
                intent.putExtra(EXTRA_NAME, profile.name)
                intent.putExtra(EXTRA_USER, profile.user)
                intent.putExtra(EXTRA_PASS, profile.pass)
                intent.putExtra(EXTRA_DELAY, profile.defaultDelay)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.d(LIVE_TAG, "Live-Dienst nicht gestartet: ${it.javaClass.simpleName}") }
        }
    }
}

private const val LIVE_TAG = "PCSteuerungLive"

/** Ab dieser API gibt es "Live Updates" (`Notification.ProgressStyle`). */
private const val ANDROID_16 = 36

/** Zeitlimit fuer eine einzelne Abfrage (der Dienst pollt alle 3 s). */
private const val LIVE_TIMEOUT_MS = 2_500

// ---------------------------------------------------------------------------
// Countdown fuer die Karte "Herunterfahren"
// ---------------------------------------------------------------------------

/** Kuerzester Countdown: ein Fehlgriff in der Karte soll abbrechbar bleiben. */
private const val MIN_SHUTDOWN_DELAY = 10

/** Obergrenze gegen Unsinnswerte aus alten Profilen (1 Stunde). */
private const val MAX_SHUTDOWN_DELAY = 3_600

/** Countdown in Sekunden, den "Herunterfahren" aus der Karte wirklich nutzt. */
fun liveShutdownDelay(delaySeconds: Int): Int =
    delaySeconds.coerceIn(MIN_SHUTDOWN_DELAY, MAX_SHUTDOWN_DELAY)

/** Pfad fuer "Herunterfahren" aus der Dauerkarte. */
fun liveShutdownPath(delaySeconds: Int): String =
    "/shutdown?delay=${liveShutdownDelay(delaySeconds)}"

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
    /** Tempo aus zwei Messungen des Agenten (0 = noch unbekannt). */
    val speedBps: Long = 0L,
    /** Restzeit in Sekunden laut Agent (0 = unbekannt/fertig). */
    val etaSeconds: Long = 0L,
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
                            speedBps = json.optLong("speed_bps", 0L),
                            etaSeconds = json.optLong("eta_seconds", 0L),
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
                speedBps = item.optLong("speed_bps", 0L),
                etaSeconds = item.optLong("eta_seconds", 0L),
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
