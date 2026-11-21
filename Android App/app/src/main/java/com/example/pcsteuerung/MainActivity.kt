package com.example.pcsteuerung

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

const val EXTRA_CONFIRM_SHUTDOWN = "confirm_shutdown"
const val ACTION_SHORTCUT_SHUTDOWN = "com.example.pcsteuerung.action.SHORTCUT_SHUTDOWN"
const val ACTION_SHORTCUT_WAKE = "com.example.pcsteuerung.action.SHORTCUT_WAKE"

class MainActivity : ComponentActivity() {
    private val confirmShutdownRequest = mutableStateOf(false)
    private val wakeRequest = mutableStateOf(false)

    // Live-Benachrichtigung (Now Bar): ab Android 13 muss die Berechtigung
    // erfragt werden, sonst zeigt der Dienst nichts an.
    private val liveNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun ensureLiveNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { liveNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Live-Benachrichtigung einmal anstossen; der Dienst beendet sich
        // selbst, wenn es nichts anzuzeigen gibt (Download/Countdown/Start).
        ensureLiveNotificationPermission()
        if (ProfileStore.active(this)?.isComplete == true) LiveStatusService.startSync(this)
        // Bei einer Neuerstellung (z. B. Drehung) den alten Shortcut-Intent
        // nicht erneut ausloesen - sonst wuerde der PC erneut geweckt.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var settings by remember { mutableStateOf(AppSettingsStore.load(context)) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val targetScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }
            // Sanfter Theme-Wechsel: die Farben des Schemas werden einzeln animiert
            // (echter Crossfade) statt den Inhalt abzublenden oder hart umzuschalten.
            val colorScheme = animatedColorScheme(targetScheme)

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(
                        settings = settings,
                        onSettingsChange = { updated ->
                            val cardChanged = updated.onlineCard != settings.onlineCard
                            AppSettingsStore.save(context, updated)
                            settings = updated
                            // Dauerkarte sofort an/aus: an -> Dienst starten,
                            // aus -> Dienst liest die Einstellung neu und
                            // beendet sich, wenn nichts anderes laeuft.
                            if (cardChanged) {
                                val ready = ProfileStore.active(context)?.isComplete == true
                                if (updated.onlineCard && ready) {
                                    LiveStatusService.startSync(context)
                                } else {
                                    LiveStatusService.refreshSettings(context)
                                }
                            }
                        },
                        confirmShutdown = confirmShutdownRequest.value,
                        onConfirmConsumed = { confirmShutdownRequest.value = false },
                        startWithWake = wakeRequest.value,
                        onWakeConsumed = { wakeRequest.value = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_SHORTCUT_SHUTDOWN -> confirmShutdownRequest.value = true
            ACTION_SHORTCUT_WAKE -> wakeRequest.value = true
        }
        if (intent.getBooleanExtra(EXTRA_CONFIRM_SHUTDOWN, false)) {
            confirmShutdownRequest.value = true
        }
        // Einmal ausgewertet: Bei einer spaeteren Neuerstellung nicht erneut
        // ausloesen (sonst wuerde z. B. der PC noch einmal geweckt).
        intent.removeExtra(EXTRA_CONFIRM_SHUTDOWN)
        intent.action = null
    }
}

private const val TAG = "PCSteuerung"

/**
 * Blockierender HTTP-Aufruf, immer aus Dispatchers.IO heraus verwenden.
 * [body] wird als JSON mitgeschickt (nur fuer POST/PUT noetig, z. B. Routinen).
 */
fun httpCall(
    base: String,
    path: String,
    auth: AuthHeader?,
    method: String = "GET",
    body: String? = null,
): String {
    Log.d(TAG, "HTTP $method ${base + path}")
    val conn = URL(base + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (auth != null) conn.setRequestProperty(auth.name, auth.value)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val answer = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        Log.d(TAG, "HTTP $method ${base + path} -> $code")
        return if (code in 200..299) answer else "HTTP $code: $answer"
    } finally {
        conn.disconnect()
    }
}

private fun statusColor(status: PcStatus): Color = when (status) {
    PcStatus.ONLINE -> Color(0xFF4CAF50)
    PcStatus.ERROR -> Color(0xFFFFC107)
    // PC aus: rot statt grau, damit "aus" auf einen Blick erkennbar ist.
    PcStatus.OFFLINE -> Color(0xFFE53935)
    PcStatus.UNKNOWN -> Color(0xFF9E9E9E)
}

// ---------------------------------------------------------------------------
// Daten der Kacheln: Steam, Routinen und Ereignisse werden absichtlich
// tolerant gelesen (optJSON*). Ein Agent ohne die neue Route /steam antwortet
// mit 404 - die Kachel zeigt dann "nicht verfuegbar" statt abzustuerzen.
// ---------------------------------------------------------------------------

/** Ein Download aus `GET /steam` bzw. `GET /downloads`. */
data class SteamDownload(
    val appId: String = "",
    val name: String = "",
    val percent: Double = 0.0,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    /** Tempo laut Agent (0 = noch unbekannt, der Agent braucht zwei Messungen). */
    val speedBps: Long = 0L,
    /** Restzeit in Sekunden laut Agent (0 = unbekannt/fertig). */
    val etaSeconds: Long = 0L,
    /** Fortschritt auf der Platte (`BytesStaged`), 0 ohne Staging-Angaben. */
    val percentStaged: Double = 0.0,
)

/**
 * Antwort von `GET /steam` (Vertrag: docs/steam-plugin-und-kacheln.md).
 * Alles optional, damit auch ein halbfertiger Agent die App nicht stoert.
 */
data class SteamStatus(
    val detected: Boolean = false,
    val millennium: Boolean = false,
    val running: Boolean = false,
    val downloading: Boolean = false,
    val progressPercent: Double = 0.0,
    val speedBps: Long = 0L,
    val etaSeconds: Long = 0L,
    val appName: String = "",
    val downloads: List<SteamDownload> = emptyList(),
)

private fun JSONObject.optText(name: String, fallback: String = ""): String {
    val value = optString(name, fallback).trim()
    return value.ifBlank { fallback }
}

private fun parseDownloadArray(array: JSONArray?): List<SteamDownload> {
    if (array == null) return emptyList()
    val out = ArrayList<SteamDownload>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val name = item.optText("name").ifBlank { item.optText("app_name") }
        if (name.isBlank()) continue
        out.add(
            SteamDownload(
                appId = item.optString("app_id"),
                name = name,
                percent = item.optDouble("percent", 0.0),
                downloaded = item.optLong("bytes_downloaded", 0L),
                total = item.optLong("bytes_total", 0L),
                speedBps = item.optLong("speed_bps", 0L),
                etaSeconds = item.optLong("eta_seconds", 0L),
                percentStaged = item.optDouble("percent_staged", 0.0),
            )
        )
    }
    return out
}

/** `GET /steam` auswerten; null, wenn die Route fehlt oder die Antwort unlesbar ist. */
fun parseSteam(body: String): SteamStatus? {
    if (body.startsWith("HTTP ")) return null
    return runCatching {
        val json = JSONObject(body)
        val downloads = parseDownloadArray(json.optJSONArray("downloads"))
        SteamStatus(
            detected = json.optBoolean("detected", false),
            millennium = json.optBoolean("millennium", false),
            running = json.optBoolean("running", false),
            downloading = json.optBoolean("downloading", downloads.isNotEmpty()),
            progressPercent = json.optDouble(
                "progress_percent",
                downloads.firstOrNull()?.percent ?: 0.0
            ),
            speedBps = json.optLong("speed_bps", 0L),
            etaSeconds = json.optLong("eta_seconds", downloads.firstOrNull()?.etaSeconds ?: 0L),
            appName = json.optText("app_name").ifBlank { downloads.firstOrNull()?.name.orEmpty() },
            downloads = downloads,
        )
    }.getOrNull()
}

/** `GET /downloads` auswerten: Liste oder `{"downloads": [...]}`. */
fun parseDownloads(body: String): List<SteamDownload> {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return emptyList()
    return runCatching {
        if (text.startsWith("[")) {
            parseDownloadArray(JSONArray(text))
        } else {
            parseDownloadArray(JSONObject(text).optJSONArray("downloads"))
        }
    }.getOrDefault(emptyList())
}

/** Eine Aktion innerhalb einer Routine (`shutdown`, `hibernate`, `power_plan`). */
data class RoutineActionInfo(
    val type: String = "",
    val delay: Int = 0,
    val plan: String = "",
) {
    val label: String get() = when (type) {
        "shutdown" -> "Herunterfahren in ${delay}s"
        "hibernate" -> "Ruhezustand"
        "power_plan" -> "Energieplan ${planLabel(plan)}"
        else -> type
    }
}

/** Wochentage 1 = Mo ... 7 = So (wie im Agenten). */
private val ROUTINE_DAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** Eine Routine des Agenten (Feldnamen aus `routines.rs`). */
data class RoutineInfo(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val triggerType: String = "manual",
    val triggerAt: String = "",
    val triggerDays: List<Int> = emptyList(),
    val actions: List<RoutineActionInfo> = emptyList(),
    val lastRun: String = "",
    val nextRun: String = "",
) {
    /** Kurztext des Ausloesers fuer Liste und Kachel. */
    val triggerText: String
        get() = when {
            triggerType != "time" -> "Nur manuell"
            triggerDays.isEmpty() -> "Täglich $triggerAt"
            else -> "$triggerAt · " + triggerDays
                .mapNotNull { ROUTINE_DAYS.getOrNull(it - 1) }
                .joinToString(", ")
        }

    val actionTexts: List<String> get() = actions.map { it.label }
}

/** `GET /routines` auswerten. */
fun parseRoutines(body: String): List<RoutineInfo> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val array = JSONObject(body).optJSONArray("routines") ?: JSONArray()
        val out = ArrayList<RoutineInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val trigger = item.optJSONObject("trigger")
            val days = ArrayList<Int>()
            val daysJson = trigger?.optJSONArray("days")
            if (daysJson != null) {
                for (day in 0 until daysJson.length()) days.add(daysJson.optInt(day))
            }
            val actions = ArrayList<RoutineActionInfo>()
            val actionsJson = item.optJSONArray("actions")
            if (actionsJson != null) {
                for (a in 0 until actionsJson.length()) {
                    val action = actionsJson.optJSONObject(a) ?: continue
                    actions.add(
                        RoutineActionInfo(
                            type = action.optString("type"),
                            delay = action.optInt("delay", 0),
                            plan = action.optString("plan"),
                        )
                    )
                }
            }
            out.add(
                RoutineInfo(
                    id = id,
                    name = item.optText("name", id),
                    enabled = item.optBoolean("enabled", true),
                    triggerType = trigger?.optString("type", "manual") ?: "manual",
                    triggerAt = trigger?.optString("at").orEmpty(),
                    triggerDays = days,
                    actions = actions,
                    lastRun = item.optString("last_run"),
                    nextRun = item.optString("next_run"),
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}

/** Routine als JSON fuer `POST /routines` (An/Aus umschalten, `id` bleibt gleich). */
private fun routineJson(routine: RoutineInfo, enabled: Boolean): String {
    val rawDays = JSONArray()
    routine.triggerDays.forEach { rawDays.put(it) }
    val rawActions = JSONArray()
    routine.actions.forEach { action ->
        rawActions.put(
            JSONObject()
                .put("type", action.type)
                .put("delay", action.delay)
                .put("plan", action.plan)
        )
    }
    val trigger = JSONObject()
        .put("type", routine.triggerType)
        .put("at", routine.triggerAt)
        .put("days", rawDays)
    return JSONObject()
        .put("id", routine.id)
        .put("name", routine.name)
        .put("enabled", enabled)
        .put("trigger", trigger)
        .put("actions", rawActions)
        .put("last_run", routine.lastRun)
        .toString()
}

/** Ein Ereignis aus `GET /events` (Ringpuffer, neueste zuerst). */
data class AgentEvent(
    val time: String = "",
    val action: String = "",
    val detail: String = "",
    val ok: Boolean = true,
)

/** `GET /events` auswerten. */
fun parseEvents(body: String): List<AgentEvent> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val array = JSONObject(body).optJSONArray("events") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            AgentEvent(
                time = item.optString("time"),
                action = item.optString("action"),
                detail = item.optString("detail"),
                ok = item.optBoolean("ok", true),
            )
        }
    }.getOrDefault(emptyList())
}

/** Prozentwert ohne Nachkommastellen ("43"). */
private fun fmtPercent(value: Double): String =
    String.format(Locale.GERMANY, "%.0f", value.coerceIn(0.0, 100.0))

/** Grobe Groesse in B/KB/MB/GB fuer Download-Zeilen. */
private fun fmtBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.GERMANY, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.GERMANY, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Animiert einen Theme-Wechsel: jede Farbe des Zielschemas blendet einzeln ueber
 * [durationMs] ueber. Dadurch geht Hell/Dunkel weich ineinander ueber, ohne dass
 * der Bildschirminhalt abgeblendet wird oder der UI-Zustand verloren geht.
 * Reine Compose-Loesung, keine zusaetzliche Bibliothek.
 */
@Composable
private fun animatedColorScheme(
    target: ColorScheme,
    durationMs: Int = 480,
): ColorScheme {
    @Composable
    fun fade(color: Color): Color {
        val animated by animateColorAsState(
            targetValue = color,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
            label = "schemeColor"
        )
        return animated
    }
    return target.copy(
        primary = fade(target.primary),
        onPrimary = fade(target.onPrimary),
        primaryContainer = fade(target.primaryContainer),
        onPrimaryContainer = fade(target.onPrimaryContainer),
        inversePrimary = fade(target.inversePrimary),
        secondary = fade(target.secondary),
        onSecondary = fade(target.onSecondary),
        secondaryContainer = fade(target.secondaryContainer),
        onSecondaryContainer = fade(target.onSecondaryContainer),
        tertiary = fade(target.tertiary),
        onTertiary = fade(target.onTertiary),
        tertiaryContainer = fade(target.tertiaryContainer),
        onTertiaryContainer = fade(target.onTertiaryContainer),
        background = fade(target.background),
        onBackground = fade(target.onBackground),
        surface = fade(target.surface),
        onSurface = fade(target.onSurface),
        surfaceVariant = fade(target.surfaceVariant),
        onSurfaceVariant = fade(target.onSurfaceVariant),
        surfaceTint = fade(target.surfaceTint),
        inverseSurface = fade(target.inverseSurface),
        inverseOnSurface = fade(target.inverseOnSurface),
        error = fade(target.error),
        onError = fade(target.onError),
        errorContainer = fade(target.errorContainer),
        onErrorContainer = fade(target.onErrorContainer),
        outline = fade(target.outline),
        outlineVariant = fade(target.outlineVariant),
        scrim = fade(target.scrim),
        surfaceBright = fade(target.surfaceBright),
        surfaceDim = fade(target.surfaceDim),
        surfaceContainer = fade(target.surfaceContainer),
        surfaceContainerHigh = fade(target.surfaceContainerHigh),
        surfaceContainerHighest = fade(target.surfaceContainerHighest),
        surfaceContainerLow = fade(target.surfaceContainerLow),
        surfaceContainerLowest = fade(target.surfaceContainerLowest),
    )
}

private fun statusLabel(status: PcStatus): String = when (status) {
    PcStatus.ONLINE -> "Online"
    PcStatus.ERROR -> "Fehler"
    PcStatus.OFFLINE -> "Offline"
    PcStatus.UNKNOWN -> "Unbekannt"
}

/**
 * Statuspunkt. In der Animation sitzt hinter dem Punkt ein sanft
 * pulsierender Schein, der "lebt" von selbst - typisch für den Online-Zustand.
 */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val halo by rememberInfiniteTransition(label = "statusHalo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusHaloAlpha"
    )
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.26f * halo))
            )
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/**
 * Lässt Inhalte gestaffelt (Kaskade) einfliegen. Index bestimmt die
 * Verzoegerung in Millisekunden.
 */
@Composable
private fun Appearing(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val delay = (index * 70).coerceAtMost(350)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300, delayMillis = delay, easing = FastOutSlowInEasing)) +
            expandVertically(tween(340, delayMillis = delay, easing = FastOutSlowInEasing))
    ) {
        content()
    }
}

@Composable
private fun DelayChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Sofort", 10 to "10 s", 30 to "30 s", 60 to "60 s").forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun AppRoot(
    settings: AppSettings = AppSettings(),
    onSettingsChange: (AppSettings) -> Unit = {},
    confirmShutdown: Boolean = false,
    onConfirmConsumed: () -> Unit = {},
    startWithWake: Boolean = false,
    onWakeConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileStore.load(context)) }
    var activeId by remember { mutableStateOf(ProfileStore.activeId(context)) }

    fun persist(newProfiles: List<Profile>, newActiveId: String?) {
        ProfileStore.save(context, newProfiles, newActiveId)
        profiles = newProfiles
        activeId = newActiveId
    }

    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    // Einrichtung verlassen: bevorzugt zu einem fertigen Profil zurueckwechseln
    // und ein gerade neu angelegtes, leeres Profil dabei verwerfen.
    fun cancelSetup() {
        val fallback = profiles.firstOrNull { it.id != active?.id && it.isComplete } ?: return
        val remaining = if (active != null && !active.isComplete) {
            profiles.filter { it.id != active.id }
        } else {
            profiles
        }
        persist(remaining, fallback.id)
    }

    if (active == null || !active.isComplete) {
        SetupScreen(
            profile = active ?: Profile(id = ProfileStore.newId(), name = "PC"),
            onSave = { saved ->
                val others = profiles.filter { it.id != saved.id }
                persist(others + saved, saved.id)
            },
            onCancel = if (profiles.size > 1) ::cancelSetup else null,
        )
    } else {
        MainApp(
            profiles = profiles,
            activeId = active.id,
            onProfilesChange = ::persist,
            settings = settings,
            onSettingsChange = onSettingsChange,
            startWithShutdownConfirm = confirmShutdown,
            onConfirmConsumed = onConfirmConsumed,
            startWithWake = startWithWake,
            onWakeConsumed = onWakeConsumed,
        )
    }
}

/**
 * Vollbild-QR-Scanner mit CameraX und ML Kit.
 *
 * Die fruehere zxing-CaptureActivity nutzt die Camera1-API, die auf Android 17
 * nicht mehr zuverlaessig funktioniert. CameraX nutzt Camera2; das ML-Kit-Modell
 * ist eingebettet und braucht weder Play Services noch Internet.
 */
@Composable
private fun QrScannerScreen(
    title: String,
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        permissionAsked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (granted) {
            CameraQrPreview(onResult = onResult)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) {
                    Text("Schließen", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (granted) {
                Text(
                    text = "QR-Code in den Rahmen halten",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = if (permissionAsked) {
                        "Kamera-Zugriff wurde nicht erlaubt."
                    } else {
                        "Für den Scanner wird die Kamera benötigt."
                    },
                    fontSize = 15.sp,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Kamera erlauben")
                }
                TextButton(onClick = onClose) {
                    Text("Abbrechen", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CameraQrPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val resultDelivered = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner.close() }
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }

    LaunchedEffect(Unit) {
        val provider = runCatching {
            ProcessCameraProvider.getInstance(context).get()
        }.getOrNull() ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null || resultDelivered.get()) {
                imageProxy.close()
                return@setAnalyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val raw = barcodes.firstOrNull()?.rawValue
                    if (raw != null && resultDelivered.compareAndSet(false, true)) {
                        onResult(raw)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun SetupScreen(profile: Profile, onSave: (Profile) -> Unit, onCancel: (() -> Unit)?) {
    var step by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf(profile.name) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port) }
    var user by remember { mutableStateOf(profile.user) }
    var pass by remember { mutableStateOf(profile.pass) }
    var pass2 by remember { mutableStateOf(profile.pass) }
    var mac by remember { mutableStateOf(profile.mac) }
    var wolEnabled by remember { mutableStateOf(profile.mac.isNotBlank()) }
    var broadcast by remember { mutableStateOf(profile.broadcast) }
    var showPass by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var macMessage by remember { mutableStateOf<String?>(null) }
    var lookingUpMac by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connectionReady = host.isNotBlank() && isValidPort(port)

    if (showScanner) {
        QrScannerScreen(
            title = "QR-Code vom PC scannen",
            onResult = { raw ->
                showScanner = false
                val pairing = parsePcSetupQr(raw)
                if (pairing == null) {
                    scanMessage = "Das ist kein gültiger PC-Steuerung-QR-Code."
                } else {
                    name = pairing.name
                    host = pairing.host
                    port = pairing.port.toString()
                    if (pairing.mac != null) {
                        mac = pairing.mac
                        wolEnabled = true
                    }
                    scanMessage = if (pairing.mac != null) {
                        "${pairing.name} übernommen. Zugangsdaten noch eintragen."
                    } else {
                        "${pairing.name} übernommen. WoL-MAC und Zugangsdaten noch eintragen."
                    }
                    formError = null
                }
            },
            onClose = { showScanner = false },
        )
        return
    }

    fun testConnection() {
        if (!connectionReady) {
            testResult = "Erst IP und Port eintragen."
            return
        }
        scope.launch {
            testing = true
            testResult = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = httpCall("http://${host.trim()}:${port.trim()}", "/status", null)
                    if (body.startsWith("HTTP ")) body else "Erreichbar: der PC antwortet."
                } catch (e: Exception) {
                    "Nicht erreichbar: ${e.message ?: "Fehler"}"
                }
            }
            testResult = result
            testing = false
        }
    }

    fun lookupMac() {
        if (!connectionReady) {
            macMessage = "Erst IP und Port eintragen."
            return
        }
        scope.launch {
            lookingUpMac = true
            macMessage = null
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    httpCall("http://${host.trim()}:${port.trim()}", "/pairing-info", null)
                }.getOrNull()
            }
            lookingUpMac = false
            val found = body?.let { parsePairingMac(it) }
            if (found != null) {
                mac = found
                macMessage = "MAC vom PC übernommen: $found"
            } else {
                macMessage = "Am PC ist Wake-on-LAN noch nicht eingerichtet. MAC manuell eingeben oder im Web-Setup aktivieren."
            }
        }
    }

    fun save() {
        when {
            pass.isNotEmpty() && pass != pass2 -> formError = "Passwörter stimmen nicht überein"
            wolEnabled && (mac.isBlank() || !isValidMac(mac)) -> formError = "MAC-Format: AA:BB:CC:DD:EE:FF"
            else -> {
                formError = null
                onSave(
                    profile.copy(
                        name = name.trim().ifBlank { "PC" },
                        host = host.trim(),
                        port = port.trim(),
                        user = user.trim(),
                        pass = pass,
                        mac = if (wolEnabled) mac.trim() else "",
                        broadcast = broadcast.trim().ifBlank { "255.255.255.255" },
                        defaultDelay = profile.defaultDelay,
                    )
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Einrichten",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Einmal einrichten, danach startet die App direkt.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.outline
            )

            // Schritt 0 (Auswahl "QR oder manuell") ist entfernt: Der Assistent startet
            // direkt bei Schritt 1, links/rechts gilt derselbe Ablauf. QR-Scan ist ein
            // optionaler Abkuerzungs-Button, der die Felder automatisch fuellt.
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(4) { i ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i < step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
                Text(
                    text = when (step) {
                        1 -> "Schritt 1 · Verbindung"
                        2 -> "Schritt 2 · Wake-on-LAN (optional)"
                        3 -> "Schritt 3 · Anmeldung"
                        else -> "Schritt 4 · Fertig"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (step) {
                            1 -> {
                                OutlinedButton(
                                    onClick = { showScanner = true },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("QR-Code vom PC scannen (schnellster Weg)")
                                }
                                scanMessage?.let {
                                    Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    text = "Oder die Felder darunter selbst ausfüllen.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("Name (z. B. Wohnzimmer-PC)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it; testResult = null },
                                    label = { Text("IP des PCs") },
                                    placeholder = { Text("192.168.1.100") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it; testResult = null },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    isError = port.isNotBlank() && !isValidPort(port),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = ::testConnection,
                                    enabled = connectionReady && !testing,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    if (testing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Text("Verbindung testen")
                                    }
                                }
                                testResult?.let {
                                    Text(
                                        text = it,
                                        fontSize = 12.sp,
                                        color = if (it.startsWith("Erreichbar")) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                            2 -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Wake-on-LAN aktivieren",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Optional. Nur zuverlässig mit Ethernet-Kabel.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Switch(
                                        checked = wolEnabled,
                                        onCheckedChange = {
                                            wolEnabled = it
                                            formError = null
                                            macMessage = null
                                        }
                                    )
                                }
                                if (wolEnabled) {
                                    OutlinedTextField(
                                        value = mac,
                                        onValueChange = { mac = it; formError = null; macMessage = null },
                                        label = { Text("MAC-Adresse (für WoL)") },
                                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                        singleLine = true,
                                        isError = !isValidMac(mac),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = ::lookupMac,
                                        enabled = connectionReady && !lookingUpMac,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        if (lookingUpMac) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text("MAC automatisch vom PC holen")
                                    }
                                    macMessage?.let {
                                        Text(
                                            text = it,
                                            fontSize = 12.sp,
                                            color = if (it.startsWith("MAC vom PC")) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            }
                                        )
                                    }
                                    OutlinedTextField(
                                        value = broadcast,
                                        onValueChange = { broadcast = it },
                                        label = { Text("Broadcast-Adresse (WoL)") },
                                        placeholder = { Text("255.255.255.255") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            3 -> {
                                OutlinedTextField(
                                    value = user,
                                    onValueChange = { user = it },
                                    label = { Text("Benutzername") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pass,
                                    onValueChange = { pass = it; formError = null },
                                    label = { Text("Passwort") },
                                    singleLine = true,
                                    visualTransformation = if (showPass) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    trailingIcon = {
                                        TextButton(onClick = { showPass = !showPass }) {
                                            Text(if (showPass) "Verbergen" else "Anzeigen", fontSize = 12.sp)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pass2,
                                    onValueChange = { pass2 = it; formError = null },
                                    label = { Text("Passwort wiederholen") },
                                    singleLine = true,
                                    isError = pass2.isNotEmpty() && pass != pass2,
                                    visualTransformation = if (showPass) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Zugangsdaten einmalig in der Web-Oberfläche am PC anlegen: http://localhost:8000/",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            else -> {
                                Text(
                                    text = "Alles bereit. Gespeichert wird:",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Name: ${name.trim().ifBlank { "PC" }}\n" +
                                        "Verbindung: ${host.trim()}:${port.trim()}\n" +
                                        "WoL: " + (if (wolEnabled) mac.trim() else "aus") + "\n" +
                                        "Anmeldung: ${user.trim().ifBlank { "ohne (nur lokal)" }}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                        formError?.let {
                            Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (step > 1) {
                        OutlinedButton(
                            onClick = { step--; formError = null },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Zurück")
                        }
                    }
                    Button(
                        onClick = {
                            when {
                                step == 1 && !connectionReady -> {
                                    formError = "Bitte gültige IP und Port angeben"
                                }
                                step == 2 && wolEnabled && (mac.isBlank() || !isValidMac(mac)) -> {
                                    formError = "MAC-Format: AA:BB:CC:DD:EE:FF"
                                }
                                step == 3 && pass.isNotEmpty() && pass != pass2 -> {
                                    formError = "Passwörter stimmen nicht überein"
                                }
                                step < 4 -> { formError = null; step++ }
                                else -> save()
                            }
                        },
                        enabled = step != 1 || connectionReady,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (step == 4) "Speichern" else "Weiter", fontSize = 16.sp)
                    }
                }
                if (onCancel != null) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.setup_cancel))
                    }
                }
        }
    }
}

@Composable
fun MainApp(
    profiles: List<Profile>,
    activeId: String,
    onProfilesChange: (List<Profile>, String?) -> Unit,
    settings: AppSettings = AppSettings(),
    onSettingsChange: (AppSettings) -> Unit = {},
    startWithShutdownConfirm: Boolean = false,
    onConfirmConsumed: () -> Unit = {},
    startWithWake: Boolean = false,
    onWakeConsumed: () -> Unit = {},
) {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    // Eigener, kleiner Navigations-State (keine Bibliothek): die Startseite ist
    // das Kachel-Raster, jede Kachel oeffnet genau eine Aktionsseite.
    var route by remember { mutableStateOf<Route>(Route.Home) }
    var state by remember { mutableStateOf(AgentState()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showShutdownConfirm by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showRealModeConfirm by remember { mutableStateOf(false) }
    var timerHours by remember { mutableStateOf("") }
    var timerMinutes by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var lastUpdatedAt by remember { mutableLongStateOf(0L) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var wakePendingUntil by remember { mutableLongStateOf(0L) }
    var pendingLeft by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    // Marktplatz-Zustand: installierte Module sind persistent, der Katalog
    // startet lokal und wird beim ersten Start vom GitHub-Repo aufgefrischt.
    var installedModules by remember { mutableStateOf(ModuleStore.load(context)) }
    var catalogModules by remember { mutableStateOf(defaultCatalog()) }
    var catalogSource by remember { mutableStateOf("Lokal (Fallback)") }
    var catalogRefresh by remember { mutableStateOf(0) }

    // Plugins des PC-Agenten: Liste und Katalog kommen vom laufenden Agenten,
    // nicht aus der App selbst. Nachgeladen, sobald die Startseite oder die
    // Plugins-Seite sichtbar wird.
    var plugins by remember { mutableStateOf<List<PluginInfo>>(emptyList()) }
    var pluginCatalog by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var pluginRefresh by remember { mutableIntStateOf(0) }

    // Kachel-Kurzstatus: Steam, Routinen und Ereignisse. Alles tolerant, weil
    // /steam und /downloads erst mit einem neuen Agenten kommen (404 -> null).
    var steam by remember { mutableStateOf<SteamStatus?>(null) }
    var steamLoading by remember { mutableStateOf(true) }
    var routines by remember { mutableStateOf<List<RoutineInfo>>(emptyList()) }
    var routinesLoading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<AgentEvent>>(emptyList()) }

    fun installModule(module: CatalogModule) {
        val next = installedModules + module.id
        ModuleStore.save(context, next)
        installedModules = next
        message = "${module.name} installiert"
    }

    fun uninstallModule(module: CatalogModule) {
        val next = installedModules - module.id
        ModuleStore.save(context, next)
        installedModules = next
        message = "${module.name} deinstalliert"
    }

    // ---------------- Plugins des Agenten ----------------

    fun pluginAction(path: String, method: String, success: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, path, active.authHeader(), method)
                    } catch (e: Exception) {
                        Log.e(TAG, "Plugin-Aktion fehlgeschlagen: $path", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                message = if (result.startsWith("HTTP ") || result.startsWith("Nicht")) {
                    result
                } else {
                    success
                }
                pluginRefresh++
            } finally {
                busy = false
            }
        }
    }

    fun startPlugin(plugin: PluginInfo) =
        pluginAction("/plugins/${plugin.id}/start", "POST", "${plugin.name} gestartet")

    fun stopPlugin(plugin: PluginInfo) =
        pluginAction("/plugins/${plugin.id}/stop", "POST", "${plugin.name} beendet")

    fun removePlugin(plugin: PluginInfo) =
        pluginAction("/plugins/${plugin.id}", "DELETE", "${plugin.name} entfernt")

    fun installPlugin(entry: PluginCatalogEntry) =
        pluginAction("/plugins/${entry.id}/install", "POST", "${entry.name} installiert")

    LaunchedEffect(catalogRefresh) {
        val remote = withContext(Dispatchers.IO) {
            try {
                fetchRemoteCatalog()
            } catch (e: Exception) {
                Log.e(TAG, "Katalog nicht erreichbar", e)
                null
            }
        }
        if (remote.isNullOrEmpty()) {
            if (catalogModules.isEmpty()) catalogModules = defaultCatalog()
            catalogSource = if (remote == null) "Lokal (offline)" else "Lokal"
        } else {
            catalogModules = mergeCatalog(defaultCatalog(), remote)
            catalogSource = "GitHub (remote)"
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    // Plugins immer frisch laden, wenn die Startseite oder die Plugins-Seite
    // sichtbar ist (Kachel-Kurzstatus bzw. volle Liste).
    suspend fun loadPlugins() {
        val snapshot = withContext(Dispatchers.IO) {
            try {
                PluginSnapshot(
                    plugins = parsePlugins(httpCall(active.base, "/plugins", active.authHeader())),
                    catalog = parsePluginCatalog(
                        httpCall(active.base, "/plugin-catalog", active.authHeader())
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Plugins konnten nicht geladen werden", e)
                null
            }
        }
        plugins = snapshot?.plugins.orEmpty()
        pluginCatalog = snapshot?.catalog.orEmpty()
    }

    /**
     * Steam-Status: zuerst `GET /steam`, sonst die reine Download-Liste von
     * `GET /downloads`. Fehlt beides (alter Agent, 404), bleibt [steam] null -
     * Kachel und Seite zeigen dann "nicht verfuegbar".
     */
    suspend fun loadSteam() {
        steamLoading = true
        val result = withContext(Dispatchers.IO) {
            try {
                val primary = parseSteam(httpCall(active.base, "/steam", active.authHeader()))
                if (primary != null) {
                    primary
                } else {
                    val raw = httpCall(active.base, "/downloads", active.authHeader())
                    if (raw.startsWith("HTTP ")) {
                        null
                    } else {
                        val list = parseDownloads(raw)
                        SteamStatus(
                            detected = true,
                            millennium = false,
                            running = list.isNotEmpty(),
                            downloading = list.isNotEmpty(),
                            progressPercent = list.firstOrNull()?.percent ?: 0.0,
                            speedBps = list.firstOrNull()?.speedBps ?: 0L,
                            etaSeconds = list.firstOrNull()?.etaSeconds ?: 0L,
                            appName = list.firstOrNull()?.name.orEmpty(),
                            downloads = list,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Steam-Status nicht verfuegbar", e)
                null
            }
        }
        steam = result
        steamLoading = false
    }

    /** Routinen des Agenten (`GET /routines`). */
    suspend fun loadRoutines() {
        val list = withContext(Dispatchers.IO) {
            try {
                parseRoutines(httpCall(active.base, "/routines", active.authHeader()))
            } catch (e: Exception) {
                Log.e(TAG, "Routinen nicht erreichbar", e)
                emptyList()
            }
        }
        routines = list
        routinesLoading = false
    }

    /** Ereignisse des Agenten (`GET /events`, neueste zuerst). */
    suspend fun loadEvents() {
        val list = withContext(Dispatchers.IO) {
            try {
                parseEvents(httpCall(active.base, "/events", active.authHeader()))
            } catch (e: Exception) {
                Log.e(TAG, "Ereignisse nicht erreichbar", e)
                emptyList()
            }
        }
        events = list
    }

    /** Log-Text des Agenten laden (`GET /log`, blockierend -> immer im IO-Kontext). */
    suspend fun fetchLogText(): String = withContext(Dispatchers.IO) {
        try {
            httpCall(active.base, "/log?lines=200", active.authHeader())
        } catch (e: Exception) {
            "Nicht erreichbar: ${e.message ?: "Fehler"}"
        }
    }

    /** Routinen-Aktion (Jetzt/An-Aus/Loeschen) inklusive Neuladen der Liste. */
    fun routineAction(path: String, method: String, success: String, body: String? = null) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, path, active.authHeader(), method, body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Routine-Aktion fehlgeschlagen: $path", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                message = if (result.startsWith("HTTP ") || result.startsWith("Nicht")) {
                    result
                } else {
                    success
                }
                loadRoutines()
                refresh++
            } finally {
                busy = false
            }
        }
    }

    // Nachladen abhaengig von der sichtbaren Seite. Die Startseite braucht die
    // Kurzstatus, die Aktionsseiten ihre vollen Listen.
    LaunchedEffect(active, route, pluginRefresh, refresh) {
        when (route) {
            Route.Home -> {
                loadPlugins()
                loadRoutines()
                loadSteam()
            }

            Route.Plugins -> loadPlugins()
            Route.Steam -> loadSteam()
            Route.Routines -> loadRoutines()
            Route.History -> {
                loadEvents()
                logText = fetchLogText().trim().ifBlank { "Noch keine Einträge." }
            }

            else -> Unit
        }
    }

    // Kacheln der Startseite regelmaessig auffrischen (Plugins, Steam, Routinen),
    // aber nur solange die Seite sichtbar ist - kein Funkverkehr im Hintergrund.
    LaunchedEffect(active, route, refresh, pluginRefresh) {
        if (route != Route.Home) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(15_000)
                loadPlugins()
                loadRoutines()
                loadSteam()
            }
        }
    }

    LaunchedEffect(startWithShutdownConfirm) {
        if (startWithShutdownConfirm) {
            route = Route.Control
            showShutdownConfirm = true
            onConfirmConsumed()
        }
    }

    fun runAction(path: String, success: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                message = "Sende ..."
                val result = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, path, active.authHeader(), "POST")
                    } catch (e: Exception) {
                        Log.e(TAG, "Aktion fehlgeschlagen: $path", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                val shown = if (result.startsWith("HTTP ") || result.startsWith("Nicht")) result else success
                message = shown
                refresh++
            } finally {
                busy = false
            }
        }
    }

    fun doWake() {
        if (busy) return
        if (active.mac.isBlank()) {
            message = "MAC-Adresse fehlt - bitte in den Einstellungen eintragen"
            return
        }
        scope.launch {
            busy = true
            try {
                message = "Sende Wake ..."
                val ok = withContext(Dispatchers.IO) {
                    sendMagicPacket(context, active.mac, active.broadcast)
                }
                Log.d(TAG, "WoL an ${active.mac} via ${active.broadcast} -> $ok")
                message = if (ok) "Wake gesendet (direkt)" else "Wake fehlgeschlagen"
                if (ok) {
                    // 60 s lang alle 2 s pruefen, ob der PC hochkommt.
                    wakePendingUntil = System.currentTimeMillis() + 60_000
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Log des Agenten laden. Mit [showDialog] = true erscheint wie bisher der
     * Dialog (Steuerungs-Seite), sonst nur der Text auf der Verlauf-Seite.
     */
    fun loadLog(showDialog: Boolean = true) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                logText = fetchLogText().trim().ifBlank { "Noch keine Einträge." }
                if (showDialog) showLog = true
            } finally {
                busy = false
            }
        }
    }

    /** Zwischenablage: Log kopieren (wie im Verlauf-Dialog). */
    fun copyLog() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("PC-Agent-Verlauf", logText))
        message = "Verlauf kopiert"
    }

    fun testConnection() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val body = httpCall(active.base, "/status", active.authHeader())
                        if (body.startsWith("HTTP ")) body else "Verbindung OK"
                    } catch (e: Exception) {
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                message = result
            } finally {
                busy = false
            }
        }
    }

    fun switchProfile(id: String) {
        if (id == active.id) return
        onProfilesChange(profiles, id)
    }

    fun addProfile() {
        val p = Profile(id = ProfileStore.newId(), name = "Neuer PC")
        onProfilesChange(profiles + p, p.id)
    }

    fun deleteProfile(id: String) {
        val newList = profiles.filter { it.id != id }
        if (newList.isEmpty()) return
        val newActive = if (active.id == id) newList.first().id else active.id
        onProfilesChange(newList, newActive)
    }

    fun saveProfile(updated: Profile) {
        val newList = profiles.map { if (it.id == updated.id) updated else it }
        onProfilesChange(newList, updated.id)
    }

    fun openWebInterface() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(active.base)))
        } catch (e: Exception) {
            Log.e(TAG, "Browser konnte nicht geoeffnet werden", e)
            message = "Kein Browser gefunden"
        }
    }

    // Zurueck-Taste: von jeder Aktionsseite zurueck zur Kachel-Startseite statt
    // die App zu schliessen (typisches Android-Verhalten).
    BackHandler(enabled = route != Route.Home) { route = Route.Home }

    // Sekundentakt fuer Countdown und "zuletzt geprueft"-Anzeige. Nur im
    // Vordergrund aktiv, damit im Hintergrund keine Recomposition laeuft.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1000)
                nowTick = System.currentTimeMillis()
            }
        }
    }

    // Countdown lokal weiterzaehlen; die Server-Antwort synchronisiert ihn
    // alle paar Sekunden. Bei 0 einmal frisch nachfragen.
    LaunchedEffect(state.pending, state.status) {
        pendingLeft = state.pending
        while (pendingLeft > 0) {
            delay(1000)
            pendingLeft -= 1
        }
        if (state.pending > 0) refresh++
    }

    val lastUpdatedText = when {
        lastUpdatedAt == 0L -> "gerade eben"
        else -> {
            val age = ((nowTick - lastUpdatedAt) / 1000).coerceAtLeast(0)
            if (age < 2) "gerade eben" else "vor $age s"
        }
    }
    val wakePending = wakePendingUntil > nowTick

    LaunchedEffect(startWithWake) {
        if (startWithWake) {
            route = Route.Control
            doWake()
            onWakeConsumed()
        }
    }

    LaunchedEffect(active, refresh, settings.refreshSeconds) {
        // Status nur abfragen, solange die App sichtbar (STARTED) ist: Im
        // Hintergrund pausiert die Schleife, bei Rueckkehr startet sie frisch -
        // die Anzeige ist sofort aktuell und Akku/Datenvolumen werden gespart.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val next = withContext(Dispatchers.IO) {
                    try {
                        parseStatus(httpCall(active.base, "/status", active.authHeader()))
                    } catch (e: Exception) {
                        Log.e(TAG, "Status-Abfrage fehlgeschlagen", e)
                        AgentState("Nicht erreichbar: ${e.message ?: "Fehler"}", PcStatus.OFFLINE, "?", 0)
                    }
                }
                state = next
                lastUpdatedAt = System.currentTimeMillis()
                refreshing = false
                if (next.status == PcStatus.ONLINE) wakePendingUntil = 0L
                // Nach dem Wecken schneller nachsehen, ob der PC hochkommt.
                val fastPoll = wakePendingUntil > System.currentTimeMillis()
                delay(if (fastPoll) 2000L else settings.refreshSeconds * 1000L)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Die Aktionsseiten bringen ihr eigenes Scaffold mit; die Insets sind hier
        // bereits verbraucht, damit Status-/Navigationsleiste nicht doppelt polstern.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            AnimatedContent(
                targetState = route,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 40 }) togetherWith
                        fadeOut(tween(150))
                },
                label = "routeContent"
            ) { screen ->
                when (screen) {
                    Route.Home -> HomeScreen(
                        active = active,
                        state = state,
                        busy = busy,
                        pendingLeft = pendingLeft,
                        lastUpdated = lastUpdatedText,
                        refreshSeconds = settings.refreshSeconds,
                        refreshing = refreshing,
                        plugins = plugins,
                        installedModules = installedModules,
                        moduleCount = catalogModules.size,
                        routines = routines,
                        events = events,
                        steam = steam,
                        steamLoading = steamLoading,
                        onRefresh = {
                            refreshing = true
                            refresh++
                        },
                        onOpen = { route = it },
                    )

                    Route.Control -> ActionPage(
                        title = stringResource(R.string.page_control_title),
                        subtitle = active.name,
                        onBack = { route = Route.Home },
                    ) {
                        ControlTab(
                            profiles = profiles,
                            active = active,
                            state = state,
                            busy = busy,
                            installed = installedModules,
                            pendingLeft = pendingLeft,
                            wakePending = wakePending,
                            lastUpdated = lastUpdatedText,
                            refreshSeconds = settings.refreshSeconds,
                            refreshing = refreshing,
                            onRefresh = {
                                refreshing = true
                                refresh++
                            },
                            onSwitchProfile = ::switchProfile,
                            onShutdown = { showShutdownConfirm = true },
                            onTimer = {
                                timerHours = ""
                                timerMinutes = ""
                                showTimer = true
                            },
                            onWake = ::doWake,
                            onHibernate = { runAction("/hibernate", "Ruhezustand ausgelöst") },
                            onAbort = { runAction("/abort", "Herunterfahren abgebrochen") },
                            onShowLog = ::loadLog,
                            onOpenMarket = { route = Route.Market },
                            onOpenSettings = { route = Route.Settings },
                        )
                    }

                    Route.Steam -> ActionPage(
                        title = stringResource(R.string.page_steam_title),
                        subtitle = stringResource(R.string.page_steam_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        SteamPage(
                            steam = steam,
                            loading = steamLoading,
                            busy = busy,
                            onRefresh = { refresh++ },
                        )
                    }

                    Route.Plugins -> ActionPage(
                        title = stringResource(R.string.page_plugins_title),
                        subtitle = stringResource(R.string.page_plugins_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        PluginsPage(
                            plugins = plugins,
                            pluginCatalog = pluginCatalog,
                            busy = busy,
                            onRefresh = { pluginRefresh++ },
                            onStart = ::startPlugin,
                            onStop = ::stopPlugin,
                            onRemove = ::removePlugin,
                            onInstall = ::installPlugin,
                        )
                    }

                    Route.Market -> ActionPage(
                        title = stringResource(R.string.page_market_title),
                        subtitle = stringResource(R.string.page_market_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        MarketPage(
                            modules = catalogModules,
                            installed = installedModules,
                            source = catalogSource,
                            busy = busy,
                            onRefresh = { catalogRefresh++ },
                            onInstall = ::installModule,
                            onUninstall = ::uninstallModule,
                            pluginCount = plugins.size,
                            onOpenPlugins = { route = Route.Plugins },
                        )
                    }
                    Route.Energy -> ActionPage(
                        title = stringResource(R.string.page_energy_title),
                        subtitle = stringResource(R.string.page_energy_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        PlanTab(
                            activePlan = state.plan,
                            busy = busy,
                            onSelect = { key, label ->
                                state = state.copy(plan = key)
                                runAction("/power-plan?name=$key", "Energieplan: $label")
                            },
                        )
                    }

                    Route.Routines -> ActionPage(
                        title = stringResource(R.string.page_routines_title),
                        subtitle = stringResource(R.string.page_routines_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        RoutinesPage(
                            routines = routines,
                            loading = routinesLoading,
                            busy = busy,
                            onRefresh = { refresh++ },
                            onRun = { routine ->
                                routineAction(
                                    "/routines/${routine.id}/run",
                                    "POST",
                                    "${routine.name} ausgeführt",
                                )
                            },
                            onToggle = { routine ->
                                routineAction(
                                    "/routines",
                                    "POST",
                                    if (routine.enabled) {
                                        "${routine.name} ausgeschaltet"
                                    } else {
                                        "${routine.name} eingeschaltet"
                                    },
                                    routineJson(routine, enabled = !routine.enabled),
                                )
                            },
                            onDelete = { routine ->
                                routineAction(
                                    "/routines/${routine.id}",
                                    "DELETE",
                                    "${routine.name} gelöscht",
                                )
                            },
                        )
                    }

                    Route.History -> ActionPage(
                        title = stringResource(R.string.page_history_title),
                        subtitle = stringResource(R.string.page_history_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        HistoryPage(
                            events = events,
                            logText = logText,
                            busy = busy,
                            onRefresh = {
                                // Laedt Ereignisse und Log neu (siehe LaunchedEffect oben).
                                refresh++
                            },
                            onCopy = ::copyLog,
                        )
                    }

                    Route.Settings -> ActionPage(
                        title = stringResource(R.string.settings_title),
                        subtitle = stringResource(R.string.page_settings_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        SettingsTab(
                            profiles = profiles,
                            active = active,
                            simulate = state.simulate,
                            busy = busy,
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onOpenWeb = ::openWebInterface,
                            versionName = versionName,
                            onSwitch = ::switchProfile,
                            onAdd = ::addProfile,
                            onDelete = ::deleteProfile,
                            onSave = ::saveProfile,
                            onTestConnection = ::testConnection,
                            onModeChange = { simulation ->
                                if (simulation) {
                                    state = state.copy(simulate = true)
                                    runAction("/mode?simulate=true", "Simulationsmodus aktiviert")
                                } else {
                                    showRealModeConfirm = true
                                }
                            },
                        )
                    }
                }
            }

            // Duenner Fortschrittsbalken am oberen Rand: liegt ueber dem Inhalt,
            // verschiebt also nichts und stiehlt keinen Platz (statt dem alten
            // breiten Balken, der die ganze Seite nach unten geschoben hat).
            AnimatedVisibility(
                visible = busy,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(260)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = Color.Transparent
                )
            }
        }
    }

    if (showShutdownConfirm) {
        val delay = active.defaultDelay
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = { Text("PC herunterfahren?") },
            text = {
                Text(
                    (if (delay == 0) {
                        "Der PC ${active.name} fährt sofort herunter."
                    } else {
                        "Der PC ${active.name} fährt in $delay Sekunden herunter."
                    }) + "\n\nAbbrechen ist danach auf der Aktionsseite Steuerung möglich."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showShutdownConfirm = false
                        runAction(
                            "/shutdown?delay=$delay",
                            if (delay == 0) {
                                "Herunterfahren ausgelöst"
                            } else {
                                "Herunterfahren in $delay s geplant"
                            }
                        )
                    }
                ) { Text("Ja") }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownConfirm = false }) { Text("Nein") }
            }
        )
    }

    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Timer: Herunterfahren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 8).forEach { hours ->
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                showTimer = false
                                runAction("/shutdown?delay=${hours * 3600}", "Herunterfahren in $hours h geplant")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("In $hours Stunden") }
                    }
                    Text(
                        text = "Eigene Zeit:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timerHours,
                            onValueChange = { timerHours = it.filter(Char::isDigit) },
                            label = { Text("Stunden") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = timerMinutes,
                            onValueChange = { timerMinutes = it.filter(Char::isDigit) },
                            label = { Text("Minuten") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val total = (timerHours.toIntOrNull() ?: 0) * 3600 +
                        (timerMinutes.toIntOrNull() ?: 0) * 60
                    if (total <= 0) {
                        message = "Bitte Stunden oder Minuten angeben"
                    } else if (total > 86400) {
                        message = "Maximal 24 Stunden erlaubt"
                    } else {
                        showTimer = false
                        runAction("/shutdown?delay=$total", "Herunterfahren in ${total / 60} min geplant")
                    }
                }) { Text("Starten") }
            },
            dismissButton = {
                TextButton(onClick = { showTimer = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showRealModeConfirm) {
        AlertDialog(
            onDismissRequest = { showRealModeConfirm = false },
            title = { Text("Echtzeitmodus aktivieren?") },
            text = {
                Text(
                    "Achtung: Befehle werden wirklich ausgeführt - " +
                        "Herunterfahren, Ruhezustand und Energieplan wirken sofort."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    showRealModeConfirm = false
                    state = state.copy(simulate = false)
                    runAction("/mode?simulate=false", "Echtzeitmodus aktiviert")
                }) { Text("Ja, aktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showRealModeConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Verlauf") },
            text = {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = logText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("Schließen") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("PC-Agent-Verlauf", logText)
                            )
                            message = "Verlauf kopiert"
                            showLog = false
                        }
                    ) { Text("Kopieren") }
                    TextButton(enabled = !busy, onClick = { loadLog() }) { Text("Aktualisieren") }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlTab(
    profiles: List<Profile>,
    active: Profile,
    state: AgentState,
    busy: Boolean,
    installed: Set<String>,
    pendingLeft: Int,
    wakePending: Boolean,
    lastUpdated: String,
    refreshSeconds: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onShutdown: () -> Unit,
    onTimer: () -> Unit,
    onWake: () -> Unit,
    onHibernate: () -> Unit,
    onAbort: () -> Unit,
    onShowLog: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PC Steuerung",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Zuletzt geprüft: $lastUpdated · alle ${refreshSeconds} s",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Status aktualisieren",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (profiles.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { p ->
                        FilterChip(
                            selected = p.id == active.id,
                            onClick = { onSwitchProfile(p.id) },
                            label = { Text(p.name) }
                        )
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.status_header, active.name),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${active.host}:${active.port}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        modifier = Modifier.semantics {
                            contentDescription = "${statusLabel(state.status)}: ${state.text}"
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(
                            color = statusColor(state.status),
                            pulsing = state.status == PcStatus.ONLINE
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedContent(
                            targetState = state.text,
                            transitionSpec = {
                                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 4 }) togetherWith
                                    fadeOut(tween(180))
                            },
                            label = "statusText"
                        ) { text ->
                            Text(
                                text = text,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.simulate?.let { sim ->
                        val modeColor by animateColorAsState(
                            targetValue = if (sim) Color(0xFFF9A825) else Color(0xFFE53935),
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                            label = "modeColor"
                        )
                        Text(
                            text = if (sim) {
                                stringResource(R.string.mode_simulation)
                            } else {
                                stringResource(R.string.mode_real)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = modeColor
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.simulate == true,
                enter = fadeIn(tween(240)) + expandVertically(tween(320)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(320))
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.banner_simulation),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (state.status == PcStatus.ERROR) {
                Text(
                    text = stringResource(R.string.error_credentials),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }

            AnimatedContent(
                targetState = state.status,
                transitionSpec = {
                    (fadeIn(tween(300)) + expandVertically(tween(360))) togetherWith
                        (fadeOut(tween(200)) + shrinkVertically(tween(360)))
                },
                label = "actions"
            ) { status ->
                when (status) {
                    PcStatus.ONLINE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onShutdown()
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("PC herunterfahren", fontSize = 18.sp)
                            }
                            // Timer nur als installiertes Marktplatz-Modul.
                            if (MODULE_TIMER in installed) {
                                OutlinedButton(
                                    onClick = onTimer,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Timer...", fontSize = 15.sp)
                                }
                            }

                            // Countdown-Karte bleibt immer da: Eine laufende
                            // Abschaltung muss unabhaengig vom Modul abbrichbar sein.
                            AnimatedVisibility(
                                visible = pendingLeft > 0,
                                enter = fadeIn(tween(240)) + expandVertically(tween(320)),
                                exit = fadeOut(tween(180)) + shrinkVertically(tween(320))
                            ) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Geplantes Herunterfahren in ${fmtDuration(pendingLeft)}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        TextButton(enabled = !busy, onClick = onAbort) { Text("Abbrechen") }
                                    }
                                }
                            }

                            if (MODULE_LOG in installed) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onHibernate()
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("Ruhezustand", fontSize = 14.sp) }
                                    OutlinedButton(
                                        onClick = onShowLog,
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("Verlauf", fontSize = 14.sp) }
                                }
                            } else {
                                // Ohne Verlauf-Modul hat Ruhezustand die volle Breite.
                                OutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onHibernate()
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Ruhezustand", fontSize = 14.sp) }
                            }

                            // Entdecker-Hinweis: Basis ist da, Zusaetze gibt es im Markt.
                            if (MODULE_TIMER !in installed && MODULE_LOG !in installed) {
                                TextButton(
                                    onClick = onOpenMarket,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Mehr Funktionen im Markt+ …", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    PcStatus.OFFLINE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onWake()
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                if (wakePending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("PC startet ...", fontSize = 18.sp)
                                } else {
                                    Text("PC einschalten", fontSize = 18.sp)
                                }
                            }
                            Text(
                                text = if (wakePending) {
                                    "Wake-on-LAN gesendet. Der PC braucht oft 10–30 Sekunden."
                                } else {
                                    "Wake-on-LAN: Ethernet empfohlen; WoL im BIOS/UEFI und im Netzwerkadapter aktivieren."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (wakePending) {
                                TextButton(
                                    onClick = onRefresh,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Jetzt prüfen") }
                            }
                        }
                    }

                    PcStatus.ERROR, PcStatus.UNKNOWN -> Unit
                }
            }

        }
    }
}

/**
 * Markt+: An/Aus, Status, Ruhezustand und Energieplan sind Basis und immer
 * gratis dabei. Zusaetze (Module) werden hier installiert; der Katalog stammt
 * aus dem GitHub-Repo, offline greift ein lokaler Fallback. Die Plugins des
 * Agenten haben eine eigene Aktionsseite (Kachel "Plugins").
 */
@Composable
private fun MarketPage(
    modules: List<CatalogModule>,
    installed: Set<String>,
    source: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onInstall: (CatalogModule) -> Unit,
    onUninstall: (CatalogModule) -> Unit,
    pluginCount: Int,
    onOpenPlugins: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Erweiterungen für deine PC-Steuerung",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Katalog aktualisieren")
            }
        }

        // Ehrlich statt Fake-Markt: Die Module stecken bereits in der App.
        // Der GitHub-Katalog bestimmt nur, welche Erweiterungen hier angeboten werden.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Kein Download nötig: Diese Erweiterungen sind bereits in der App " +
                    "enthalten. Der Katalog auf GitHub legt nur fest, was hier angeboten wird.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }

        // Basis-Funktionen: immer enthalten und nicht abhaengig vom Katalog.
        Text(
            text = "IMMER DABEI",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("An/Aus", "Status", "Ruhezustand", "Energieplan").forEach { label ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Text(
            text = "ERWEITERUNGEN",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = "Katalog: $source · ${modules.size} Module",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        if (modules.isEmpty()) {
            Text(
                text = "Katalog ist leer – bitte oben aktualisieren oder später erneut versuchen.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        modules.forEachIndexed { index, module ->
            ModuleCard(
                module = module,
                isInstalled = module.id in installed,
                index = index,
                busy = busy,
                onInstall = { onInstall(module) },
                onUninstall = { onUninstall(module) }
            )
        }

        Text(
            text = "Neue Module erscheinen automatisch im Katalog (per GitHub).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        // ---------------- Plugins des PC-Agenten ----------------
        // Die Plugin-Liste selbst liegt auf der eigenen Aktionsseite "Plugins"
        // (Kachel im Start-Raster), damit der Markt+ uebersichtlich bleibt.
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = stringResource(R.string.plugins_section_title),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedButton(
            onClick = onOpenPlugins,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Build, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.market_open_plugins, pluginCount))
        }
    }
}

/**
 * Aktionsseite Plugins: lokale Plugins des Agenten (starten, stoppen,
 * entfernen) plus Katalogeintraege zum Nachinstallieren.
 */
@Composable
private fun PluginsPage(
    plugins: List<PluginInfo>,
    pluginCatalog: List<PluginCatalogEntry>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onStart: (PluginInfo) -> Unit,
    onStop: (PluginInfo) -> Unit,
    onRemove: (PluginInfo) -> Unit,
    onInstall: (PluginCatalogEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plugins_section_title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = stringResource(R.string.plugins_section_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.plugins_refresh)
                )
            }
        }

        if (plugins.isEmpty()) {
            Text(
                text = stringResource(R.string.plugins_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        plugins.forEachIndexed { index, plugin ->
            PluginCard(
                plugin = plugin,
                index = index,
                busy = busy,
                onStart = { onStart(plugin) },
                onStop = { onStop(plugin) },
                onRemove = { onRemove(plugin) },
            )
        }

        if (pluginCatalog.isNotEmpty()) {
            Text(
                text = stringResource(R.string.plugins_catalog_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline
            )
            pluginCatalog.forEach { entry ->
                PluginCatalogRow(
                    entry = entry,
                    busy = busy,
                    onInstall = { onInstall(entry) },
                )
            }
        }
    }
}

/** Karte fuer ein Plugin des Agenten: Zustand, letzte Zeilen, Aktionen. */
@Composable
private fun PluginCard(
    plugin: PluginInfo,
    index: Int,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    Appearing(index = index) {
        val containerColor by animateColorAsState(
            targetValue = if (plugin.running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "pluginCardColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = if (plugin.kind == "javascript") "JS" else "EXE",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = pluginStateLabel(plugin),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (plugin.tail.isNotEmpty()) {
                    Text(
                        text = plugin.tail.takeLast(3).joinToString("\n"),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (plugin.running) {
                        TextButton(
                            enabled = !busy,
                            onClick = onStop
                        ) { Text(stringResource(R.string.plugins_stop)) }
                    } else {
                        TextButton(
                            enabled = !busy && plugin.installed,
                            onClick = onStart
                        ) { Text(stringResource(R.string.plugins_start)) }
                    }
                    if (plugin.installed && !plugin.builtin) {
                        TextButton(
                            enabled = !busy,
                            onClick = onRemove
                        ) { Text(stringResource(R.string.plugins_remove)) }
                    }
                }
            }
        }
    }
}

/** Zeile im Plugin-Katalog: Name, Beschreibung, Installieren-Knopf. */
@Composable
private fun PluginCatalogRow(
    entry: PluginCatalogEntry,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = entry.description.ifBlank { "Plugin fuer den PC-Agenten." },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (entry.installed) {
            Text(
                text = stringResource(R.string.plugins_installed),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            TextButton(
                enabled = !busy,
                onClick = onInstall
            ) { Text(stringResource(R.string.plugins_install)) }
        }
    }
}

/**
 * Karte fuer ein Markt-Modul: erscheint gestaffelt (Kascade) und animiert
 * die Auswahl-Farbe wie die Profil-Karten. Module, deren Feature-Code die
 * App noch nicht kennt (SUPPORTED_MODULES), zeigen einen Update-Hinweis
 * statt Buttons.
 */
@Composable
private fun ModuleCard(
    module: CatalogModule,
    isInstalled: Boolean,
    index: Int,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Appearing(index = index) {
        val containerColor by animateColorAsState(
            targetValue = if (isInstalled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "moduleCardColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = module.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (module.version > 1 || isInstalled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Text(
                                    text = if (isInstalled) "installiert" else "v${module.version}",
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = module.description.ifBlank { "Zusaetzliches PC-Modul." },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (module.id !in SUPPORTED_MODULES) {
                        Text(
                            text = "Kommt mit dem naechsten App-Update",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                when {
                    module.id !in SUPPORTED_MODULES -> Unit // Hinweis genuegt
                    isInstalled -> TextButton(enabled = !busy, onClick = onUninstall) {
                        Text("Deinstallieren")
                    }
                    else -> TextButton(enabled = !busy, onClick = onInstall) {
                        Text("Installieren")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanTab(
    activePlan: String,
    busy: Boolean,
    onSelect: (String, String) -> Unit,
) {
    val plans = listOf(
        Triple("high", "Höchstleistung", "Maximale Leistung"),
        Triple("balanced", "Ausbalanciert", "Guter Kompromiss"),
        Triple("saver", "Energiesparen", "Wenig Verbrauch"),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Energieplan",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        // Aktiver Plan als animierte Leiste: der Wechsel gleitet ein, statt
        // einfach umzuspringen.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Aktiver Plan",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                AnimatedContent(
                    targetState = planLabel(activePlan),
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 })
                    },
                    label = "activePlan"
                ) { label ->
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        plans.forEach { (key, label, description) ->
            val active = key == activePlan
            val planContainer by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "planContainer"
            )
            val planContent by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "planContent"
            )
            val planScale by animateFloatAsState(
                targetValue = if (active) 1f else 0.97f,
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                label = "planScale"
            )
            Button(
                onClick = { onSelect(key, label) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .graphicsLayer {
                        scaleX = planScale
                        scaleY = planScale
                    },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = planContainer,
                    contentColor = planContent
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (active) "Aktiv" else description,
                        fontSize = 12.sp
                    )
                }
            }
        }

    }
}

/**
 * Karte fuer ein PC-Profil: erscheint gestaffelt (Kaskade) und animiert die
 * Auswahl-Farbe weich. "deletable" verhindert das Loeschen der letzten Karte.
 */
@Composable
private fun ProfileCard(
    profile: Profile,
    isActive: Boolean,
    index: Int,
    deletable: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    Appearing(index = index) {
        val containerColor by animateColorAsState(
            targetValue = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "profileCardColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name + if (isActive) " (aktiv)" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (profile.isComplete) {
                            "${profile.host}:${profile.port}"
                        } else {
                            "noch nicht eingerichtet"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (!isActive) {
                    TextButton(onClick = onSwitch) { Text("Aktivieren") }
                }
                if (deletable) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Profil löschen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private enum class SettingsDestination(
    val title: String,
    val subtitle: String,
) {
    PROFILE("PC-Profile", "Verbindung und Geräte verwalten"),
    APPEARANCE("Darstellung", "Farbschema und Aktualisierung"),
    UPDATES("Aktualisierungen", "Neue App-Versionen laden"),
    ABOUT("Über die App", "Version und weitere Informationen"),
}

private val SettingsAccent = Color(0xFFB39DDB)

@Composable
private fun SettingsTab(
    profiles: List<Profile>,
    active: Profile,
    simulate: Boolean?,
    busy: Boolean,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenWeb: () -> Unit,
    versionName: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onSave: (Profile) -> Unit,
    onTestConnection: () -> Unit,
    onModeChange: (Boolean) -> Unit,
) {
    var destination by remember { mutableStateOf<SettingsDestination?>(null) }

    var name by remember(active) { mutableStateOf(active.name) }
    var host by remember(active) { mutableStateOf(active.host) }
    var port by remember(active) { mutableStateOf(active.port) }
    var user by remember(active) { mutableStateOf(active.user) }
    var pass by remember(active) { mutableStateOf(active.pass) }
    var mac by remember(active) { mutableStateOf(active.mac) }
    var broadcast by remember(active) { mutableStateOf(active.broadcast) }
    var defaultDelay by remember(active) { mutableStateOf(active.defaultDelay) }
    var showPass by remember { mutableStateOf(false) }
    var macError by remember(active) { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val valid = host.isNotBlank() && isValidPort(port)

    BackHandler(enabled = destination != null) { destination = null }
    BackHandler(enabled = showScanner) { showScanner = false }

    if (showScanner) {
        QrScannerScreen(
            title = "QR-Code vom PC scannen",
            onResult = { raw ->
                showScanner = false
                val pairing = parsePcSetupQr(raw)
                if (pairing == null) {
                    scanError = "Das ist kein gültiger PC-Steuerung-QR-Code."
                } else {
                    name = pairing.name
                    host = pairing.host
                    port = pairing.port.toString()
                    scanError = null
                    saved = false
                }
            },
            onClose = { showScanner = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val page = destination
        if (page == null) {
            SettingsOverview(
                active = active,
                simulate = simulate,
                busy = busy,
                settings = settings,
                versionName = versionName,
                onOpenPage = { destination = it },
                onOpenWeb = onOpenWeb,
                onTestConnection = onTestConnection,
                onModeChange = onModeChange,
                onSettingsChange = onSettingsChange,
            )
        } else {
            SettingsPageHeader(
                title = page.title,
                subtitle = page.subtitle,
                onBack = { destination = null },
            )

            when (page) {
                SettingsDestination.PROFILE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Gespeicherte PCs",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        profiles.forEachIndexed { index, profile ->
                            ProfileCard(
                                profile = profile,
                                isActive = profile.id == active.id,
                                index = index,
                                deletable = profiles.size > 1,
                                onSwitch = { onSwitch(profile.id) },
                                onDelete = { onDelete(profile.id) },
                            )
                        }

                        OutlinedButton(
                            onClick = onAdd,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PC hinzufügen")
                        }

                        SettingsDetailCard {
                            Text(
                                text = "Profil bearbeiten: " + active.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { scanError = null; showScanner = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("QR-Code vom PC scannen")
                            }
                            Text(
                                text = "Übernimmt Name, IP und Port. Zugangsdaten bleiben erhalten.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            scanError?.let {
                                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it; saved = false },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it; saved = false },
                                label = { Text("IP des PCs") },
                                placeholder = { Text("192.168.1.100") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it; saved = false },
                                label = { Text("Port") },
                                singleLine = true,
                                isError = !isValidPort(port),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = user,
                                onValueChange = { user = it; saved = false },
                                label = { Text("Benutzername") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = pass,
                                onValueChange = { pass = it; saved = false },
                                label = { Text("Passwort") },
                                singleLine = true,
                                visualTransformation = if (showPass) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    TextButton(onClick = { showPass = !showPass }) {
                                        Text(if (showPass) "Verbergen" else "Anzeigen", fontSize = 12.sp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = mac,
                                onValueChange = { mac = it; macError = null; saved = false },
                                label = { Text("MAC-Adresse (für WoL)") },
                                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                singleLine = true,
                                isError = macError != null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            macError?.let {
                                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedTextField(
                                value = broadcast,
                                onValueChange = { broadcast = it; saved = false },
                                label = { Text("Broadcast-Adresse (WoL)") },
                                placeholder = { Text("255.255.255.255") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Standard-Timer beim Herunterfahren",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DelayChips(
                                selected = defaultDelay,
                                onSelect = { defaultDelay = it; saved = false },
                            )
                        }

                        Button(
                            onClick = {
                                if (!isValidMac(mac)) {
                                    macError = "Format: AA:BB:CC:DD:EE:FF"
                                } else {
                                    macError = null
                                    onSave(
                                        active.copy(
                                            name = name.trim().ifBlank { "PC" },
                                            host = host.trim(),
                                            port = port.trim(),
                                            user = user.trim(),
                                            pass = pass,
                                            mac = mac.trim(),
                                            broadcast = broadcast.trim().ifBlank { "255.255.255.255" },
                                            defaultDelay = defaultDelay,
                                        ),
                                    )
                                    saved = true
                                }
                            },
                            enabled = valid,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Änderungen speichern", fontSize = 16.sp)
                        }
                        if (saved) {
                            Text(
                                text = "Profil gespeichert.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Verbindung testen", fontSize = 15.sp)
                        }
                        Text(
                            text = "Zugangsdaten einmalig in der Web-Oberfläche am PC anlegen: http://localhost:8000/",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                SettingsDestination.APPEARANCE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SettingsDetailCard {
                            Text("Farbschema", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Wähle, ob PC Steuerung dem System folgt oder immer hell bzw. dunkel bleibt.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = settings.themeMode == mode,
                                        onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
                                        label = { Text(themeModeLabel(mode)) },
                                    )
                                }
                            }
                        }

                        SettingsDetailCard {
                            Text(
                                "Status-Aktualisierung",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Wie oft die App den PC-Status abfragt. Ein größeres Intervall spart Akku und Daten.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppSettingsStore.REFRESH_CHOICES.forEach { seconds ->
                                    FilterChip(
                                        selected = settings.refreshSeconds == seconds,
                                        onClick = {
                                            onSettingsChange(settings.copy(refreshSeconds = seconds))
                                        },
                                        label = { Text("$seconds s") },
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDestination.UPDATES -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        SettingsDetailCard {
                            AppUpdaterPanel(
                                versionName = versionName,
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                SettingsDestination.ABOUT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SettingsDetailCard {
                            Text("PC Steuerung", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Version " + versionName,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Die App prüft Aktualisierungen über GitHub Releases.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsDetailCard {
                            SettingsOverviewRow(
                                icon = Icons.Default.Home,
                                title = stringResource(R.string.settings_webui),
                                subtitle = active.base,
                                onClick = onOpenWeb,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsOverview(
    active: Profile,
    simulate: Boolean?,
    busy: Boolean,
    settings: AppSettings,
    versionName: String,
    onOpenPage: (SettingsDestination) -> Unit,
    onOpenWeb: () -> Unit,
    onTestConnection: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsSectionLabel(stringResource(R.string.settings_section_connection))
        SettingsGroup {
            SettingsOverviewRow(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_profiles),
                subtitle = if (active.isComplete) {
                    active.name + "  ·  " + active.host + ":" + active.port
                } else {
                    active.name + "  ·  " + stringResource(R.string.settings_profiles_not_setup)
                },
                onClick = { onOpenPage(SettingsDestination.PROFILE) },
            )
            SettingsGroupDivider()
            SettingsOverviewRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.settings_test_connection),
                subtitle = stringResource(R.string.settings_test_connection_sub),
                enabled = !busy,
                onClick = onTestConnection,
                trailing = if (busy) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_section_app))
        SettingsGroup {
            SettingsOverviewRow(
                icon = Icons.Default.Star,
                title = stringResource(R.string.settings_appearance),
                subtitle = themeModeLabel(settings.themeMode),
                onClick = { onOpenPage(SettingsDestination.APPEARANCE) },
            )
            SettingsGroupDivider()
            SettingsOverviewRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.settings_refresh),
                subtitle = stringResource(R.string.settings_refresh_value, settings.refreshSeconds),
                onClick = { onOpenPage(SettingsDestination.APPEARANCE) },
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_section_control))
        SettingsGroup {
            SettingsSwitchRow(
                icon = Icons.Default.Build,
                title = stringResource(R.string.settings_online_card),
                subtitle = if (settings.onlineCard) {
                    stringResource(R.string.settings_online_card_on)
                } else {
                    stringResource(R.string.settings_online_card_off)
                },
                checked = settings.onlineCard,
                enabled = true,
                onCheckedChange = { onSettingsChange(settings.copy(onlineCard = it)) },
            )
            SettingsGroupDivider()
            SettingsSwitchRow(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_simulation),
                subtitle = when (simulate) {
                    true -> stringResource(R.string.settings_simulation_on)
                    false -> stringResource(R.string.settings_simulation_off)
                    null -> stringResource(R.string.settings_simulation_unknown)
                },
                checked = simulate == true,
                enabled = simulate != null,
                onCheckedChange = onModeChange,
            )
            SettingsGroupDivider()
            SettingsOverviewRow(
                icon = Icons.Default.Home,
                title = stringResource(R.string.settings_webui),
                subtitle = active.base,
                onClick = onOpenWeb,
            )
        }

        SettingsSectionLabel("UPDATES")
        SettingsGroup {
            SettingsOverviewRow(
                icon = Icons.Default.Refresh,
                title = "Aktualisierungen",
                subtitle = "GitHub Releases · PC Steuerung " + versionName,
                onClick = { onOpenPage(SettingsDestination.UPDATES) },
            )
        }

        SettingsSectionLabel("WEITERE INFORMATIONEN")
        SettingsGroup {
            SettingsOverviewRow(
                icon = Icons.Default.Info,
                title = "Über die App",
                subtitle = "Version " + versionName,
                onClick = { onOpenPage(SettingsDestination.ABOUT) },
            )
        }
    }
}

@Composable
private fun SettingsPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        fontSize = 12.sp,
        letterSpacing = 1.1.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsOverviewRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SettingsAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SettingsAccent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else {
            Text(
                text = "›",
                fontSize = 28.sp,
                lineHeight = 28.sp,
                color = SettingsAccent,
            )
        }
    }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SettingsAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SettingsAccent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Hell"
    ThemeMode.DARK -> "Dunkel"
}

// ---------------------------------------------------------------------------
// Kachel-Startbildschirm und Aktionsseiten
//
// Bewusst ein eigener, kleiner Navigations-State statt einer Bibliothek: die
// Startseite ist ein Kachel-Raster, jede Kachel oeffnet genau eine Aktionsseite
// (Zurueck-Pfeil im TopAppBar, System-Zurueck ueber BackHandler).
// ---------------------------------------------------------------------------

/** Ziele der App: die Startseite plus je eine Aktionsseite pro Kachel. */
sealed interface Route {
    data object Home : Route
    data object Control : Route
    data object Steam : Route
    data object Plugins : Route
    data object Market : Route
    data object Routines : Route
    data object History : Route
    data object Energy : Route
    data object Settings : Route
}

/** Gemeinsamer Rahmen einer Aktionsseite: Zurueck-Pfeil, Titel, Untertitel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionPage(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** Eine Kachel: Ziel, Titel, Kurzstatus, Symbol und optionaler Statuspunkt. */
private data class HomeTileSpec(
    val route: Route,
    val title: String,
    val status: String,
    val icon: ImageVector,
    val dotColor: Color? = null,
    val pulsing: Boolean = false,
    /** Optionaler Fortschritt 0..1 (z. B. laufender Steam-Download). */
    val progress: Float? = null,
)

/** Kachel im Raster: grosse Touch-Flaeche, Material-3-Farben, abgerundet. */
@Composable
private fun HomeTile(tile: HomeTileSpec, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${tile.title}: ${tile.status}" },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (tile.dotColor != null) {
                    StatusDot(color = tile.dotColor, pulsing = tile.pulsing)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tile.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = tile.status,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tile.progress?.let { progress ->
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/**
 * Startseite: 2-Spalten-Raster mit Kurzstatus je Kachel. Die Inhalte selbst
 * liegen auf den Aktionsseiten; hier bleibt bewusst nur der Ueberblick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    active: Profile,
    state: AgentState,
    busy: Boolean,
    pendingLeft: Int,
    lastUpdated: String,
    refreshSeconds: Int,
    refreshing: Boolean,
    plugins: List<PluginInfo>,
    installedModules: Set<String>,
    moduleCount: Int,
    routines: List<RoutineInfo>,
    events: List<AgentEvent>,
    steam: SteamStatus?,
    steamLoading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Route) -> Unit,
) {
    val runningPlugins = plugins.count { it.running }
    val pluginStatus = when {
        plugins.isEmpty() -> stringResource(R.string.tile_no_plugins)
        runningPlugins > 0 ->
            stringResource(R.string.tile_plugins_active, plugins.size, runningPlugins)
        else -> stringResource(R.string.tile_plugins_count, plugins.size)
    }
    val marketStatus = if (moduleCount <= 0) {
        stringResource(R.string.plugins_empty)
    } else {
        stringResource(R.string.tile_modules_installed, installedModules.size, moduleCount)
    }
    val steamStatus = when {
        steam == null && steamLoading -> stringResource(R.string.tile_steam_checking)
        steam == null -> stringResource(R.string.tile_steam_unavailable)
        steam.downloading && steam.appName.isNotBlank() -> {
            val percent = "${fmtPercent(steam.progressPercent)} %"
            if (steam.speedBps > 0L) {
                stringResource(
                    R.string.steam_progress_speed,
                    percent,
                    steam.appName,
                    "${fmtBytes(steam.speedBps)}/s",
                )
            } else {
                stringResource(R.string.steam_progress, percent, steam.appName)
            }
        }
        steam.downloading -> stringResource(R.string.tile_steam_download_count, steam.downloads.size)
        !steam.detected -> stringResource(R.string.tile_steam_not_detected)
        else -> stringResource(R.string.tile_steam_no_downloads)
    }
    val routineStatus = if (routines.isEmpty()) {
        stringResource(R.string.tile_no_routines)
    } else {
        stringResource(R.string.tile_routines_count, routines.size)
    }
    val historyStatus = events.firstOrNull()
        ?.let { listOf(it.action, it.time).filter(String::isNotBlank).joinToString(" · ") }
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tile_no_events)
    val controlStatus = if (pendingLeft > 0) {
        stringResource(R.string.tile_pending, fmtDuration(pendingLeft))
    } else {
        listOf(statusLabel(state.status), active.host)
            .filter(String::isNotBlank)
            .joinToString(" · ")
    }

    val tiles = listOf(
        HomeTileSpec(
            route = Route.Control,
            title = stringResource(R.string.tile_control),
            status = controlStatus,
            icon = Icons.Default.Home,
            dotColor = statusColor(state.status),
            pulsing = state.status == PcStatus.ONLINE,
        ),
        HomeTileSpec(
            route = Route.Steam,
            title = stringResource(R.string.tile_steam),
            status = steamStatus,
            icon = Icons.Default.PlayArrow,
            // Laufender Download: duenner Balken auf der Kachel (wie Live-Karte).
            progress = steam?.let {
                if (it.downloading) (it.progressPercent / 100.0).toFloat() else null
            },
        ),
        HomeTileSpec(
            route = Route.Plugins,
            title = stringResource(R.string.tile_plugins),
            status = pluginStatus,
            icon = Icons.Default.Build,
        ),
        HomeTileSpec(
            route = Route.Market,
            title = stringResource(R.string.tile_market),
            status = marketStatus,
            icon = Icons.Default.ShoppingCart,
        ),
        HomeTileSpec(
            route = Route.Routines,
            title = stringResource(R.string.tile_routines),
            status = routineStatus,
            icon = Icons.Default.DateRange,
        ),
        HomeTileSpec(
            route = Route.History,
            title = stringResource(R.string.tile_history),
            status = historyStatus,
            icon = Icons.AutoMirrored.Filled.List,
        ),
        HomeTileSpec(
            route = Route.Energy,
            title = stringResource(R.string.tile_energy),
            status = planLabel(state.plan),
            icon = Icons.Default.Star,
        ),
        HomeTileSpec(
            route = Route.Settings,
            title = stringResource(R.string.tile_settings),
            status = active.name,
            icon = Icons.Default.Settings,
        ),
    )


    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_title),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = stringResource(R.string.home_subtitle),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton(onClick = onRefresh, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.home_refresh),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            color = statusColor(state.status),
                            pulsing = state.status == PcStatus.ONLINE,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${statusLabel(state.status)} · ${active.name}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_updated, lastUpdated) +
                            " · alle $refreshSeconds s",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            items(tiles) { tile ->
                HomeTile(tile = tile, onClick = { onOpen(tile.route) })
            }
        }
    }
}

/**
 * Aktionsseite Steam. `GET /steam` ist neu im Agenten: Fehlt die Route (404)
 * oder ist die Antwort unlesbar, zeigt die Seite "nicht verfuegbar" - sie
 * stuerzt nie ab und blockiert keine andere Funktion.
 */
@Composable
private fun SteamPage(
    steam: SteamStatus?,
    loading: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            steam == null && loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.steam_loading),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            steam == null -> {
                Text(
                    text = stringResource(R.string.steam_unavailable),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.steam_unavailable_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            else -> SteamDetails(steam = steam)
        }

        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.steam_refresh))
        }
    }
}

/** Erkannte Installation, Millennium und laufende Downloads als Liste. */
@Composable
private fun SteamDetails(steam: SteamStatus) {
    val chipLabels = listOf(
        if (steam.detected) {
            stringResource(R.string.steam_detected)
        } else {
            stringResource(R.string.steam_not_detected)
        },
        if (steam.running) {
            stringResource(R.string.steam_running)
        } else {
            stringResource(R.string.steam_not_running)
        },
        if (steam.millennium) {
            stringResource(R.string.steam_millennium_on)
        } else {
            stringResource(R.string.steam_millennium_off)
        },
    )
    val chipActive = listOf(steam.detected, steam.running, steam.millennium)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chipLabels.forEachIndexed { index, label ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (chipActive[index]) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (steam.speedBps > 0L || steam.etaSeconds > 0L) {
        val parts = ArrayList<String>(2)
        if (steam.speedBps > 0L) {
            parts.add(stringResource(R.string.steam_speed, fmtBytes(steam.speedBps)))
        }
        if (steam.etaSeconds > 0L) {
            parts.add(etaLabel(steam.etaSeconds))
        }
        Text(
            text = parts.joinToString(" · "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text = stringResource(R.string.steam_downloads_title),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline,
    )

    if (steam.downloads.isEmpty()) {
        Text(
            text = stringResource(R.string.steam_no_downloads),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    steam.downloads.forEach { download ->
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = download.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${fmtPercent(download.percent)} %",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { (download.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val details = ArrayList<String>(4)
                details.add(
                    stringResource(
                        R.string.steam_of,
                        fmtBytes(download.downloaded),
                        fmtBytes(download.total),
                    )
                )
                if (download.speedBps > 0L) {
                    details.add("${fmtBytes(download.speedBps)}/s")
                }
                if (download.etaSeconds > 0L) {
                    details.add(etaLabel(download.etaSeconds))
                }
                // Updates: der Plattenstand weicht vom Download ab (Steam staged
                // in Bloecken) - nur zeigen, wenn der Unterschied sichtbar ist.
                if (download.percentStaged > 0.0 &&
                    kotlin.math.abs(download.percentStaged - download.percent) >= 1.0
                ) {
                    details.add(stringResource(R.string.steam_staged, fmtPercent(download.percentStaged)))
                }
                Text(
                    text = details.joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** "noch 12 Min." fuer Download-Restzeiten (0 = unbekannt). */
@Composable
private fun etaLabel(seconds: Long): String {
    val value = seconds.coerceAtLeast(0L)
    return when {
        value < 60L -> stringResource(R.string.steam_eta_seconds, value.toInt())
        value < 3600L -> stringResource(R.string.steam_eta_minutes, (value / 60L).toInt())
        else -> stringResource(
            R.string.steam_eta_hours,
            (value / 3600L).toInt(),
            ((value % 3600L) / 60L).toInt(),
        )
    }
}

/**
 * Aktionsseite Routinen: Liste aus `GET /routines`. "Jetzt" loest
 * `POST /routines/{id}/run` aus, An/Aus ersetzt die Routine per `POST /routines`
 * (die Kennung bleibt gleich), "Loeschen" nutzt `DELETE /routines/{id}`.
 */
@Composable
private fun RoutinesPage(
    routines: List<RoutineInfo>,
    loading: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRun: (RoutineInfo) -> Unit,
    onToggle: (RoutineInfo) -> Unit,
    onDelete: (RoutineInfo) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.routines_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.routines_refresh),
                )
            }
        }

        if (routines.isEmpty()) {
            Text(
                text = if (loading) {
                    stringResource(R.string.routines_loading)
                } else {
                    stringResource(R.string.routines_empty)
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        routines.forEach { routine ->
            RoutineCard(
                routine = routine,
                busy = busy,
                onRun = { onRun(routine) },
                onToggle = { onToggle(routine) },
                onDelete = { onDelete(routine) },
            )
        }
    }
}

/** Eine Routine mit Ausloeser, Aktionen und den drei Bedienknoepfen. */
@Composable
private fun RoutineCard(
    routine: RoutineInfo,
    busy: Boolean,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = routine.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (routine.enabled) {
                        stringResource(R.string.routines_on)
                    } else {
                        stringResource(R.string.routines_off)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (routine.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            Text(
                text = routine.triggerText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (routine.actionTexts.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.routines_actions,
                        routine.actionTexts.joinToString(" · "),
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (routine.lastRun.isBlank()) {
                    stringResource(R.string.routines_never)
                } else {
                    stringResource(R.string.routines_last, routine.lastRun)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            if (routine.nextRun.isNotBlank()) {
                Text(
                    text = stringResource(R.string.routines_next, routine.nextRun),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRun,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.routines_run), fontSize = 13.sp) }
                OutlinedButton(
                    onClick = onToggle,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (routine.enabled) {
                            stringResource(R.string.routines_switch_off)
                        } else {
                            stringResource(R.string.routines_switch_on)
                        },
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(
                        text = stringResource(R.string.routines_delete),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Aktionsseite Verlauf: Ereignisse aus `GET /events` und das Log des Agenten
 * (`GET /log`). Nur lesend; kopiert wird wie bisher ueber die Zwischenablage.
 */
@Composable
private fun HistoryPage(
    events: List<AgentEvent>,
    logText: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.history_events_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.history_refresh),
                )
            }
        }

        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        events.take(30).forEach { event ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = event.action,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (event.ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = event.time,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (event.detail.isNotBlank()) {
                        Text(
                            text = event.detail,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            text = stringResource(R.string.history_log_title),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = logText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCopy,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.history_copy)) }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.history_refresh)) }
        }
    }
}


