@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

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
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

// Routinen-Modell, Parser und Kurztexte liegen in `Routines.kt` (Phase E).

// Ereignis-Modell (`AgentEvent`), Parser (`parseEvents`), Filter, Suche,
// Zeitgruppen und Export-Text liegen in `History.kt` (Phase F).

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
                    Text(stringResource(R.string.camera_permission))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(android.R.string.cancel), color = Color.White)
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
            @Suppress("UnsafeOptInUsageError")
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
    val ctx = LocalContext.current
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
            pass.isNotEmpty() && pass != pass2 -> formError = ctx.getString(R.string.passwords_mismatch)
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
                .imePadding()
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
                                        Text(stringResource(R.string.test_connection))
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
                                    label = { Text(stringResource(R.string.username_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pass,
                                    onValueChange = { pass = it; formError = null },
                                    label = { Text(stringResource(R.string.password_label)) },
                                    singleLine = true,
                                    visualTransformation = if (showPass) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    trailingIcon = {
                                        TextButton(onClick = { showPass = !showPass }) {
                                            Text(if (showPass) stringResource(R.string.password_hide) else stringResource(R.string.password_show), fontSize = 12.sp)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pass2,
                                    onValueChange = { pass2 = it; formError = null },
                                    label = { Text(stringResource(R.string.repeat_password_label)) },
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
                                    formError = ctx.getString(R.string.passwords_mismatch)
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
    // Installationsversionen (Phase F5): JSON `{"<id>": <version>}`. Ohne
    // Eintrag (Altdaten) gilt Version 1, damit ein Katalog-Update auffaellt.
    var moduleVersionsRaw by remember { mutableStateOf(ModuleStore.loadVersionsRaw(context)) }
    val moduleVersions = remember(moduleVersionsRaw) { installedVersions(moduleVersionsRaw) }
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
    // Vorlagen (`GET /routine-templates`): bleibt leer, wenn der Agent die
    // Route nicht kennt - die Seite blendet den Abschnitt dann aus.
    var routineTemplates by remember { mutableStateOf<List<RoutineTemplate>>(emptyList()) }
    var events by remember { mutableStateOf<List<AgentEvent>>(emptyList()) }

    // Systemwerte (`GET /sensors`): null = noch nie geladen. Die Seite fragt nur
    // ab, solange sie offen ist (siehe LaunchedEffect weiter unten), die
    // Startseite nutzt denselben Wert nur fuer den Kurzstatus der Kachel.
    var sensors by remember { mutableStateOf<SensorsStatus?>(null) }
    var sensorsRefreshing by remember { mutableStateOf(false) }
    var sensorsRefresh by remember { mutableIntStateOf(0) }

    // Warnungen (`GET /warnings`): Regeln, aktive Warnungen, Verlauf. Wie bei
    // Systemwerten wird nur geladen, solange die Seite offen ist.
    var warnings by remember { mutableStateOf<WarningsStatus?>(null) }
    var warningsRefreshing by remember { mutableStateOf(false) }
    var warningsRefresh by remember { mutableIntStateOf(0) }

    // Plugin-Detailseite: Ausgabe der letzten Zeilen (`GET /plugins/{id}/output`).
    var pluginOutput by remember { mutableStateOf("") }

    // Plugin-Detailseite (Phase G4): Schema und Werte aus
    // `GET /plugins/{id}/settings`. Ohne Schema bleibt der Abschnitt aus.
    var pluginSettings by remember { mutableStateOf<PluginSettingsSchema?>(null) }
    var pluginSettingsValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pluginSettingsNotice by remember { mutableStateOf<String?>(null) }
    var pluginSettingsError by remember { mutableStateOf<String?>(null) }

    // Spiele-Optimierung (`GET /games` + `GET /game-config`). `gameConfig` bleibt
    // null, wenn der Agent /game-config nicht kennt - dann ist nur Lesen moeglich.
    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var gameConfig by remember { mutableStateOf<GameConfig?>(null) }
    var gamesLoading by remember { mutableStateOf(true) }
    var gamesRefresh by remember { mutableIntStateOf(0) }

    fun installModule(module: CatalogModule) {
        val next = installedModules + module.id
        // Beim Installieren wird die Katalogversion festgeschrieben.
        val nextVersions = withInstalledVersion(moduleVersionsRaw, module.id, module.version)
        ModuleStore.save(context, next)
        ModuleStore.saveVersions(context, nextVersions)
        installedModules = next
        moduleVersionsRaw = nextVersions
        message = "${module.name} installiert"
    }

    fun uninstallModule(module: CatalogModule) {
        val next = installedModules - module.id
        // Mit der Installation verschwindet auch der Versions-Eintrag.
        val nextVersions = withoutInstalledVersion(moduleVersionsRaw, module.id)
        ModuleStore.save(context, next)
        ModuleStore.saveVersions(context, nextVersions)
        installedModules = next
        moduleVersionsRaw = nextVersions
        message = "${module.name} deinstalliert"
    }

    /**
     * Update eines installierten Moduls: die Version wandert auf den
     * Katalogstand, das Modul bleibt installiert. Kein Downgrade - der Aufruf
     * kommt nur, wenn [moduleUpdateAvailable] wahr ist.
     */
    fun updateModule(module: CatalogModule) {
        val nextVersions = withInstalledVersion(moduleVersionsRaw, module.id, module.version)
        ModuleStore.saveVersions(context, nextVersions)
        moduleVersionsRaw = nextVersions
        message = "${module.name} aktualisiert"
    }

    // ---------------- Plugins des Agenten ----------------

    fun pluginAction(
        path: String,
        method: String,
        success: String,
        afterSuccess: () -> Unit = {},
    ) {
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
                val failed = result.startsWith("HTTP ") || result.startsWith("Nicht")
                message = if (failed) result else success
                if (!failed) afterSuccess()
                pluginRefresh++
            } finally {
                busy = false
            }
        }
    }

    /**
     * Start eines Plugins: je nach Einstellung bleibt die Detailseite offen
     * ("Manager offen lassen", Default) oder es geht zurueck zur Uebersicht.
     */
    fun startPlugin(plugin: PluginInfo) = {
        val pluginAutoClose = settings.pluginAutoClose[plugin.id] ?: false
        pluginAction("/plugins/${plugin.id}/start", "POST", "${plugin.name} gestartet") {
            if (pluginAutoClose) route = Route.Plugins
        }
    }()

    fun stopPlugin(plugin: PluginInfo) =
        pluginAction("/plugins/${plugin.id}/stop", "POST", "${plugin.name} beendet")

    // Nach dem Entfernen existiert die Detailseite nicht mehr - zurueck zur Liste.
    fun removePlugin(plugin: PluginInfo) =
        pluginAction("/plugins/${plugin.id}", "DELETE", "${plugin.name} entfernt") {
            route = Route.Plugins
        }

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
     * Spiele-Liste (`GET /games`) und Konfiguration (`GET /game-config`) laden.
     * Der Agent fuehrt `custom` schon in die Liste ein; damit auch ein aelterer
     * Agent ohne dieses Verhalten vollstaendig aussieht, werden die manuellen
     * Spiele hier zusaetzlich zusammengefuehrt.
     */
    suspend fun loadGames() {
        val result = withContext(Dispatchers.IO) {
            try {
                val list = parseGames(httpCall(active.base, "/games", active.authHeader()))
                val config = parseGameConfig(httpCall(active.base, "/game-config", active.authHeader()))
                list to config
            } catch (e: Exception) {
                Log.e(TAG, "Spiele konnten nicht geladen werden", e)
                emptyList<GameEntry>() to null
            }
        }
        val config = result.second
        games = if (config == null) result.first else mergeCustomGames(result.first, config.custom)
        gameConfig = config
        gamesLoading = false
    }

    /** Letzte Ausgabezeilen eines Plugins (`GET /plugins/{id}/output?lines=100`). */
    suspend fun loadPluginOutput(id: String) {
        pluginOutput = withContext(Dispatchers.IO) {
            try {
                parsePluginOutput(
                    httpCall(active.base, "/plugins/$id/output?lines=100", active.authHeader())
                )
            } catch (e: Exception) {
                Log.e(TAG, "Plugin-Ausgabe nicht erreichbar", e)
                "Nicht erreichbar: ${e.message ?: "Fehler"}"
            }
        }
    }

    /**
     * Einstellungen eines Plugins (`GET /plugins/{id}/settings`). Fehlt die
     * Route (404, alter Agent) oder ist das Schema leer, bleibt [pluginSettings]
     * null und die Detailseite zeigt keinen Einstellungs-Abschnitt.
     */
    suspend fun loadPluginSettings(id: String) {
        // Neue Seite, neue Rueckmeldung: ein alter Hinweis darf nicht mitwandern.
        pluginSettingsNotice = null
        pluginSettingsError = null
        val parsed = withContext(Dispatchers.IO) {
            try {
                parsePluginSettings(
                    httpCall(active.base, "/plugins/$id/settings", active.authHeader())
                )
            } catch (e: Exception) {
                Log.e(TAG, "Plugin-Einstellungen nicht erreichbar", e)
                null
            }
        }
        pluginSettings = parsed
        pluginSettingsValues = if (parsed == null) emptyMap() else pluginSettingValues(parsed)
    }

    /**
     * Einstellungen speichern (`POST /plugins/{id}/settings`). Zahlenfelder
     * werden vorher gegen min/max geprueft, damit ungueltige Eingaben erst gar
     * nicht zum Agenten gehen. Schlaegt der Aufruf fehl (400/404/422), bleiben
     * die Eingaben stehen und der Grund wird deutsch angezeigt.
     */
    fun savePluginSettings(id: String) {
        val schema = pluginSettings ?: return
        if (busy) return
        val invalidNumber = schema.fields.any { field ->
            field.type == PluginSettingType.NUMBER &&
                checkPluginNumber(field, pluginSettingsValues[field.key].orEmpty()) != null
        }
        if (invalidNumber) {
            pluginSettingsNotice = null
            pluginSettingsError = context.getString(R.string.plugins_settings_invalid)
            return
        }
        scope.launch {
            busy = true
            try {
                val body = pluginSettingsBody(schema, pluginSettingsValues)
                val answer = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, "/plugins/$id/settings", active.authHeader(), "POST", body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Plugin-Einstellungen nicht speicherbar", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                when (val result = parsePluginSettingsSave(answer)) {
                    is PluginSettingsSave.Saved -> {
                        // Antwort uebernehmen: sie enthaelt den vollstaendigen Satz.
                        pluginSettingsValues = schema.fields.associate { field ->
                            field.key to (result.values[field.key]
                                ?: pluginSettingsValues[field.key]
                                ?: field.defaultValue)
                        }
                        pluginSettingsError = null
                        pluginSettingsNotice = context.getString(R.string.plugins_settings_saved)
                    }

                    is PluginSettingsSave.Rejected -> {
                        pluginSettingsNotice = null
                        pluginSettingsError = when {
                            // Kein HTTP-Fehler, sondern gar keine Verbindung.
                            answer.startsWith("Nicht erreichbar") -> answer
                            result.detail.isNotBlank() -> result.detail
                            result.code != null -> context.getString(
                                R.string.plugins_settings_rejected,
                                result.code,
                            )

                            else -> context.getString(R.string.plugins_settings_rejected_plain)
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }


    /** Schreibende Spiele-Aktion (z. B. `POST /games/scan`), danach neu laden. */
    fun gameAction(path: String, method: String, body: String?, success: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, path, active.authHeader(), method, body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Spiele-Aktion fehlgeschlagen: $path", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                message = if (result.startsWith("HTTP ") || result.startsWith("Nicht")) {
                    result
                } else {
                    success
                }
                gamesRefresh++
            } finally {
                busy = false
            }
        }
    }
    

    /**
     * Aenderung an der Spiel-Konfiguration speichern. Der Vertrag ist immer der
     * komplette Zustand (GET -> Feld aendern -> POST); der neue Zustand wird
     * sofort angezeigt, die Antwort des Agenten korrigiert ihn danach.
     */
    fun saveGameConfig(next: GameConfig, success: String) {
        if (busy) return
        gameConfig = next
        gameAction("/game-config", "POST", gameConfigJson(next), success)
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

    /**
     * Vorlagen des Agenten (`GET /routine-templates`, Phase E). Ein alter
     * Agent antwortet mit 404 - dann bleibt die Liste leer und die Seite zeigt
     * den Abschnitt "Vorlagen" nicht.
     */
    suspend fun loadRoutineTemplates() {
        val list = withContext(Dispatchers.IO) {
            try {
                parseRoutineTemplates(httpCall(active.base, "/routine-templates", active.authHeader()))
            } catch (e: Exception) {
                Log.e(TAG, "Routinen-Vorlagen nicht erreichbar", e)
                emptyList()
            }
        }
        routineTemplates = list
    }

    /**
     * Systemwerte des Agenten (`GET /sensors`). Fehlt die Route (404, alter
     * Agent), liefert [parseSensors] einen leeren Status - Kachel und Seite
     * zeigen dann "Nicht verfügbar" statt Fantasiewerten.
     */
    suspend fun loadSensors() {
        val result = withContext(Dispatchers.IO) {
            try {
                parseSensors(httpCall(active.base, "/sensors", active.authHeader()))
            } catch (e: Exception) {
                Log.e(TAG, "Systemwerte nicht erreichbar", e)
                SensorsStatus()
            }
        }
        sensors = result
        sensorsRefreshing = false
    }

    /** Warnungen des Agenten (`GET /warnings`). */
    suspend fun loadWarnings() {
        val result = withContext(Dispatchers.IO) {
            try {
                parseWarnings(httpCall(active.base, "/warnings", active.authHeader()))
            } catch (e: Exception) {
                Log.e(TAG, "Warnungen nicht erreichbar", e)
                WarningsStatus()
            }
        }
        warnings = result
        warningsRefreshing = false
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
            // Aufbewahrung aus den Einstellungen (200/500/1000 Zeilen, Default 200).
            httpCall(active.base, "/log?lines=${settings.historyLines}", active.authHeader())
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

    /**
     * Routine aus dem Formular speichern (`POST /routines`). Geprueft wird
     * schon im Dialog; hier wird nur noch umgewandelt und geschickt. Eine neue
     * Routine bekommt eine freie Kennung, damit nichts ueberschrieben wird.
     */
    fun saveRoutine(draft: RoutineDraft) {
        val routine = routineFromDraft(draft)
        val target = if (draft.id.isBlank()) {
            routine.copy(id = uniqueRoutineId(routine.id, routines.map { it.id }))
        } else {
            routine
        }
        routineAction(
            path = "/routines",
            method = "POST",
            success = if (draft.id.isBlank()) "Routine angelegt" else "Routine gespeichert",
            body = routineJson(target),
        )
    }

    /** Vorlage aus `GET /routine-templates` als eigene Routine anlegen. */
    fun createRoutineFromTemplate(template: RoutineTemplate) {
        val routine = routineFromTemplate(template)
        routineAction(
            path = "/routines",
            method = "POST",
            success = "${template.name} angelegt",
            body = routineJson(
                routine.copy(id = uniqueRoutineId(routine.id, routines.map { it.id }))
            ),
        )
    }

    /** Warnregeln speichern (kompletter Regelsatz, `POST /warnings`). */
    fun saveWarningsRules(rules: List<WarningRule>) {
        val body = JSONObject().put("rules", JSONArray(rules.map { rule ->
            JSONObject()
                .put("id", rule.id)
                .put("label", rule.label)
                .put("metric", rule.metric)
                .put("op", rule.op)
                .put("threshold", rule.threshold)
                .put("duration_s", rule.durationS)
                .put("enabled", rule.enabled)
        })).toString()
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, "/warnings", active.authHeader(), "POST", body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Warnregeln speichern fehlgeschlagen", e)
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                val failed = result.startsWith("HTTP ") || result.startsWith("Nicht")
                message = if (failed) result else "Warnregeln gespeichert"
                if (!failed) {
                    loadWarnings()
                }
                refresh++
            } finally {
                busy = false
            }
        }
    }

    // Nachladen abhaengig von der sichtbaren Seite. Die Startseite braucht die
    // Kurzstatus, die Aktionsseiten ihre vollen Listen.
    LaunchedEffect(active, route, pluginRefresh, refresh, gamesRefresh) {
        // `val current` statt `route`: der Zustand ist delegiert, ein Smart-Cast
        // auf `route` ist in Kotlin deshalb nicht erlaubt.
        when (val current = route) {
            Route.Home -> {
                loadPlugins()
                loadRoutines()
                loadSteam()
                loadSensors()
                loadWarnings()
            }

            Route.Plugins -> {
                loadPlugins()
                loadSteam()
                loadGames()
            }

            is Route.PluginDetail -> {
                loadPlugins()
                loadPluginOutput(current.id)
                loadPluginSettings(current.id)
            }

            Route.GameOptimization -> loadGames()
            Route.Steam -> loadSteam()
            Route.Warnings -> loadWarnings()
            Route.Routines -> {
                loadRoutines()
                loadRoutineTemplates()
                // Plugins fuellen die Bedingung "Plugin muss laufen".
                loadPlugins()
            }
            Route.History -> {
                loadEvents()
                logText = fetchLogText().trim().ifBlank { "Noch keine Einträge." }
            }

            else -> Unit
        }
    }

    // Systemwerte-Seite: nur solange die Seite offen ist, alle 2 Sekunden
    // nachfragen (Pflichtregel D1 - keine Dauerabfrage im Hintergrund). Das
    // Verlassen der Seite beendet den Effekt, STARTED pausiert ihn zusaetzlich.
    LaunchedEffect(active, route, sensorsRefresh) {
        if (route != Route.Sensors) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loadSensors()
                delay(2000L)
            }
        }
    }

    // Kacheln der Startseite regelmaessig auffrischen (Plugins, Steam, Routinen,
    // Systemwerte), aber nur solange die Seite sichtbar ist - kein Funkverkehr
    // im Hintergrund.
    LaunchedEffect(active, route, refresh, pluginRefresh) {
        if (route != Route.Home) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(15_000)
                loadPlugins()
                loadRoutines()
                loadSteam()
                loadSensors()
            }
        }
    }

    // Warnungen-Seite: alle 30 s nachfragen, nur solange die Seite offen ist.
    LaunchedEffect(active, route, warningsRefresh) {
        if (route != Route.Warnings) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loadWarnings()
                delay(30_000L)
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
        message = context.getString(R.string.history_copied)
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

    // Zurueck-Taste: von jeder Aktionsseite zur Kachel-Startseite statt die App
    // zu schliessen (typisches Android-Verhalten). Detailseiten fuehren zu ihrer
    // Uebersicht zurueck (siehe backTarget).
    BackHandler(enabled = route != Route.Home) { route = backTarget(route) }

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
        contentWindowInsets = WindowInsets.safeDrawing,
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
                        sensors = sensors,
                        games = games,
                        onRefresh = {
                            refreshing = true
                            refresh++
                        },
                        onOpen = { route = it },
                    )

                    Route.Sensors -> ActionPage(
                        title = stringResource(R.string.page_sensors_title),
                        subtitle = stringResource(R.string.page_sensors_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        SensorsPage(
                            status = sensors,
                            refreshing = sensorsRefreshing,
                            onRefresh = {
                                sensorsRefreshing = true
                                sensorsRefresh++
                            },
                        )
                    }

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
                            onHibernate = { runAction("/hibernate", context.getString(R.string.hibernate_triggered)) },
                            onAbort = { runAction("/abort", context.getString(R.string.abort_triggered)) },
                            onShowLog = ::loadLog,
                            onOpenMarket = { route = Route.Market },
                            onOpenSettings = { route = Route.Settings },
                        )
                    }

                    Route.Steam -> ActionPage(
                        title = stringResource(R.string.page_steam_title),
                        subtitle = stringResource(R.string.page_steam_subtitle),
                        onBack = { route = backTarget(Route.Steam) },
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
                        onBack = { route = backTarget(Route.Plugins) },
                    ) {
                        PluginsPage(
                            plugins = plugins,
                            pluginCatalog = pluginCatalog,
                            busy = busy,
                            onRefresh = { pluginRefresh++ },
                            onOpenPlugin = { plugin -> route = Route.PluginDetail(plugin.id) },
                            onInstall = ::installPlugin,
                        )
                    }

                    is Route.PluginDetail -> {
                        val plugin = plugins.firstOrNull { it.id == screen.id }
                        ActionPage(
                            title = plugin?.name ?: stringResource(R.string.page_plugins_title),
                            subtitle = stringResource(R.string.plugins_detail_subtitle),
                            onBack = { route = backTarget(screen) },
                        ) {
                            val pluginAutoClose = settings.pluginAutoClose[plugin?.id ?: ""] ?: false
                            PluginDetailPage(
                                plugin = plugin,
                                output = pluginOutput,
                                busy = busy,
                                autoClose = pluginAutoClose,
                                onAutoCloseChange = { value ->
                                    val newMap = settings.pluginAutoClose.toMutableMap()
                                    newMap[plugin?.id ?: ""] = value
                                    onSettingsChange(settings.copy(pluginAutoClose = newMap))
                                },
                                settingsSchema = pluginSettings,
                                settingsValues = pluginSettingsValues,
                                settingsNotice = pluginSettingsNotice,
                                settingsError = pluginSettingsError,
                                onSettingChange = { key, value ->
                                    pluginSettingsValues = pluginSettingsValues + (key to value)
                                    pluginSettingsNotice = null
                                    pluginSettingsError = null
                                },
                                onSaveSettings = { savePluginSettings(screen.id) },
                                onStart = { plugin?.let(::startPlugin) },
                                onStop = { plugin?.let(::stopPlugin) },
                                onRemove = { plugin?.let(::removePlugin) },
                                onRefresh = { pluginRefresh++ },
                            )
                        }
                    }

                    Route.GameOptimization -> ActionPage(
                        title = stringResource(R.string.page_games_title),
                        subtitle = stringResource(R.string.page_games_subtitle),
                        onBack = { route = backTarget(Route.GameOptimization) },
                    ) {
                        GameOptimizationPage(
                            games = games,
                            config = gameConfig,
                            loading = gamesLoading,
                            busy = busy,
                            onRefresh = { gamesRefresh++ },
                            onScan = { gameAction("/games/scan", "POST", null, context.getString(R.string.games_scanned)) },
                            onCleanMemory = { gameAction("/clean-memory", "POST", null, context.getString(R.string.memory_cleaned)) },
                            onToggleHidden = { game ->
                                val config = gameConfig
                                if (config != null) {
                                    val hide = !isHiddenGame(game, config.hidden)
                                    saveGameConfig(
                                        config.copy(
                                            hidden = withHidden(config.hidden, game, hide)
                                        ),
                                        if (hide) {
                                            "${game.name} ausgeblendet"
                                        } else {
                                            "${game.name} eingeblendet"
                                        },
                                    )
                                }
                            },
                            onAddManual = { name, exe ->
                                val config = gameConfig
                                if (config != null) {
                                    saveGameConfig(
                                        withCustomGame(
                                            config,
                                            GameEntry(name = name.trim(), exe = exe.trim()),
                                        ),
                                        "${name.trim()} hinzugefügt",
                                    )
                                }
                            },
                            onDeleteManual = { game ->
                                val config = gameConfig
                                if (config != null) {
                                    saveGameConfig(
                                        withoutCustomGame(config, game),
                                        "${game.name} gelöscht",
                                    )
                                }
                            },
                            onKillChange = { name, present ->
                                val config = gameConfig
                                if (config != null) {
                                    saveGameConfig(
                                        config.copy(
                                            killList = withKillEntry(config.killList, name, present)
                                        ),
                                        if (present) "$name aufgenommen" else "$name entfernt",
                                    )
                                }
                            },
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
                            versions = moduleVersions,
                            source = catalogSource,
                            busy = busy,
                            onRefresh = { catalogRefresh++ },
                            onOpenModule = { module -> route = Route.ModuleDetail(module.id) },
                            onInstall = ::installModule,
                            onUninstall = ::uninstallModule,
                            onUpdate = ::updateModule,
                            pluginCount = plugins.size,
                            onOpenPlugins = { route = Route.Plugins },
                        )
                    }

                    is Route.ModuleDetail -> {
                        // Katalog kann sich geaendert haben: Modul fehlt dann,
                        // die Seite zeigt einen Hinweis statt zu crashen.
                        val module = catalogModules.firstOrNull { it.id == screen.id }
                        ActionPage(
                            title = module?.name ?: stringResource(R.string.page_market_title),
                            subtitle = stringResource(R.string.module_detail_subtitle),
                            onBack = { route = backTarget(screen) },
                        ) {
                            ModuleDetailPage(
                                module = module,
                                installed = module != null && module.id in installedModules,
                                installedVersion = module?.let {
                                    installedVersionOf(moduleVersions, it.id)
                                },
                                supported = module != null && module.id in SUPPORTED_MODULES,
                                busy = busy,
                                onInstall = { module?.let(::installModule) },
                                onUninstall = { module?.let(::uninstallModule) },
                                onUpdate = { module?.let(::updateModule) },
                            )
                        }
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

                    Route.Warnings -> ActionPage(
                        title = stringResource(R.string.page_warnings_title),
                        subtitle = stringResource(R.string.page_warnings_subtitle),
                        onBack = { route = Route.Home },
                    ) {
                        WarningsPage(
                            status = warnings,
                            refreshing = warningsRefreshing,
                            busy = busy,
                            onRefresh = { warningsRefreshing = true; warningsRefresh++ },
                            onSaveRules = { newRules ->
                                val currentRules = warnings?.rules ?: emptyList()
                                saveWarningsRules(currentRules + newRules)
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
                            templates = routineTemplates,
                            plugins = plugins,
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
                                    routineJson(withRoutineEnabled(routine, !routine.enabled)),
                                )
                            },
                            onDelete = { routine ->
                                routineAction(
                                    "/routines/${routine.id}",
                                    "DELETE",
                                    "${routine.name} gelöscht",
                                )
                            },
                            onSave = ::saveRoutine,
                            onCreateFromTemplate = ::createRoutineFromTemplate,
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
                            pcName = active.name,
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
                            installedModules = installedModules,
                            onSettingsChange = onSettingsChange,
                            onModulesChange = { ids ->
                                // Import uebernimmt die Modul-IDs direkt in den Speicher.
                                ModuleStore.save(context, ids)
                                installedModules = ids
                            },
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
                                    runAction("/mode?simulate=true", context.getString(R.string.simulation_activated))
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
                val shutdownMsg = if (delay == 0) {
                    stringResource(R.string.shutdown_triggered)
                } else {
                    stringResource(R.string.shutdown_planned, delay)
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showShutdownConfirm = false
                        runAction("/shutdown?delay=$delay", shutdownMsg)
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
                                runAction("/shutdown?delay=${hours * 3600}", context.getString(R.string.shutdown_planned_hours, hours))
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
                        runAction("/shutdown?delay=$total", context.getString(R.string.shutdown_planned_minutes, total / 60))
                    }
                }) { Text("Starten") }
            },
            dismissButton = {
                TextButton(onClick = { showTimer = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showRealModeConfirm) {
        val realModeActivated = stringResource(R.string.real_mode_activated)
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
                    runAction("/mode?simulate=false", realModeActivated)
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
                val historyCopied = stringResource(R.string.history_copied)
                Row {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("PC-Agent-Verlauf", logText)
                            )
                            message = historyCopied
                            showLog = false
                        }
                    ) { Text(stringResource(R.string.copy)) }
                    TextButton(enabled = !busy, onClick = { loadLog() }) { Text(stringResource(R.string.refresh_action)) }
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
                        contentDescription = stringResource(R.string.refresh_status),
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
                                    ) { Text(stringResource(R.string.hibernate_label), fontSize = 14.sp) }
                                    OutlinedButton(
                                        onClick = onShowLog,
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text(stringResource(R.string.history_title), fontSize = 14.sp) }
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
                                ) { Text(stringResource(R.string.hibernate_label), fontSize = 14.sp) }
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
                                ) { Text(stringResource(R.string.check_now)) }
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
 *
 * Ein Tipp auf eine Modul-Karte oeffnet die Detailseite (`Route.ModuleDetail`);
 * Installieren/Entfernen/Aktualisieren geht weiter direkt auf der Karte.
 */
@Composable
private fun MarketPage(
    modules: List<CatalogModule>,
    installed: Set<String>,
    versions: Map<String, Int>,
    source: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenModule: (CatalogModule) -> Unit,
    onInstall: (CatalogModule) -> Unit,
    onUninstall: (CatalogModule) -> Unit,
    onUpdate: (CatalogModule) -> Unit,
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
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_catalog))
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
            val isInstalled = module.id in installed
            ModuleCard(
                module = module,
                isInstalled = isInstalled,
                // Ohne Installation gibt es keine gemerkte Version.
                installedVersion = if (isInstalled) installedVersionOf(versions, module.id) else null,
                index = index,
                busy = busy,
                onOpen = { onOpenModule(module) },
                onInstall = { onInstall(module) },
                onUninstall = { onUninstall(module) },
                onUpdate = { onUpdate(module) },
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
 * Aktionsseite Plugins: 2-Spalten-Kachelraster der lokalen Plugins des Agenten.
 *
 * Jede Kachel oeffnet eine Detailseite (Start/Stop, Entfernen, Ausgabe). Dazu
 * kommen zwei Sonderkacheln ohne JS-Plugin - Steam und Spiele-Optimierung -,
 * weil beide einen eigenen Bereich mit eigener Seite haben. Der Marktplatz-
 * Katalog bleibt als Liste unter dem Raster.
 */
@Composable
private fun PluginsPage(
    plugins: List<PluginInfo>,
    pluginCatalog: List<PluginCatalogEntry>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenPlugin: (PluginInfo) -> Unit,
    onInstall: (PluginCatalogEntry) -> Unit,
) {
    val running = plugins.count { it.running }
    val summary = when {
        plugins.isEmpty() -> stringResource(R.string.plugins_empty)
        running > 0 -> stringResource(R.string.tile_plugins_active, plugins.size, running)
        else -> stringResource(R.string.tile_plugins_count, plugins.size)
    }
    // Kacheln wie auf der Startseite (HomeTile), je Kachel ein eigenes Ziel.
    val tiles = ArrayList<Pair<HomeTileSpec, () -> Unit>>(plugins.size)
    plugins.forEach { plugin ->
        tiles.add(
            HomeTileSpec(
                route = Route.PluginDetail(plugin.id),
                title = plugin.name,
                status = stringResource(
                    R.string.plugins_tile_status,
                    pluginStateText(plugin),
                    pluginKindLabel(plugin),
                ),
                icon = Icons.Default.Build,
                dotColor = pluginDotColor(plugin),
                pulsing = plugin.running,
            ) to { onOpenPlugin(plugin) }
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
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
                        text = summary,
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
        }

        items(tiles) { (tile, onClick) ->
            HomeTile(tile = tile, onClick = onClick)
        }

        if (pluginCatalog.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.plugins_catalog_title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            pluginCatalog.forEach { entry ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PluginCatalogRow(
                        entry = entry,
                        busy = busy,
                        onInstall = { onInstall(entry) },
                    )
                }
            }
        }
    }
}

/** Zustand eines Plugins als Text (Aktiv/Bereit/Fehlt) fuer Kachel und Detailseite. */
@Composable
private fun pluginStateText(plugin: PluginInfo): String = when {
    !plugin.installed -> stringResource(R.string.plugins_state_missing)
    plugin.running && plugin.uptime > 0 ->
        stringResource(R.string.plugins_state_active_since, fmtDuration(plugin.uptime))
    plugin.running -> stringResource(R.string.plugins_state_active)
    else -> stringResource(R.string.plugins_state_ready)
}

/** Statuspunkt einer Plugin-Kachel: laeuft = gruen, bereit = grau, fehlt = gelb. */
private fun pluginDotColor(plugin: PluginInfo): Color = when {
    plugin.running -> statusColor(PcStatus.ONLINE)
    plugin.installed -> statusColor(PcStatus.UNKNOWN)
    else -> statusColor(PcStatus.ERROR)
}

/**
 * Detailseite eines Plugins: Zustand, Start/Stop, Entfernen und die letzten
 * Ausgabezeilen (`GET /plugins/{id}/output?lines=...`). Damit bleiben die
 * Aktionen der frueheren Karte in der Uebersicht erreichbar.
 *
 * Phase G4: Meldet der Agent ein Manifest-Schema (`GET /plugins/{id}/settings`),
 * erscheint zusaetzlich der Abschnitt "Einstellungen". Ohne Schema (404, alter
 * Agent, leeres Schema) bleibt er aus und die Seite ist wie bisher bedienbar.
 */
@Composable
private fun PluginDetailPage(
    plugin: PluginInfo?,
    output: String,
    busy: Boolean,
    autoClose: Boolean,
    onAutoCloseChange: (Boolean) -> Unit,
    settingsSchema: PluginSettingsSchema?,
    settingsValues: Map<String, String>,
    settingsNotice: String?,
    settingsError: String?,
    onSettingChange: (String, String) -> Unit,
    onSaveSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (plugin == null) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                text = stringResource(R.string.plugins_detail_missing),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    val autoCloseHint = if (autoClose) {
        stringResource(R.string.plugins_auto_close_off)
    } else {
        stringResource(R.string.plugins_auto_close_on)
    }
    val containerColor by animateColorAsState(
        targetValue = if (plugin.running) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "pluginDetailColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = containerColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = pluginKindLabel(plugin),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = pluginDotColor(plugin), pulsing = plugin.running)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pluginStateText(plugin),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Version und Rechte nur zeigen, wenn der Agent sie meldet.
                val extra = listOfNotNull(
                    stringResource(R.string.plugins_detail_version, plugin.version),
                    plugin.permissions.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.plugins_detail_permissions, it.joinToString(", "))
                    },
                ).joinToString(" · ")
                Text(
                    text = extra,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plugin.running) {
                Button(
                    enabled = !busy,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.plugins_stop)) }
            } else {
                Button(
                    enabled = !busy && plugin.installed,
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.plugins_start)) }
            }
            if (plugin.installed && !plugin.builtin) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = onRemove,
                ) { Text(stringResource(R.string.plugins_remove)) }
            }
        }

        // Der Schalter steht auch hier, weil er genau beim Start zaehlt.
        SettingsGroup {
            SettingsSwitchRow(
                icon = Icons.Default.Build,
                title = stringResource(R.string.plugins_auto_close),
                subtitle = autoCloseHint,
                checked = !autoClose,
                enabled = true,
                onCheckedChange = { keepOpen -> onAutoCloseChange(!keepOpen) },
            )
        }

        // Phase G4: Einstellungen aus dem Manifest-Schema (nur wenn vorhanden).
        if (settingsSchema != null && !settingsSchema.isEmpty) {
            PluginSettingsSection(
                schema = settingsSchema,
                values = settingsValues,
                busy = busy,
                notice = settingsNotice,
                error = settingsError,
                onChange = onSettingChange,
                onSave = onSaveSettings,
            )
        }

        Text(
            text = stringResource(R.string.plugins_detail_output),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = output.ifBlank { stringResource(R.string.plugins_detail_output_empty) },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
        }

        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.plugins_detail_refresh))
        }
    }
}

/**
 * Abschnitt "Einstellungen" einer Plugin-Detailseite (Phase G4). Die Felder
 * kommen aus dem Manifest-Schema des Agenten: Textfeld, Zahlenfeld mit
 * min/max-Pruefung, Schalter und Auswahl (Filter-Chips statt Menue, damit keine
 * zusaetzliche Bibliothek noetig ist). "Speichern" schickt alle Werte zusammen.
 */
@Composable
private fun PluginSettingsSection(
    schema: PluginSettingsSchema,
    values: Map<String, String>,
    busy: Boolean,
    notice: String?,
    error: String?,
    onChange: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    Text(
        text = stringResource(R.string.plugins_settings_title),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline,
    )
    SettingsDetailCard {
        schema.fields.forEach { field ->
            PluginSettingFieldRow(
                field = field,
                value = values[field.key] ?: field.defaultValue,
                enabled = !busy,
                onChange = { onChange(field.key, it) },
            )
        }
        Button(
            onClick = onSave,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.plugins_settings_save))
        }
        Text(
            text = stringResource(R.string.plugins_settings_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        // Rueckmeldung: gespeichert (primaer) oder abgelehnt (Fehlerfarbe).
        notice?.let {
            Text(text = it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Text(text = it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Ein Feld des Plugin-Schemas: Text, Zahl (mit Grenzen), Schalter oder Auswahl. */
@Composable
private fun PluginSettingFieldRow(
    field: PluginSettingField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    when (field.type) {
        PluginSettingType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(field.label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        PluginSettingType.NUMBER -> {
            val numberError = checkPluginNumber(field, value)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(field.label) },
                    singleLine = true,
                    enabled = enabled,
                    isError = numberError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (numberError != null) {
                    Text(
                        text = pluginNumberErrorText(field, numberError),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        PluginSettingType.SWITCH -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = field.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = value == "true",
                onCheckedChange = { onChange(if (it) "true" else "false") },
                enabled = enabled,
            )
        }

        PluginSettingType.SELECT -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = field.label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field.options.forEach { option ->
                    FilterChip(
                        selected = value == option,
                        enabled = enabled,
                        onClick = { onChange(option) },
                        label = { Text(option) },
                    )
                }
            }
        }
    }
}

/** Fehlertext eines Zahlenfelds (deutsche Texte aus `strings.xml`). */
@Composable
private fun pluginNumberErrorText(field: PluginSettingField, error: PluginNumberError): String =
    when (error) {
        PluginNumberError.EMPTY -> stringResource(R.string.plugins_settings_error_empty)
        PluginNumberError.NOT_A_NUMBER -> stringResource(R.string.plugins_settings_error_number)
        PluginNumberError.TOO_SMALL -> stringResource(
            R.string.plugins_settings_error_min,
            jsonScalarText(field.min),
        )

        PluginNumberError.TOO_LARGE -> stringResource(
            R.string.plugins_settings_error_max,
            jsonScalarText(field.max),
        )
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
 * Karte fuer ein Markt-Modul: erscheint gestaffelt (Kaskade) und animiert
 * die Auswahl-Farbe wie die Profil-Karten. Ein Tipp oeffnet die Detailseite.
 * Module, deren Feature-Code die App noch nicht kennt (SUPPORTED_MODULES),
 * zeigen einen Hinweis statt Buttons. Ist der Katalog neuer als die gemerkte
 * Version, erscheint "Update verfuegbar" plus Knopf (Phase F5).
 */
@Composable
private fun ModuleCard(
    module: CatalogModule,
    isInstalled: Boolean,
    installedVersion: Int?,
    index: Int,
    busy: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    val updateAvailable = moduleUpdateAvailable(module, installedVersion)
    val newerThanCatalog = catalogOlderThanInstalled(module, installedVersion)
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
            onClick = onOpen,
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
                    if (updateAvailable) ModuleUpdateBadge()
                    if (newerThanCatalog) {
                        Text(
                            text = stringResource(R.string.module_newer_than_catalog),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (module.id !in SUPPORTED_MODULES) {
                        Text(
                            text = stringResource(R.string.module_unsupported_hint),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                when {
                    module.id !in SUPPORTED_MODULES -> Unit // Hinweis genuegt
                    // Update zuerst: das Modul bleibt installiert, die Version wandert.
                    updateAvailable -> TextButton(enabled = !busy, onClick = onUpdate) {
                        Text(stringResource(R.string.module_action_update))
                    }
                    isInstalled -> TextButton(enabled = !busy, onClick = onUninstall) {
                        Text(stringResource(R.string.module_action_uninstall))
                    }
                    else -> TextButton(enabled = !busy, onClick = onInstall) {
                        Text(stringResource(R.string.module_action_install))
                    }
                }
            }
        }
    }
}

/** Kleiner Hinweis "Update verfuegbar" - auf der Karte und in der Detailseite. */
@Composable
private fun ModuleUpdateBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Text(
            text = stringResource(R.string.module_update_available),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Detailseite eines Markt-Moduls: Name, Beschreibung, Katalogversion, Status
 * ("Installiert (vN)" / "Nicht installiert"), was das Modul freischaltet und
 * genau eine Aktion (Installieren, Entfernen, Aktualisieren). Ist der Katalog
 * aelter als die Installation, gibt es bewusst keinen Knopf zum Zurueck.
 */
@Composable
private fun ModuleDetailPage(
    module: CatalogModule?,
    installed: Boolean,
    installedVersion: Int?,
    supported: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    if (module == null) {
        // Modul ist aus dem Katalog verschwunden - kein Absturz, nur Hinweis.
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = stringResource(R.string.module_detail_missing),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    val updateAvailable = moduleUpdateAvailable(module, installedVersion)
    val newerThanCatalog = catalogOlderThanInstalled(module, installedVersion)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = module.name,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = module.description.ifBlank { "Zusaetzliches PC-Modul." },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Status und Versionen: was ist installiert, was bietet der Katalog?
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (installed) {
                        stringResource(
                            R.string.module_status_installed,
                            installedVersion ?: MODULE_VERSION_FALLBACK
                        )
                    } else {
                        stringResource(R.string.module_status_not_installed)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.module_catalog_version, module.version),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Text(
            text = stringResource(R.string.module_features_title),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Text(text = moduleFeatureText(module.id), fontSize = 14.sp)

        if (updateAvailable) {
            ModuleUpdateBadge()
            Text(
                text = stringResource(
                    R.string.module_update_hint,
                    module.version,
                    installedVersion ?: MODULE_VERSION_FALLBACK
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (newerThanCatalog) {
            Text(
                text = stringResource(R.string.module_newer_than_catalog),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (!supported) {
            Text(
                text = stringResource(R.string.module_unsupported_hint),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        when {
            !supported -> Unit // Feature-Code fehlt noch, deshalb kein Knopf
            updateAvailable -> Button(
                onClick = onUpdate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(R.string.module_action_update)) }
            installed -> OutlinedButton(
                onClick = onUninstall,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(R.string.module_action_remove)) }
            else -> Button(
                onClick = onInstall,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(R.string.module_action_install)) }
        }

        Text(
            text = stringResource(R.string.module_detail_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
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
                    targetState = planLabelRes(activePlan),
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 })
                    },
                    label = "activePlan"
                ) { resId ->
                    Text(
                        text = stringResource(resId),
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
                            contentDescription = stringResource(R.string.delete_profile),
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
    HISTORY("Verlauf", "Aufbewahrung und Logzeilen"),
    LIVE_CARD("Live-Karte", "Statusleisten-Benachrichtigung"),
    RESET("Zurücksetzen", "Einstellungen zurücksetzen"),
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
    installedModules: Set<String>,
    onSettingsChange: (AppSettings) -> Unit,
    onModulesChange: (Set<String>) -> Unit,
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
                profiles = profiles,
                modules = installedModules,
                onOpenPage = { destination = it },
                onOpenWeb = onOpenWeb,
                onTestConnection = onTestConnection,
                onModeChange = onModeChange,
                onSettingsChange = onSettingsChange,
                onModulesChange = onModulesChange,
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
                            Text(stringResource(R.string.save_changes), fontSize = 16.sp)
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
                            Text(stringResource(R.string.test_connection), fontSize = 15.sp)
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

                SettingsDestination.HISTORY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SettingsDetailCard {
                            Text(
                                stringResource(R.string.settings_history_lines),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.settings_history_hint),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HISTORY_LINES_CHOICES.forEach { lines ->
                                    FilterChip(
                                        selected = settings.historyLines == lines,
                                        onClick = {
                                            onSettingsChange(settings.copy(historyLines = lines))
                                        },
                                        label = {
                                            Text(
                                                stringResource(R.string.settings_history_value, lines),
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        SettingsDetailCard {
                            Text(
                                stringResource(R.string.settings_history_rotate_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.settings_history_rotate),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

SettingsDestination.LIVE_CARD -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SettingsDetailCard {
                            Text("Live-Karte in der Statusleiste", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Dauerkarte \"PC ist an\" mit Herunterfahren/Ruhezustand-Knöpfen.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SettingsSwitchRow(icon = Icons.Default.Build, title = "Dauerkarte", subtitle = "PC ist an", checked = settings.onlineCard, enabled = true, onCheckedChange = { onSettingsChange(settings.copy(onlineCard = it)) })
                        }
                        SettingsDetailCard {
                            Text("Live-Benachrichtigungen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            SettingsSwitchRow(icon = Icons.Default.Build, title = "Downloads", subtitle = "", checked = settings.liveDownloads, enabled = true, onCheckedChange = { onSettingsChange(settings.copy(liveDownloads = it)) })
                            SettingsSwitchRow(icon = Icons.Default.Build, title = "Countdown", subtitle = "", checked = settings.liveCountdown, enabled = true, onCheckedChange = { onSettingsChange(settings.copy(liveCountdown = it)) })
                            SettingsSwitchRow(icon = Icons.Default.Build, title = "Warnungen", subtitle = "", checked = settings.liveWarnings, enabled = true, onCheckedChange = { onSettingsChange(settings.copy(liveWarnings = it)) })
                            SettingsSwitchRow(icon = Icons.Default.Build, title = "Startphase", subtitle = "", checked = settings.liveStarting, enabled = true, onCheckedChange = { onSettingsChange(settings.copy(liveStarting = it)) })
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                SettingsDestination.RESET -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SettingsDetailCard {
                            Text("Zurücksetzen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Setzt nur die App-Einstellungen zurück, keine Profile.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onSettingsChange(AppSettings()) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Alle Einstellungen zurücksetzen")
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
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
    profiles: List<Profile>,
    modules: Set<String>,
    onOpenPage: (SettingsDestination) -> Unit,
    onOpenWeb: () -> Unit,
    onTestConnection: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onModulesChange: (Set<String>) -> Unit,
) {
    // G1: Suchbegriff; gefiltert wird mit der reinen Funktion aus Settings.kt.
    var query by remember { mutableStateOf("") }
    // G2: Bereich, dessen Zuruecksetzen gerade bestaetigt werden soll.
    var resetArea by remember { mutableStateOf<SettingsArea?>(null) }
    var resetNotice by remember { mutableStateOf<SettingsArea?>(null) }
    // G3: Rueckmeldung der Datei-Aktionen und die zu bestaetigende Importdatei.
    var fileNotice by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<SettingsImportResult.Ok?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Rueckmeldung des Resets nach ein paar Sekunden wieder ausblenden.
    LaunchedEffect(resetNotice) {
        if (resetNotice != null) {
            delay(4000)
            resetNotice = null
        }
    }

    // G3 Export: System-Dateidialog (SAF), kein Speicher-Recht noetig.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            fileNotice = R.string.settings_export_cancelled
        } else {
            val text = pendingExport
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: return@runCatching false
                        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        true
                    }.getOrDefault(false)
                }
                fileNotice = if (written) {
                    R.string.settings_export_done
                } else {
                    R.string.settings_export_failed
                }
            }
        }
    }

    // G3 Import: tolerant lesen (parseSettingsImport); Profile bleiben aussen vor.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            fileNotice = R.string.settings_import_cancelled
        } else {
            scope.launch {
                val body = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (body == null) {
                    importError = context.getString(R.string.settings_import_unreadable)
                } else when (val result = parseSettingsImport(body)) {
                    is SettingsImportResult.Ok -> pendingImport = result
                    is SettingsImportResult.Failed -> importError = context.getString(
                        when (result.error) {
                            SettingsImportError.WRONG_VERSION -> R.string.settings_import_version
                            else -> R.string.settings_import_invalid
                        }
                    )
                }
            }
        }
    }

    // G1: Die Bereiche mit ihren Eintraegen. Gesucht wird in Titel, Untertitel
    // und Bereichsname (reine Funktion `searchSettings` in Settings.kt).
    val connectionSection = stringResource(R.string.settings_section_connection)
    val appearanceSection = stringResource(R.string.settings_section_appearance)
    val controlSection = stringResource(R.string.settings_section_control)
    val pluginsSection = stringResource(R.string.settings_section_plugins)
    val historySection = stringResource(R.string.settings_section_history)
    val updatesSection = stringResource(R.string.settings_section_updates)
    val dataSection = stringResource(R.string.settings_section_data)
    val aboutSection = stringResource(R.string.settings_section_about)

    fun searchEntry(key: String, title: String, subtitle: String, section: String) =
        SettingsSearchEntry(key = key, title = title, subtitle = subtitle, section = section)

    val profilesSubtitle = if (active.isComplete) {
        active.name + "  ·  " + active.host + ":" + active.port
    } else {
        active.name + "  ·  " + stringResource(R.string.settings_profiles_not_setup)
    }
    val simulationSubtitle = when (simulate) {
        true -> stringResource(R.string.settings_simulation_on)
        false -> stringResource(R.string.settings_simulation_off)
        null -> stringResource(R.string.settings_simulation_unknown)
    }

    // Bereich (fuer das Zuruecksetzen) plus seine Eintraege. `null` = Bereich
    // ohne eigenen Reset-Knopf (Verbindung, Updates, Daten, Ueber die App).
    val sections: List<Pair<SettingsArea?, SettingsSearchSection>> = listOf(
        null to SettingsSearchSection(
            title = connectionSection,
            entries = listOf(
                searchEntry(
                    "profiles",
                    stringResource(R.string.settings_profiles),
                    profilesSubtitle,
                    connectionSection,
                ),
                searchEntry(
                    "test_connection",
                    stringResource(R.string.settings_test_connection),
                    stringResource(R.string.settings_test_connection_sub),
                    connectionSection,
                ),
            ),
        ),
        SettingsArea.APPEARANCE to SettingsSearchSection(
            title = appearanceSection,
            entries = listOf(
                searchEntry(
                    "appearance",
                    stringResource(R.string.settings_appearance),
                    themeModeLabel(settings.themeMode),
                    appearanceSection,
                ),
                searchEntry(
                    "refresh",
                    stringResource(R.string.settings_refresh),
                    stringResource(R.string.settings_refresh_value, settings.refreshSeconds),
                    appearanceSection,
                ),
            ),
        ),
        SettingsArea.CONTROL to SettingsSearchSection(
            title = controlSection,
            entries = listOf(
                searchEntry(
                    "online_card",
                    stringResource(R.string.settings_online_card),
                    if (settings.onlineCard) {
                        stringResource(R.string.settings_online_card_on)
                    } else {
                        stringResource(R.string.settings_online_card_off)
                    },
                    controlSection,
                ),
                searchEntry(
                    "live_card",
                    "Live-Karte",
                    "Statusleisten-Benachrichtigung",
                    controlSection,
                ),
                searchEntry(
                    "reset",
                    "Zurücksetzen",
                    "Einstellungen zurücksetzen",
                    controlSection,
                ),
            ),
        ),
        SettingsArea.PLUGINS to SettingsSearchSection(
            title = pluginsSection,
            entries = listOf(
            ),
        ),
        SettingsArea.HISTORY to SettingsSearchSection(
            title = historySection,
            entries = listOf(
                searchEntry(
                    "history",
                    stringResource(R.string.settings_history),
                    stringResource(R.string.settings_history_value, settings.historyLines),
                    historySection,
                ),
            ),
        ),
        null to SettingsSearchSection(
            title = updatesSection,
            entries = listOf(
                searchEntry(
                    "updates",
                    stringResource(R.string.settings_updates),
                    "GitHub Releases · PC Steuerung " + versionName,
                    updatesSection,
                ),
            ),
        ),
        null to SettingsSearchSection(
            title = dataSection,
            entries = listOf(
                searchEntry(
                    "export",
                    stringResource(R.string.settings_export),
                    stringResource(R.string.settings_export_sub),
                    dataSection,
                ),
                searchEntry(
                    "import",
                    stringResource(R.string.settings_import),
                    stringResource(R.string.settings_import_sub),
                    dataSection,
                ),
            ),
        ),
        null to SettingsSearchSection(
            title = aboutSection,
            entries = listOf(
                searchEntry(
                    "about",
                    stringResource(R.string.settings_about),
                    "Version " + versionName,
                    aboutSection,
                ),
            ),
        ),
    )

    val visible = searchSettings(sections.map { it.second }, query)
    // Bereich eines sichtbaren Abschnitts (fuer den Reset-Knopf).
    val resetTargets = sections.associate { (area, section) -> section.title to area }

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

        // G1: Suchfeld ueber den Bereichen. Filtert Titel, Untertitel, Bereich.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.settings_search_clear),
                        )
                    }
                }
            },
        )

        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        visible.forEach { section ->
            val area = resetTargets[section.title]
            SettingsSectionLabel(section.title)
            SettingsGroup {
                section.entries.forEachIndexed { index, item ->
                    if (index > 0) SettingsGroupDivider()
                    when (item.key) {
                        "profiles" -> SettingsOverviewRow(
                            icon = Icons.Default.Settings,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.PROFILE) },
                        )

                        "test_connection" -> SettingsOverviewRow(
                            icon = Icons.Default.Refresh,
                            title = item.title,
                            subtitle = item.subtitle,
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

                        "appearance", "refresh" -> SettingsOverviewRow(
                            icon = if (item.key == "appearance") {
                                Icons.Default.Star
                            } else {
                                Icons.Default.Refresh
                            },
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.APPEARANCE) },
                        )

                        "online_card" -> SettingsSwitchRow(
                            icon = Icons.Default.Build,
                            title = item.title,
                            subtitle = item.subtitle,
                            checked = settings.onlineCard,
                            enabled = true,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(onlineCard = it))
                            },
                        )

                        "live_card" -> SettingsOverviewRow(
                            icon = Icons.Default.Build,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.LIVE_CARD) },
                        )

                        "reset" -> SettingsOverviewRow(
                            icon = Icons.Default.Refresh,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.RESET) },
                        )

                        "history" -> SettingsOverviewRow(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.HISTORY) },
                        )

                        "updates" -> SettingsOverviewRow(
                            icon = Icons.Default.Refresh,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.UPDATES) },
                        )

                        // G3: Inhalt vorbereiten, dann den System-Dialog oeffnen.
                        "export" -> SettingsOverviewRow(
                            icon = Icons.Default.Star,
                            title = item.title,
                            subtitle = item.subtitle,
                            showChevron = false,
                            onClick = {
                                pendingExport = settingsExportJson(
                                    settings = settings,
                                    modules = modules,
                                    profiles = profiles,
                                    exportedAt = LocalDateTime.now()
                                        .format(
                                            DateTimeFormatter.ofPattern(
                                                "yyyy-MM-dd HH:mm:ss",
                                                Locale.GERMANY,
                                            ),
                                        ),
                                )
                                fileNotice = 0
                                exportLauncher.launch(settingsExportFileName())
                            },
                        )

                        "import" -> SettingsOverviewRow(
                            icon = Icons.Default.Refresh,
                            title = item.title,
                            subtitle = item.subtitle,
                            showChevron = false,
                            onClick = {
                                fileNotice = 0
                                // Viele Dateimanager melden JSON als octet-stream,
                                // deshalb bewusst alle Dateien anbieten.
                                importLauncher.launch(arrayOf("*/*"))
                            },
                        )

                        "about" -> SettingsOverviewRow(
                            icon = Icons.Default.Info,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onOpenPage(SettingsDestination.ABOUT) },
                        )

                        else -> Unit
                    }
                }
                // G2: Zuruecksetzen je Bereich - nur Felder dieses Bereichs.
                if (area != null) {
                    SettingsGroupDivider()
                    SettingsResetRow(enabled = !busy, onClick = { resetArea = area })
                }
            }
            // Kurze Rueckmeldung im Bereich: Reset bzw. Export/Import.
            if (area != null && resetNotice == area) {
                Text(
                    text = stringResource(R.string.settings_reset_done, settingsAreaLabel(area)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (section.title == dataSection && fileNotice != 0) {
                Text(
                    text = stringResource(fileNotice),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // G2: Bestaetigung vor dem Zuruecksetzen (AlertDialog wie im Bestand).
    resetArea?.let { area ->
        AlertDialog(
            onDismissRequest = { resetArea = null },
            title = {
                Text(stringResource(R.string.settings_reset_title, settingsAreaLabel(area)))
            },
            text = { Text(stringResource(R.string.settings_reset_text, settingsAreaLabel(area))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Nur die Felder dieses Bereichs, sofort speichern.
                        onSettingsChange(resetSettingsArea(settings, area))
                        resetNotice = area
                        fileNotice = 0
                        resetArea = null
                    },
                ) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { resetArea = null }) {
                    Text(stringResource(R.string.settings_reset_cancel))
                }
            },
        )
    }

    // G3: Import erst nach Bestaetigung uebernehmen (Profile bleiben aussen vor).
    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.settings_import_title)) },
            text = { Text(stringResource(R.string.settings_import_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSettingsChange(imported.settings)
                        onModulesChange(imported.modules)
                        fileNotice = R.string.settings_import_done
                        pendingImport = null
                    },
                ) { Text(stringResource(R.string.settings_import_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.settings_reset_cancel))
                }
            },
        )
    }

    // G3: Die Datei war keine Einstellungsdatei (oder nicht lesbar).
    importError?.let { text ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.settings_import)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(stringResource(R.string.games_cancel))
                }
            },
        )
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
    /** false fuer reine Aktionen (Export/Import): kein Pfeil nach rechts. */
    showChevron: Boolean = true,
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
        } else if (showChevron) {
            Text(
                text = "›",
                fontSize = 28.sp,
                lineHeight = 28.sp,
                color = SettingsAccent,
            )
        }
    }
}

/**
 * Zeile "Zuruecksetzen" am Ende eines Bereichs (Phase G2). Der Knopf oeffnet
 * nur den Bestaetigungsdialog; gesetzt wird in [resetSettingsArea].
 */
@Composable
private fun SettingsResetRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(stringResource(R.string.settings_reset), fontSize = 13.sp)
        }
    }
}

/** Deutscher Name eines Einstellungs-Bereichs (Dialog und Rueckmeldung). */
@Composable
private fun settingsAreaLabel(area: SettingsArea): String = when (area) {
    SettingsArea.APPEARANCE -> stringResource(R.string.settings_area_appearance)
    SettingsArea.CONTROL -> stringResource(R.string.settings_area_control)
    SettingsArea.HISTORY -> stringResource(R.string.settings_area_history)
    SettingsArea.PLUGINS -> stringResource(R.string.settings_area_plugins)
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

    /** Systemwerte (CPU, RAM, GPU, Netzwerk, Datentraeger, Luefter, Temperaturen). */
    data object Sensors : Route
    data object Steam : Route
    data object Plugins : Route

    /** Detailseite eines Plugins (Zustand, Start/Stop, Entfernen, Ausgabe). */
    data class PluginDetail(val id: String) : Route

    /** Spiel-Optimierung: Spiele-Liste und Konfiguration (`GET /games`). */
    data object GameOptimization : Route
    data object Market : Route

    /** Detailseite eines Markt-Moduls (Status, freigeschaltete Funktion, Aktion). */
    data class ModuleDetail(val id: String) : Route
    data object Routines : Route
    data object History : Route
    data object Energy : Route
    data object Warnings : Route
    data object Settings : Route
}

/**
 * Ziel der Zurueck-Taste: Detailseiten fuehren zu ihrer Uebersicht zurueck,
 * alle anderen Aktionsseiten zur Kachel-Startseite.
 */
private fun backTarget(route: Route): Route = when (route) {
    is Route.PluginDetail -> Route.Plugins
    Route.GameOptimization -> Route.Plugins
    is Route.ModuleDetail -> Route.Market
    else -> Route.Home
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
    sensors: SensorsStatus?,
    games: List<GameEntry>,
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
    // Kurzstatus der Systemwerte-Kachel: nur echte Messwerte zeigen, sonst
    // "Nicht verfügbar" (kein Platzhalter, keine 0 %).
    val sensorsStatus = if (sensors == null ||
        (sensors.cpu.percent == null && sensors.ram.percent == null)
    ) {
        stringResource(R.string.sensors_unavailable)
    } else {
        stringResource(
            R.string.tile_sensors_status,
            sensorPercentText(sensors.cpu.percent),
            sensorPercentText(sensors.ram.percent),
        )
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
            dotColor = if (steam?.downloading == true) statusColor(PcStatus.ONLINE) else null,
            pulsing = steam?.downloading == true,
        ),
        // Systemwerte: Icons.Default.Memory liegt nur in material-icons-extended,
        // das die App bewusst nicht einbindet - daher das vorhandene Info-Symbol.
        HomeTileSpec(
            route = Route.Sensors,
            title = stringResource(R.string.tile_sensors),
            status = sensorsStatus,
            icon = Icons.Default.Info,
        ),
        HomeTileSpec(
            route = Route.GameOptimization,
            title = stringResource(R.string.tile_games),
            status = stringResource(R.string.tile_games_status, games.size),
            icon = Icons.Default.Star,
        ),
        HomeTileSpec(
            route = Route.Energy,
            title = stringResource(R.string.tile_energy),
            status = stringResource(planLabelRes(state.plan)),
            icon = Icons.Default.Star,
        ),
        HomeTileSpec(
            route = Route.Routines,
            title = stringResource(R.string.tile_routines),
            status = routineStatus,
            icon = Icons.Default.DateRange,
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
            route = Route.History,
            title = stringResource(R.string.tile_history),
            status = historyStatus,
            icon = Icons.AutoMirrored.Filled.List,
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

// ---------------------------------------------------------------------------
// Aktionsseite Systemwerte (`GET /sensors`)
//
// Sieben Unterkacheln in zwei Spalten. Jeder Block kann einzeln fehlen:
// dann steht dort "Nicht verfuegbar" (Text aus strings.xml), niemals eine 0.
// ---------------------------------------------------------------------------

/** Prozentwert als Text fuer Kachel und Seite; null = "Nicht verfuegbar". */
@Composable
private fun sensorPercentText(value: Double?): String = if (value == null) {
    stringResource(R.string.sensors_unavailable)
} else {
    stringResource(R.string.sensors_percent_value, fmtSensorPercent(value))
}

/** Groesse in MB mit Einheit aus der Ressourcendatei. */
@Composable
private fun sensorMbText(value: Long): String =
    stringResource(R.string.sensors_mb_value, fmtSensorMb(value))

/** Groesse in GB mit Einheit aus der Ressourcendatei. */
@Composable
private fun sensorGbText(value: Double): String =
    stringResource(R.string.sensors_gb_value, fmtSensorGb(value))

/** Durchsatz als Mbit/s mit Einheit aus der Ressourcendatei. */
@Composable
private fun sensorRateText(bytesPerSecond: Double): String =
    stringResource(R.string.sensors_rate_value, fmtSensorRate(bytesPerSecond))

/** Eine Unterkachel im Raster: Titel klein oben, Messwert darunter. */
@Composable
private fun SensorTile(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
            )
            content()
        }
    }
}

/** Grosser Messwert einer Unterkachel (grau, wenn kein Wert vorliegt). */
@Composable
private fun SensorValue(text: String, available: Boolean = true) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (available) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outline
        },
    )
}

/** Kleine Zusatzzeile (Einheit, Kerne, Name) einer Unterkachel. */
@Composable
private fun SensorDetail(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Duenner Fortschrittsbalken; ohne Prozentwert bleibt er weg. */
@Composable
private fun SensorBar(percent: Double?) {
    if (percent == null) return
    LinearProgressIndicator(
        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
    )
}

/** "Nicht verfuegbar" plus optionaler Grund des Agenten. */
@Composable
private fun SensorUnavailable(reason: String = "") {
    SensorValue(stringResource(R.string.sensors_unavailable), available = false)
    if (reason.isNotBlank()) SensorDetail(reason)
}

/** Zwei Unterkacheln nebeneinander, gleich hoch. */
@Composable
private fun SensorRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Inhalt der Systemwerte-Seite. [status] ist null, solange noch nichts geladen
 * wurde; ein leerer Status (Agent ohne `/sensors`, 404) zeigt nur den Hinweis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsPage(
    status: SensorsStatus?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
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
                    text = stringResource(R.string.sensors_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.sensors_refresh),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            when {
                status == null -> Text(
                    text = stringResource(R.string.sensors_loading),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline,
                )

                status.isEmpty -> {
                    Text(
                        text = stringResource(R.string.sensors_unavailable),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.sensors_unavailable_hint),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                else -> SensorTiles(status = status)
            }
        }
    }
}

/** Die sieben Unterkacheln in zwei Spalten; Datentraeger ueber die volle Breite. */
@Composable
private fun SensorTiles(status: SensorsStatus) {
    SensorRow {
        SensorCpuTile(status.cpu, Modifier.weight(1f))
        SensorRamTile(status.ram, Modifier.weight(1f))
    }
    SensorRow {
        SensorGpuTile(status.gpu, Modifier.weight(1f))
        SensorNetworkTile(status.network, Modifier.weight(1f))
    }
    SensorTile(title = stringResource(R.string.sensors_tile_disks)) {
        if (status.disks.isEmpty()) {
            SensorUnavailable()
        } else {
            status.disks.forEach { disk ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = disk.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    SensorValue(
                        text = if (disk.percent == null) {
                            stringResource(R.string.sensors_unavailable)
                        } else {
                            stringResource(
                                R.string.sensors_percent_value,
                                fmtSensorPercent(disk.percent),
                            )
                        },
                        available = disk.percent != null,
                    )
                }
                if (disk.freeGb != null && disk.totalGb != null) {
                    SensorDetail(
                        stringResource(
                            R.string.sensors_disk_free,
                            sensorGbText(disk.freeGb),
                            sensorGbText(disk.totalGb),
                        )
                    )
                }
                SensorBar(disk.percent)
            }
        }
    }
    SensorRow {
        SensorGroupTile(
            title = stringResource(R.string.sensors_tile_fans),
            group = status.fans,
            modifier = Modifier.weight(1f),
        )
        SensorGroupTile(
            title = stringResource(R.string.sensors_tile_temperatures),
            group = status.temperatures,
            modifier = Modifier.weight(1f),
        )
    }
}


@Composable
private fun SensorCpuTile(cpu: SensorCpu, modifier: Modifier = Modifier) {
    SensorTile(title = stringResource(R.string.sensors_tile_cpu), modifier = modifier) {
        if (!cpu.available) {
            SensorUnavailable(cpu.reason)
        } else {
            SensorValue(sensorPercentText(cpu.percent), available = cpu.percent != null)
            cpu.cores?.let { SensorDetail(stringResource(R.string.sensors_cores, it)) }
            SensorBar(cpu.percent)
        }
    }
}

@Composable
private fun SensorRamTile(ram: SensorRam, modifier: Modifier = Modifier) {
    SensorTile(title = stringResource(R.string.sensors_tile_ram), modifier = modifier) {
        if (!ram.available) {
            SensorUnavailable(ram.reason)
        } else {
            SensorValue(sensorPercentText(ram.percent), available = ram.percent != null)
            if (ram.usedMb != null && ram.totalMb != null) {
                SensorDetail(
                    stringResource(
                        R.string.sensors_ram_used,
                        sensorMbText(ram.usedMb),
                        sensorMbText(ram.totalMb),
                    )
                )
            }
            SensorBar(ram.percent)
        }
    }
}

@Composable
private fun SensorGpuTile(gpu: SensorGpu, modifier: Modifier = Modifier) {
    SensorTile(title = stringResource(R.string.sensors_tile_gpu), modifier = modifier) {
        if (!gpu.available) {
            SensorUnavailable(gpu.reason)
        } else {
            SensorValue(sensorPercentText(gpu.percent), available = gpu.percent != null)
            if (gpu.name.isNotBlank()) SensorDetail(gpu.name)
            if (gpu.memoryUsedMb != null && gpu.memoryTotalMb != null) {
                SensorDetail(
                    stringResource(
                        R.string.sensors_gpu_memory,
                        sensorMbText(gpu.memoryUsedMb),
                        sensorMbText(gpu.memoryTotalMb),
                    )
                )
            }
            SensorBar(gpu.percent)
        }
    }
}

@Composable
private fun SensorNetworkTile(network: SensorNetwork, modifier: Modifier = Modifier) {
    val unavailable = stringResource(R.string.sensors_unavailable)
    SensorTile(title = stringResource(R.string.sensors_tile_network), modifier = modifier) {
        if (!network.available) {
            SensorUnavailable(network.reason)
        } else {
            val hasRate = network.rxBps != null || network.txBps != null
            SensorValue(
                text = if (!hasRate) {
                    unavailable
                } else {
                    stringResource(
                        R.string.sensors_down_up,
                        network.rxBps?.let { sensorRateText(it) } ?: unavailable,
                        network.txBps?.let { sensorRateText(it) } ?: unavailable,
                    )
                },
                available = hasRate,
            )
            if (network.name.isNotBlank()) SensorDetail(network.name)
        }
    }
}

@Composable
private fun SensorGroupTile(
    title: String,
    group: SensorGroup,
    modifier: Modifier = Modifier,
) {
    SensorTile(title = title, modifier = modifier) {
        // Der Agent liefert hier aktuell nur die Verfuegbarkeit (available und
        // reason). Ohne Messwert bleibt es bei "Nicht verfügbar" - nie eine
        // erfundene Zahl.
        SensorUnavailable(group.reason)
    }
}

/**
 * Aktionsseite Warnungen (`GET /warnings`). Zeigt Regeln, aktive Warnungen
 * und Verlauf. Der Agent prueft die Schwellen (auch bei geschlossener App),
 * die App meldet jedes Ereignis genau einmal ueber den Foreground-Service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningsPage(
    status: WarningsStatus?,
    refreshing: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSaveRules: (List<WarningRule>) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.warnings_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.warnings_refresh),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            when {
                status == null -> Text(
                    text = stringResource(R.string.warnings_loading),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline,
                )

                status.isEmpty -> {
                    Text(
                        text = stringResource(R.string.warnings_unavailable),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.warnings_unavailable_hint),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                else -> WarningsContent(status = status, busy = busy, onSaveRules = onSaveRules)
            }
        }
    }
}

@Composable
private fun WarningsContent(
    status: WarningsStatus,
    busy: Boolean,
    onSaveRules: (List<WarningRule>) -> Unit,
) {
    // Warnregeln
    Text(
        text = stringResource(R.string.warnings_rules_title),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (status.rules.isEmpty()) {
        Text(
            text = stringResource(R.string.warnings_no_rules),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    } else {
        status.rules.forEach { rule ->
            WarningRuleRow(rule = rule)
        }
    }

    // Aktuell ausgeloest
    Text(
        text = stringResource(R.string.warnings_active_title),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (status.active.isEmpty()) {
        Text(
            text = stringResource(R.string.warnings_no_active),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    } else {
        status.active.forEach { alert ->
            WarningAlertRow(alert = alert, isActive = true)
        }
    }

    // Verlauf
    Text(
        text = stringResource(R.string.warnings_history_title),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (status.triggered.isEmpty()) {
        Text(
            text = stringResource(R.string.warnings_no_history),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    } else {
        status.triggered.forEach { alert ->
            WarningAlertRow(alert = alert, isActive = false)
        }
    }

    // Neue Regel anlegen
    WarningEditor(onSaveRules = onSaveRules, busy = busy)
}

@Composable
private fun WarningRuleRow(rule: WarningRule) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (rule.enabled) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.warnings_rule_active),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = stringResource(R.string.warnings_rule_inactive),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            val metricLabel = when (rule.metric) {
                "cpu_percent" -> stringResource(R.string.warnings_metric_cpu)
                "ram_percent" -> stringResource(R.string.warnings_metric_ram)
                "gpu_percent" -> stringResource(R.string.warnings_metric_gpu)
                "disk_free_percent" -> stringResource(R.string.warnings_metric_disk)
                else -> rule.metric
            }
            val opLabel = if (rule.op == ">") stringResource(R.string.warnings_op_gt)
            else if (rule.op == "<") stringResource(R.string.warnings_op_lt)
            else rule.op
            Text(
                text = stringResource(
                    R.string.warnings_rule_detail,
                    metricLabel,
                    opLabel,
                    rule.threshold,
                    rule.durationS,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarningAlertRow(alert: WarningAlert, isActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alert.message,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (alert.since.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.warnings_since, alert.since),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (alert.value != null && alert.threshold != null) {
                Text(
                    text = stringResource(
                        R.string.warnings_alert_detail,
                        alert.value,
                        alert.threshold,
                        alert.op,
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WarningEditor(
    onSaveRules: (List<WarningRule>) -> Unit,
    busy: Boolean,
) {
    var metric by remember { mutableStateOf("cpu_percent") }
    var op by remember { mutableStateOf(">") }
    var threshold by remember { mutableStateOf("90") }
    var duration by remember { mutableStateOf("30") }
    var enabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.warnings_new_rule),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = metric,
                onValueChange = { metric = it },
                label = { Text(stringResource(R.string.warnings_metric)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = op,
                onValueChange = { op = it },
                label = { Text(stringResource(R.string.warnings_op)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it },
                label = { Text(stringResource(R.string.warnings_threshold)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it },
                label = { Text(stringResource(R.string.warnings_duration)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.warnings_enabled),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Button(
            onClick = {
                val newRule = WarningRule(
                    id = "rule_${System.currentTimeMillis()}",
                    label = "Regel ${System.currentTimeMillis()}",
                    metric = metric,
                    op = op,
                    threshold = threshold.toDoubleOrNull() ?: 90.0,
                    durationS = duration.toIntOrNull() ?: 30,
                    enabled = enabled,
                )
                onSaveRules(listOf(newRule))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.warnings_add_rule))
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

// ---------------------------------------------------------------------------
// Aktionsseite Spiele-Optimierung (`GET /games`, `GET/POST /game-config`)
//
// Ausblenden wirkt ueber die Liste `hidden` der Konfiguration: das Spiel bleibt
// in der Suche, steht aber nur noch im Abschnitt "Ausgeblendet" und laesst sich
// dort wieder einblenden. Manuell hinzugefuegte Spiele liegen in `custom` und
// koennen zusaetzlich geloescht werden. Jede Aenderung ist ein kompletter
// Zustand: GET /game-config -> Feld aendern -> POST /game-config.
// ---------------------------------------------------------------------------

@Composable
private fun GameOptimizationPage(
    games: List<GameEntry>,
    config: GameConfig?,
    loading: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onCleanMemory: () -> Unit,
    onToggleHidden: (GameEntry) -> Unit,
    onAddManual: (String, String) -> Unit,
    onDeleteManual: (GameEntry) -> Unit,
    onKillChange: (String, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newExe by remember { mutableStateOf("") }
    var newKill by remember { mutableStateOf("") }

    val hiddenEntries = config?.hidden.orEmpty()
    val visible = games.filterNot { isHiddenGame(it, hiddenEntries) }
    val hidden = games.filter { isHiddenGame(it, hiddenEntries) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.games_section_found),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = stringResource(R.string.games_count, games.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.games_refresh),
                )
            }
        }

        Button(
            enabled = !busy,
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.games_scan))
        }

        Button(
            enabled = !busy,
            onClick = onCleanMemory,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("RAM bereinigen")
        }

        if (loading && games.isEmpty()) {
            Text(
                text = stringResource(R.string.games_loading),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (games.isEmpty()) {
            Text(
                text = stringResource(R.string.games_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        visible.forEach { game ->
            GameRow(
                game = game,
                hidden = false,
                busy = busy,
                configurable = config != null,
                onToggleHidden = { onToggleHidden(game) },
                onDelete = if (game.isManual) ({ onDeleteManual(game) }) else null,
            )
        }

        if (hidden.isNotEmpty()) {
            Text(
                text = stringResource(R.string.games_section_hidden),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
            )
            // Wichtig fuer die Bedienung: ausgeblendet heisst nicht geloescht.
            Text(
                text = stringResource(R.string.games_hidden_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            hidden.forEach { game ->
                GameRow(
                    game = game,
                    hidden = true,
                    busy = busy,
                    configurable = config != null,
                    onToggleHidden = { onToggleHidden(game) },
                    onDelete = if (game.isManual) ({ onDeleteManual(game) }) else null,
                )
            }
        }

        Text(
            text = stringResource(R.string.games_section_manual),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = stringResource(R.string.games_manual_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedButton(
            enabled = !busy,
            onClick = {
                newName = ""
                newExe = ""
                showAdd = true
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.games_manual_add))
        }

        Text(
            text = stringResource(R.string.games_section_kill),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = stringResource(R.string.games_kill_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        if (config == null) {
            Text(
                text = stringResource(R.string.games_config_unavailable),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            if (config.killList.isEmpty()) {
                Text(
                    text = stringResource(R.string.games_kill_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            config.killList.forEach { name ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !busy, onClick = { onKillChange(name, false) }) {
                        Text(stringResource(R.string.games_kill_remove))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newKill,
                    onValueChange = { newKill = it },
                    label = { Text(stringResource(R.string.games_kill_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !busy && newKill.isNotBlank(),
                    onClick = {
                        onKillChange(newKill, true)
                        newKill = ""
                    },
                ) { Text(stringResource(R.string.games_kill_add)) }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.games_manual_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.games_manual_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newExe,
                        onValueChange = { newExe = it },
                        label = { Text(stringResource(R.string.games_manual_exe)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newExe.isNotBlank(),
                    onClick = {
                        showAdd = false
                        onAddManual(newName, newExe)
                    },
                ) { Text(stringResource(R.string.games_manual_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.games_cancel))
                }
            },
        )
    }
}

/** Ein Spiel als Karte: Name, Quelle/Pfad, Laufstatus, Ausblenden und Loeschen. */
@Composable
private fun GameRow(
    game: GameEntry,
    hidden: Boolean,
    busy: Boolean,
    configurable: Boolean,
    onToggleHidden: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = game.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (game.running) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.games_running),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = listOf(game.source, game.exe)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && configurable, onClick = onToggleHidden) {
                    Text(
                        stringResource(if (hidden) R.string.games_show else R.string.games_hide)
                    )
                }
                if (onDelete != null) {
                    TextButton(enabled = !busy && configurable, onClick = onDelete) {
                        Text(stringResource(R.string.games_delete))
                    }
                }
            }
        }
    }
}

/**
 * Aktionsseite Routinen (Phase E).
 *
 * Liste aus `GET /routines`: Name, Kurztext des Ausloesers, `next_run`, die
 * Schritte und der Verlauf (aufklappbar). "Jetzt" loest
 * `POST /routines/{id}/run` aus, An/Aus ersetzt die Routine per `POST /routines`
 * (die Kennung bleibt gleich), "Loeschen" nutzt `DELETE /routines/{id}`.
 *
 * Neu/Bearbeiten laeuft ueber [RoutineEditorDialog] (1-3 Schritte, Bedingungen,
 * Pruefung vor dem Speichern). Vorlagen kommen aus `GET /routine-templates` und
 * werden nach einer Rueckfrage per `POST /routines` angelegt; kennt der Agent
 * die Route nicht (leere Liste), fehlt der Abschnitt ganz.
 */
@Composable
private fun RoutinesPage(
    routines: List<RoutineInfo>,
    templates: List<RoutineTemplate>,
    plugins: List<PluginInfo>,
    loading: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRun: (RoutineInfo) -> Unit,
    onToggle: (RoutineInfo) -> Unit,
    onDelete: (RoutineInfo) -> Unit,
    onSave: (RoutineDraft) -> Unit,
    onCreateFromTemplate: (RoutineTemplate) -> Unit,
) {
    // null = Dialog zu. Ein Entwurf ohne id legt neu an, mit id aendert er.
    var editorDraft by remember { mutableStateOf<RoutineDraft?>(null) }
    var templateToAdd by remember { mutableStateOf<RoutineTemplate?>(null) }
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

        Button(
            enabled = !busy,
            onClick = { editorDraft = RoutineDraft() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.routines_new))
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
                onEdit = { editorDraft = routineToDraft(routine) },
            )
        }

        // Vorlagen: nur wenn der Agent `GET /routine-templates` kennt.
        if (templates.isNotEmpty()) {
            Text(
                text = stringResource(R.string.routines_templates),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(R.string.routines_templates_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            templates.forEach { template ->
                RoutineTemplateRow(
                    template = template,
                    busy = busy,
                    onAdd = { templateToAdd = template },
                )
            }
        }
    }

    editorDraft?.let { draft ->
        RoutineEditorDialog(
            initial = draft,
            plugins = plugins,
            busy = busy,
            onDismiss = { editorDraft = null },
            onSave = { saved ->
                editorDraft = null
                onSave(saved)
            },
        )
    }

    templateToAdd?.let { template ->
        AlertDialog(
            onDismissRequest = { templateToAdd = null },
            title = { Text(stringResource(R.string.routines_template_confirm_title)) },
            text = { Text(stringResource(R.string.routines_template_confirm, template.name)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        templateToAdd = null
                        onCreateFromTemplate(template)
                    },
                ) { Text(stringResource(R.string.routines_template_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { templateToAdd = null }) {
                    Text(stringResource(R.string.routines_cancel))
                }
            },
        )
    }
}

/** Eine Vorlage als Zeile: Name, Ausloeser, Schritte und "Anlegen". */
@Composable
private fun RoutineTemplateRow(
    template: RoutineTemplate,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = template.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = routineTriggerText(template.trigger),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (template.actions.isNotEmpty()) {
                    Text(
                        text = template.actions.joinToString(" · ") { it.label },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            TextButton(enabled = !busy, onClick = onAdd) {
                Text(stringResource(R.string.routines_template_apply), fontSize = 13.sp)
            }
        }
    }
}

/** Eine Routine mit Ausloeser, Schritten, Verlauf und den Bedienknoepfen. */
@Composable
private fun RoutineCard(
    routine: RoutineInfo,
    busy: Boolean,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    val history = routine.recentHistory
    val conditions = routineConditionsText(routine.trigger.conditions)
    val nextRun = routineNextRunText(routine.nextRun)
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
            if (conditions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.routines_conditions_label, conditions),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
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
            if (nextRun.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.routines_next, nextRun),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // Verlauf: die letzten Laeufe, auf Wunsch aufgeklappt (max. 5).
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = { showHistory = !showHistory },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (showHistory) R.string.routines_history_hide else R.string.routines_history_show,
                            history.size,
                        ),
                        fontSize = 11.sp,
                    )
                }
                AnimatedVisibility(visible = showHistory) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        history.forEach { entry ->
                            Text(
                                text = listOf(entry.at, entry.text)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                TextButton(onClick = onEdit, enabled = !busy) {
                    Text(stringResource(R.string.routines_edit), fontSize = 13.sp)
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
 * Formular fuer eine Routine: Ausloeser (Zeit/Manuell/Ereignis), optionale
 * Bedingungen, 1-3 Schritte mit Wartezeit und Pruefung vor dem Speichern.
 * Herunterfahren ist nur als letzter Schritt erlaubt (Agent-Vertrag).
 */
@Composable
private fun RoutineEditorDialog(
    initial: RoutineDraft,
    plugins: List<PluginInfo>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoutineDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<RoutineValidationError?>(null) }
    val heading = stringResource(
        if (initial.id.isBlank()) R.string.routines_title_new else R.string.routines_title_edit
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value ->
                        draft = draft.copy(name = value)
                        error = null
                    },
                    label = { Text(stringResource(R.string.routines_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                RoutineEditorLabel(stringResource(R.string.routines_trigger))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ROUTINE_TRIGGER_TIME to R.string.routines_trigger_time,
                        ROUTINE_TRIGGER_MANUAL to R.string.routines_trigger_manual,
                        ROUTINE_TRIGGER_EVENT to R.string.routines_trigger_event,
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = draft.triggerType == type,
                            onClick = {
                                draft = draft.copy(triggerType = type)
                                error = null
                            },
                            label = { Text(stringResource(label), fontSize = 11.sp) },
                        )
                    }
                }

                when (draft.triggerType) {
                    ROUTINE_TRIGGER_TIME -> {
                        OutlinedTextField(
                            value = draft.at,
                            onValueChange = { value ->
                                draft = draft.copy(at = value.take(5))
                                error = null
                            },
                            label = { Text(stringResource(R.string.routines_time)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RoutineEditorLabel(stringResource(R.string.routines_days))
                        RoutineWeekdayPicker(selected = draft.days, enabled = !busy) { days ->
                            draft = draft.copy(days = days)
                        }
                    }

                    ROUTINE_TRIGGER_EVENT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                ROUTINE_EVENT_GAME_STOPPED to R.string.routines_event_game,
                                ROUTINE_EVENT_DOWNLOAD_DONE to R.string.routines_event_download,
                                ROUTINE_EVENT_IDLE to R.string.routines_event_idle,
                            ).forEach { (event, label) ->
                                FilterChip(
                                    selected = draft.event == event,
                                    onClick = {
                                        draft = draft.copy(event = event)
                                        error = null
                                    },
                                    label = { Text(stringResource(label), fontSize = 11.sp) },
                                )
                            }
                        }
                        if (draft.event == ROUTINE_EVENT_IDLE) {
                            OutlinedTextField(
                                value = draft.idleMinutes,
                                onValueChange = { value ->
                                    draft = draft.copy(
                                        idleMinutes = value.filter(Char::isDigit).take(3)
                                    )
                                    error = null
                                },
                                label = { Text(stringResource(R.string.routines_idle_minutes)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                RoutineEditorLabel(stringResource(R.string.routines_conditions))
                Text(
                    text = stringResource(R.string.routines_conditions_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                RoutineWeekdayPicker(
                    selected = draft.conditionDays,
                    enabled = !busy,
                ) { days -> draft = draft.copy(conditionDays = days) }
                RoutineSelectField(
                    label = stringResource(R.string.routines_conditions_plugin),
                    value = draft.conditionPlugin,
                    enabled = !busy,
                    options = listOf("" to stringResource(R.string.routines_any)) +
                        plugins.map { it.id to it.name.ifBlank { it.id } },
                ) { plugin -> draft = draft.copy(conditionPlugin = plugin) }
                RoutineSelectField(
                    label = stringResource(R.string.routines_conditions_plan),
                    value = draft.conditionPowerPlan,
                    enabled = !busy,
                    options = listOf("" to stringResource(R.string.routines_any)) +
                        ROUTINE_PLAN_KEYS.map { it to stringResource(planLabelRes(it)) },
                ) { plan -> draft = draft.copy(conditionPowerPlan = plan) }

                RoutineEditorLabel(stringResource(R.string.routines_steps))
                draft.actions.forEachIndexed { index, step ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.routines_step, index + 1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (draft.actions.size > 1) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        draft = draft.copy(
                                            actions = draft.actions
                                                .filterIndexed { i, _ -> i != index }
                                        )
                                        error = null
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.routines_step_remove),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        RoutineSelectField(
                            label = stringResource(R.string.routines_step_type),
                            value = step.type,
                            enabled = !busy,
                            options = ROUTINE_ACTION_TYPES.map { type ->
                                type to stringResource(routineActionTypeText(type))
                            },
                        ) { type ->
                            draft = updateRoutineStep(draft, index) { it.copy(type = type) }
                            error = null
                        }
                        when (step.type) {
                            ROUTINE_ACTION_POWER_PLAN -> RoutineSelectField(
                                label = stringResource(R.string.routines_step_plan),
                                value = step.plan,
                                enabled = !busy,
                                options = ROUTINE_PLAN_KEYS.map { it to stringResource(planLabelRes(it)) },
                            ) { plan ->
                                draft = updateRoutineStep(draft, index) { it.copy(plan = plan) }
                                error = null
                            }

                            ROUTINE_ACTION_SHUTDOWN -> OutlinedTextField(
                                value = step.delay,
                                onValueChange = { value ->
                                    draft = updateRoutineStep(draft, index) {
                                        it.copy(delay = value.filter(Char::isDigit).take(4))
                                    }
                                    error = null
                                },
                                label = { Text(stringResource(R.string.routines_step_delay)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = step.wait,
                            onValueChange = { value ->
                                draft = updateRoutineStep(draft, index) {
                                    it.copy(wait = value.filter(Char::isDigit).take(3))
                                }
                                error = null
                            },
                            label = { Text(stringResource(R.string.routines_step_wait)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (draft.actions.size < MAX_ROUTINE_STEPS) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            draft = draft.copy(actions = withNewRoutineStep(draft.actions))
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.routines_step_add), fontSize = 13.sp)
                    }
                }

                if (error != null) {
                    Text(
                        text = stringResource(routineErrorText(error!!)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val problem = routineValidationError(draft)
                    if (problem == null) onSave(draft) else error = problem
                },
            ) { Text(stringResource(R.string.routines_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routines_cancel)) }
        },
    )
}

/** Kleine Abschnitts-Ueberschrift im Formular. */
@Composable
private fun RoutineEditorLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Wochentage 1-7 als schmale Knoepfe (Mo..So). Mehrfachauswahl; ohne Auswahl
 * gilt "taeglich" bzw. "Bedingung egal".
 */
@Composable
private fun RoutineWeekdayPicker(
    selected: List<Int>,
    enabled: Boolean = true,
    onChange: (List<Int>) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ROUTINE_DAY_SHORT.forEachIndexed { index, label ->
            val day = index + 1
            val active = day in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    )
                    .clickable(enabled = enabled) { onChange(withRoutineDay(selected, day)) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Auswahlfeld fuer Werte aus einer festen Liste (Plugin, Energieplan,
 * Schritt-Art). Der Dialog zeigt den Klartext, gespeichert wird der Schluessel.
 */
@Composable
private fun RoutineSelectField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second
        ?: value.ifBlank { stringResource(R.string.routines_any) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = current,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/** Schluessel einer Schritt-Art -> Text im Formular. */
private fun routineActionTypeText(type: String): Int = when (type) {
    ROUTINE_ACTION_SHUTDOWN -> R.string.routines_action_shutdown
    ROUTINE_ACTION_HIBERNATE -> R.string.routines_action_hibernate
    else -> R.string.routines_action_power_plan
}

/** Beanstandung -> deutsche Meldung im Formular. */
private fun routineErrorText(error: RoutineValidationError): Int = when (error) {
    RoutineValidationError.NAME_MISSING -> R.string.routines_error_name
    RoutineValidationError.TIME_INVALID -> R.string.routines_error_time
    RoutineValidationError.EVENT_MISSING -> R.string.routines_error_event
    RoutineValidationError.IDLE_INVALID -> R.string.routines_error_idle
    RoutineValidationError.STEP_MISSING -> R.string.routines_error_step_missing
    RoutineValidationError.TOO_MANY_STEPS -> R.string.routines_error_step_count
    RoutineValidationError.SHUTDOWN_NOT_LAST -> R.string.routines_error_shutdown_last
    RoutineValidationError.PLAN_MISSING -> R.string.routines_error_plan
    RoutineValidationError.WAIT_INVALID -> R.string.routines_error_wait
    RoutineValidationError.DELAY_INVALID -> R.string.routines_error_delay
}

/**
 * Aktionsseite Verlauf (Phase F1/F2): Ereignisse aus `GET /events` mit
 * Filter-Chips, Textsuche und Zeitgruppen ("Heute/Gestern/Aelter") sowie das
 * Log des Agenten (`GET /log`). Nur lesend.
 *
 * - "Kopieren" legt wie bisher das Log in die Zwischenablage.
 * - "Exportieren" speichert den **gefilterten** Verlauf ueber den
 *   System-Dateidialog (`ActivityResultContracts.CreateDocument`).
 * - Filter, Suche, Gruppen und der Export-Text sind reine Funktionen in
 *   `History.kt` (Unit-Tests in `HistoryTest.kt`), kein Hintergrund-Takt.
 */
@Composable
private fun HistoryPage(
    events: List<AgentEvent>,
    logText: String,
    pcName: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
) {
    // F1: Filter, Suche und Zeitgruppen - alles reine Funktionen aus History.kt.
    var category by remember { mutableStateOf(HistoryCategory.ALL) }
    var query by remember { mutableStateOf("") }
    // 0 = keine Meldung, sonst die Ressourcen-ID des Hinweises unter den Knoepfen.
    var exportNotice by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Inhalt fuer den laufenden Export: der System-Dialog liefert nur die Uri zurueck.
    var pendingExport by remember { mutableStateOf("") }

    // F2: Speichern ueber den System-Dateidialog (SAF) - kein Speicher-Recht noetig.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            exportNotice = R.string.history_export_cancelled
        } else {
            val text = pendingExport
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: return@runCatching false
                        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        true
                    }.getOrDefault(false)
                }
                exportNotice = if (written) {
                    R.string.history_export_done
                } else {
                    R.string.history_export_failed
                }
            }
        }
    }

    val filtered = remember(events, category, query) {
        filterHistoryEvents(events, category, query)
    }
    val sections = remember(filtered) {
        // Nur die neuesten Eintraege anzeigen; lange Listen rendern sonst traege.
        groupHistoryEvents(filtered.take(HISTORY_EVENT_LIMIT), LocalDate.now())
    }

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

        // Filter-Chips nach Kategorie (F1). Die Zuordnung macht historyCategory().
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            historyFilterChips.forEach { chip ->
                FilterChip(
                    selected = category == chip,
                    onClick = { category = chip },
                    label = { Text(historyCategoryLabel(chip)) },
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.history_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.history_search_clear),
                        )
                    }
                }
            },
        )

        if (events.isNotEmpty()) {
            Text(
                text = stringResource(R.string.history_count, filtered.size, events.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        when {
            events.isEmpty() -> Text(
                text = stringResource(R.string.history_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            // Leerzustand je Filter (F1): Filter oder Suche ohne Treffer.
            sections.isEmpty() -> Text(
                text = stringResource(R.string.history_empty_filter),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            else -> sections.forEach { section ->
                Text(
                    text = historyGroupLabel(section.group),
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.outline,
                )
                section.events.forEach { event -> HistoryEventCard(event) }
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
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.history_copy)) }
            // F2: gefilterten Verlauf als Textdatei speichern (System-Dateidialog).
            OutlinedButton(
                onClick = {
                    pendingExport = historyExportText(
                        pcName = pcName,
                        exportedAt = historyExportTimestamp(LocalDateTime.now()),
                        events = filtered,
                    )
                    exportNotice = 0
                    exportLauncher.launch(historyExportFileName(LocalDate.now()))
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.history_export)) }
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text(stringResource(R.string.history_refresh)) }
        if (exportNotice != 0) {
            Text(
                text = stringResource(exportNotice),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Filter-Chips der Verlauf-Seite in fester Reihenfolge. */
private val historyFilterChips = listOf(
    HistoryCategory.ALL,
    HistoryCategory.CONTROL,
    HistoryCategory.ROUTINE,
    HistoryCategory.PLUGIN,
    HistoryCategory.ERROR,
)

/** Beschriftung eines Filter-Chips (Texte in `strings.xml`). */
@Composable
private fun historyCategoryLabel(category: HistoryCategory): String = when (category) {
    HistoryCategory.CONTROL -> stringResource(R.string.history_filter_control)
    HistoryCategory.ROUTINE -> stringResource(R.string.history_filter_routine)
    HistoryCategory.PLUGIN -> stringResource(R.string.history_filter_plugin)
    HistoryCategory.ERROR -> stringResource(R.string.history_filter_error)
    HistoryCategory.ALL, HistoryCategory.OTHER -> stringResource(R.string.history_filter_all)
}

/** Ueberschrift einer Zeitgruppe ("Heute", "Gestern", "Aelter"). */
@Composable
private fun historyGroupLabel(group: HistoryGroup): String = when (group) {
    HistoryGroup.TODAY -> stringResource(R.string.history_group_today)
    HistoryGroup.YESTERDAY -> stringResource(R.string.history_group_yesterday)
    HistoryGroup.OLDER -> stringResource(R.string.history_group_older)
}

/**
 * Eine Ereigniszeile im Verlauf. Fehler (`ok == false`) werden deutlich
 * hervorgehoben: Fehlerfarbe, Warnsymbol und das Kennzeichen "Fehler".
 */
@Composable
private fun HistoryEventCard(event: AgentEvent) {
    val failed = !event.ok
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (failed) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = event.action.ifBlank { HISTORY_EXPORT_PLACEHOLDER },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (failed) {
                    Text(
                        text = stringResource(R.string.history_filter_error),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
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


