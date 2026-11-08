package com.example.pcsteuerung

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val EXTRA_CONFIRM_SHUTDOWN = "confirm_shutdown"

class MainActivity : ComponentActivity() {
    private val confirmShutdownRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        confirmShutdownRequest.value =
            intent?.getBooleanExtra(EXTRA_CONFIRM_SHUTDOWN, false) == true
        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(
                        confirmShutdown = confirmShutdownRequest.value,
                        onConfirmConsumed = { confirmShutdownRequest.value = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONFIRM_SHUTDOWN, false)) {
            confirmShutdownRequest.value = true
        }
    }
}

private const val TAG = "PCSteuerung"

/** Blockierender HTTP-Aufruf, immer aus Dispatchers.IO heraus verwenden. */
fun httpCall(base: String, path: String, auth: AuthHeader?, method: String = "GET"): String {
    Log.d(TAG, "HTTP $method ${base + path}")
    val conn = URL(base + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (auth != null) conn.setRequestProperty(auth.name, auth.value)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        Log.d(TAG, "HTTP $method ${base + path} -> $code")
        return if (code in 200..299) body else "HTTP $code: $body"
    } finally {
        conn.disconnect()
    }
}

private fun statusColor(status: PcStatus): Color = when (status) {
    PcStatus.ONLINE -> Color(0xFF4CAF50)
    PcStatus.ERROR -> Color(0xFFFFC107)
    PcStatus.OFFLINE, PcStatus.UNKNOWN -> Color(0xFF9E9E9E)
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
fun AppRoot(confirmShutdown: Boolean = false, onConfirmConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileStore.load(context)) }
    var activeId by remember { mutableStateOf(ProfileStore.activeId(context)) }

    fun persist(newProfiles: List<Profile>, newActiveId: String?) {
        ProfileStore.save(context, newProfiles, newActiveId)
        profiles = newProfiles
        activeId = newActiveId
    }

    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    if (active == null || !active.isComplete) {
        SetupScreen(
            profile = active ?: Profile(id = ProfileStore.newId(), name = "PC"),
            onSave = { saved ->
                val others = profiles.filter { it.id != saved.id }
                persist(others + saved, saved.id)
            },
            onCancel = if (active != null && profiles.size > 1) {
                { persist(profiles, profiles.firstOrNull { it.id != active.id }?.id) }
            } else null,
        )
    } else {
        MainApp(
            profiles = profiles,
            activeId = active.id,
            onProfilesChange = ::persist,
            startWithShutdownConfirm = confirmShutdown,
            onConfirmConsumed = onConfirmConsumed,
        )
    }
}

@Composable
fun SetupScreen(profile: Profile, onSave: (Profile) -> Unit, onCancel: (() -> Unit)?) {
    var name by remember { mutableStateOf(profile.name) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port) }
    var user by remember { mutableStateOf(profile.user) }
    var pass by remember { mutableStateOf(profile.pass) }
    var mac by remember { mutableStateOf(profile.mac) }
    var broadcast by remember { mutableStateOf(profile.broadcast) }
    var defaultDelay by remember { mutableStateOf(profile.defaultDelay) }
    var showPass by remember { mutableStateOf(false) }
    var pass2 by remember { mutableStateOf(profile.pass) }
    var formError by remember { mutableStateOf<String?>(null) }
    val valid = host.isNotBlank() && port.toIntOrNull() != null

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
                text = if (profile.isComplete) "Profil bearbeiten" else "Einrichten",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Einmal eingeben, danach startet die App direkt.",
                fontSize = 16.sp,
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
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (z. B. Wohnzimmer-PC)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP des PCs") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = port.toIntOrNull() == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it; formError = null },
                        label = { Text("MAC-Adresse (für WoL)") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        isError = formError?.startsWith("MAC") == true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = broadcast,
                        onValueChange = { broadcast = it },
                        label = { Text("Broadcast-Adresse (WoL)") },
                        placeholder = { Text("255.255.255.255") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Standard-Timer beim Herunterfahren",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    DelayChips(selected = defaultDelay, onSelect = { defaultDelay = it })
                    formError?.let {
                        Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            when {
                                pass.isNotEmpty() && pass != pass2 -> {
                                    formError = "Passwörter stimmen nicht überein"
                                }
                                !isValidMac(mac) -> {
                                    formError = "MAC-Format: AA:BB:CC:DD:EE:FF"
                                }
                                else -> {
                                    formError = null
                                    onSave(
                                        profile.copy(
                                            name = name.trim().ifBlank { "PC" },
                                            host = host.trim(),
                                            port = port.trim(),
                                            user = user.trim(),
                                            pass = pass,
                                            mac = mac.trim(),
                                            broadcast = broadcast.trim().ifBlank { "255.255.255.255" },
                                            defaultDelay = defaultDelay,
                                        )
                                    )
                                }
                            }
                        },
                        enabled = valid,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Speichern", fontSize = 16.sp)
                    }
                    if (onCancel != null) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Abbrechen")
                        }
                    }
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
    startWithShutdownConfirm: Boolean = false,
    onConfirmConsumed: () -> Unit = {},
) {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    var tab by remember { mutableStateOf(0) }
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
    var lastWidgetText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Marktplatz-Zustand: installierte Module sind persistent, der Katalog
    // startet lokal und wird beim ersten Start vom GitHub-Repo aufgefrischt.
    var installedModules by remember { mutableStateOf(ModuleStore.load(context)) }
    var catalogModules by remember { mutableStateOf(defaultCatalog()) }
    var catalogSource by remember { mutableStateOf("Lokal (Fallback)") }
    var catalogRefresh by remember { mutableStateOf(0) }

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

    LaunchedEffect(startWithShutdownConfirm) {
        if (startWithShutdownConfirm) {
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
            } finally {
                busy = false
            }
        }
    }

    fun loadLog() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    try {
                        httpCall(active.base, "/log?lines=200", active.authHeader())
                    } catch (e: Exception) {
                        "Nicht erreichbar: ${e.message ?: "Fehler"}"
                    }
                }
                logText = text.trim().ifBlank { "Noch keine Einträge." }
                showLog = true
            } finally {
                busy = false
            }
        }
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

    LaunchedEffect(active, refresh) {
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
                val widgetText = when (next.status) {
                    PcStatus.ONLINE -> "${active.name} an · ${planLabel(next.plan)}"
                    PcStatus.OFFLINE -> "${active.name} aus"
                    PcStatus.ERROR -> "Login prüfen"
                    PcStatus.UNKNOWN -> "Bereit"
                }
                if (widgetText != lastWidgetText) {
                    lastWidgetText = widgetText
                    WidgetProvider.updateAll(context, widgetText)
                }
                delay(5000)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Steuerung") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text("Markt+") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    label = { Text("Energie") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 40 }) togetherWith
                        fadeOut(tween(150))
                },
                label = "tabContent"
            ) { page ->
                when (page) {
                    0 -> ControlTab(
                        profiles = profiles,
                        active = active,
                        state = state,
                        busy = busy,
                        installed = installedModules,
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
                        onOpenMarket = { tab = 1 },
                        onOpenSettings = { tab = 3 },
                    )
                    1 -> MarketplaceTab(
                        modules = catalogModules,
                        installed = installedModules,
                        source = catalogSource,
                        busy = busy,
                        onRefresh = { catalogRefresh++ },
                        onInstall = ::installModule,
                        onUninstall = ::uninstallModule,
                    )
                    2 -> PlanTab(
                        activePlan = state.plan,
                        busy = busy,
                        onSelect = { key, label ->
                            state = state.copy(plan = key)
                            runAction("/power-plan?name=$key", "Energieplan: $label")
                        },
                    )
                    else -> SettingsTab(
                        profiles = profiles,
                        active = active,
                        simulate = state.simulate,
                        busy = busy,
                        onSwitch = ::switchProfile,
                        onAdd = ::addProfile,
                        onDelete = ::deleteProfile,
                        onSave = ::saveProfile,
                        onTestConnection = ::testConnection,
                        onModeChange = { real ->
                            if (real) {
                                showRealModeConfirm = true
                            } else {
                                state = state.copy(simulate = true)
                                runAction("/mode?simulate=true", "Simulationsmodus aktiviert")
                            }
                        },
                    )
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
                    }) + "\n\nAbbrechen ist danach im Steuerungs-Tab möglich."
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
                TextButton(enabled = !busy, onClick = { loadLog() }) { Text("Aktualisieren") }
            }
        )
    }
}

@Composable
private fun ControlTab(
    profiles: List<Profile>,
    active: Profile,
    state: AgentState,
    busy: Boolean,
    installed: Set<String>,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PC Steuerung",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

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
                    text = "Status · ${active.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
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
                            "Modus: SIMULATION (nur Log)"
                        } else {
                            "Modus: ECHT - Befehle werden ausgeführt!"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = modeColor
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.simulate == false,
            enter = fadeIn(tween(240)) + expandVertically(tween(320)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(320))
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "ECHT-MODUS AKTIV - Befehle werden wirklich ausgeführt!",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (state.status == PcStatus.ERROR) {
            Text(
                text = "Bitte Benutzername/Passwort in den Einstellungen prüfen.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Einstellungen öffnen")
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
                            onClick = onShutdown,
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
                            visible = state.pending > 0,
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
                                        text = "Geplantes Herunterfahren in ${fmtDuration(state.pending)}",
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
                                    onClick = onHibernate,
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
                                onClick = onHibernate,
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
                    Button(
                        onClick = onWake,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("PC einschalten", fontSize = 18.sp)
                    }
                }

                PcStatus.ERROR, PcStatus.UNKNOWN -> Unit
            }
        }

    }
}

/**
 * Marktplatz: An/Aus, Status, Ruhezustand und Energieplan sind Basis und
 * immer gratis dabei. Zusaetze (Module) werden hier installiert; der Katalog
 * stammt aus dem GitHub-Repo, offline greift ein lokaler Fallback.
 */
@Composable
private fun MarketplaceTab(
    modules: List<CatalogModule>,
    installed: Set<String>,
    source: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onInstall: (CatalogModule) -> Unit,
    onUninstall: (CatalogModule) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Markt+",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "An/Aus, Status, Ruhezustand und Energieplan sind immer gratis dabei. " +
                "Wenn du mehr willst, hol dir die Extras hier.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.outline
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Katalog: $source",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            TextButton(enabled = !busy, onClick = onRefresh) {
                Text("Aktualisieren")
            }
        }

        if (modules.isEmpty()) {
            Text(
                text = "Katalog ist leer - bitte spaeter aktualisieren.",
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
                    Text(
                        text = module.name + if (isInstalled) " (installiert)" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
        Text(
            text = "Aktiver Plan: " + planLabel(activePlan),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.outline
        )

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
            Button(
                onClick = { onSelect(key, label) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(78.dp),
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

@Composable
private fun SettingsTab(
    profiles: List<Profile>,
    active: Profile,
    simulate: Boolean?,
    busy: Boolean,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onSave: (Profile) -> Unit,
    onTestConnection: () -> Unit,
    onModeChange: (Boolean) -> Unit,
) {
    var name by remember(active) { mutableStateOf(active.name) }
    var host by remember(active) { mutableStateOf(active.host) }
    var port by remember(active) { mutableStateOf(active.port) }
    var user by remember(active) { mutableStateOf(active.user) }
    var pass by remember(active) { mutableStateOf(active.pass) }
    var mac by remember(active) { mutableStateOf(active.mac) }
    var broadcast by remember(active) { mutableStateOf(active.broadcast) }
    var defaultDelay by remember(active) { mutableStateOf(active.defaultDelay) }
    var showPass by remember { mutableStateOf(false) }
    var macError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val valid = host.isNotBlank() && port.toIntOrNull() != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Einstellungen",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "PC-Profile",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        profiles.forEachIndexed { index, p ->
            ProfileCard(
                profile = p,
                isActive = p.id == active.id,
                index = index,
                deletable = profiles.size > 1,
                onSwitch = { onSwitch(p.id) },
                onDelete = { onDelete(p.id) }
            )
        }

        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Neuen PC hinzufügen")
        }

        HorizontalDivider()

        Text(
            text = "Profil bearbeiten: ${active.name}",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; saved = false },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; saved = false },
            label = { Text("IP des PCs") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it; saved = false },
            label = { Text("Port") },
            singleLine = true,
            isError = port.toIntOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it; saved = false },
            label = { Text("Benutzername") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it; macError = null; saved = false },
            label = { Text("MAC-Adresse (für WoL)") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
            isError = macError != null,
            modifier = Modifier.fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Standard-Timer beim Herunterfahren",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline
        )
        DelayChips(selected = defaultDelay, onSelect = { defaultDelay = it; saved = false })

        Text(
            text = "Debug-Modus",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Echtzeitmodus", fontSize = 15.sp)
                Text(
                    text = when (simulate) {
                        true -> "Simulation aktiv - Befehle werden nur geloggt."
                        false -> "ECHT - Befehle werden wirklich ausgeführt."
                        null -> "Modus unbekannt."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = simulate == false,
                enabled = simulate != null,
                onCheckedChange = { real -> onModeChange(real) }
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
                        )
                    )
                    saved = true
                }
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Speichern", fontSize = 16.sp)
        }
        if (saved) {
            Text(
                text = "Gespeichert.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedButton(
            onClick = onTestConnection,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Verbindung testen", fontSize = 15.sp)
        }

        Text(
            text = "Zugangsdaten einmalig in der Web-Oberfläche am PC anlegen: http://localhost:8000/",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

    }
}
