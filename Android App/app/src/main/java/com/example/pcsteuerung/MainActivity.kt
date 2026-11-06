package com.example.pcsteuerung

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    AppRoot()
                }
            }
        }
    }
}

private const val TAG = "PCSteuerung"

data class AuthHeader(val name: String, val value: String)

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

data class Settings(
    val host: String,
    val port: String,
    val user: String = "",
    val pass: String = "",
    val mac: String = "",
    val broadcast: String = "255.255.255.255",
    val defaultDelay: Int = 60,
    val token: String = "",
) {
    val base: String get() = "http://$host:$port"
    val isComplete: Boolean get() = host.isNotBlank() && port.isNotBlank()

    /** Basic Auth, wenn Benutzername/Passwort gesetzt sind; sonst alter X-Token. */
    fun authHeader(): AuthHeader? = when {
        user.isNotBlank() && pass.isNotBlank() -> {
            val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
            AuthHeader("Authorization", "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP))
        }
        token.isNotBlank() -> AuthHeader("X-Token", token)
        else -> null
    }
}

enum class PcStatus { ONLINE, ERROR, OFFLINE, UNKNOWN }

private data class AgentState(
    val text: String = "Verbinde ...",
    val status: PcStatus = PcStatus.UNKNOWN,
    val plan: String = "?",
    val pending: Int = 0,
    val simulate: Boolean? = null,
)

private fun statusColor(status: PcStatus): Color = when (status) {
    PcStatus.ONLINE -> Color(0xFF4CAF50)
    PcStatus.ERROR -> Color(0xFFFFC107)
    PcStatus.OFFLINE, PcStatus.UNKNOWN -> Color(0xFF9E9E9E)
}

private fun fmtDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
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
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var settings by remember {
        mutableStateOf(
            Settings(
                host = prefs.getString("host", "").orEmpty(),
                port = prefs.getString("port", "8000").orEmpty(),
                user = prefs.getString("user", "").orEmpty(),
                pass = prefs.getString("pass", "").orEmpty(),
                mac = prefs.getString("mac", "").orEmpty(),
                broadcast = prefs.getString("broadcast", "255.255.255.255").orEmpty(),
                defaultDelay = prefs.getInt("default_delay", 60),
                token = prefs.getString("token", "").orEmpty(),
            )
        )
    }
    var showSetup by remember { mutableStateOf(!settings.isComplete) }

    fun persist(newSettings: Settings) {
        prefs.edit()
            .putString("host", newSettings.host)
            .putString("port", newSettings.port)
            .putString("user", newSettings.user)
            .putString("pass", newSettings.pass)
            .putString("mac", newSettings.mac)
            .putString("broadcast", newSettings.broadcast)
            .putInt("default_delay", newSettings.defaultDelay)
            .remove("token")
            .apply()
        settings = newSettings.copy(token = "")
    }

    if (showSetup) {
        SetupScreen(
            initial = settings,
            onSave = { persist(it); showSetup = false },
            onCancel = if (settings.isComplete) ({ showSetup = false }) else null,
        )
    } else {
        MainApp(settings = settings, onSave = { persist(it) })
    }
}

@Composable
fun SetupScreen(initial: Settings, onSave: (Settings) -> Unit, onCancel: (() -> Unit)?) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var mac by remember { mutableStateOf(initial.mac) }
    var broadcast by remember { mutableStateOf(initial.broadcast) }
    var defaultDelay by remember { mutableStateOf(initial.defaultDelay) }
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
                text = "Einrichten",
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
                        onValueChange = { pass = it },
                        label = { Text("Passwort") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Zugangsdaten einmalig in der Web-Oberflaeche am PC anlegen: http://localhost:8000/",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it },
                        label = { Text("MAC-Adresse (fuer WoL)") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
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
                    Button(
                        onClick = {
                            onSave(
                                Settings(
                                    host = host.trim(),
                                    port = port.trim(),
                                    user = user.trim(),
                                    pass = pass,
                                    mac = mac.trim(),
                                    broadcast = broadcast.trim().ifBlank { "255.255.255.255" },
                                    defaultDelay = defaultDelay,
                                )
                            )
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
fun MainApp(settings: Settings, onSave: (Settings) -> Unit) {
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
    val scope = rememberCoroutineScope()

    fun runAction(path: String, success: String) {
        scope.launch {
            message = "Sende ..."
            val result = withContext(Dispatchers.IO) {
                try {
                    httpCall(settings.base, path, settings.authHeader(), "POST")
                } catch (e: Exception) {
                    Log.e(TAG, "Aktion fehlgeschlagen: $path", e)
                    "Nicht erreichbar: ${e.message ?: "Fehler"}"
                }
            }
            val shown = if (result.startsWith("HTTP ") || result.startsWith("Nicht")) result else success
            message = shown
            refresh++
            delay(4000)
            if (message == shown) message = null
        }
    }

    fun doWake() {
        if (settings.mac.isBlank()) {
            message = "MAC-Adresse fehlt - bitte in den Einstellungen eintragen"
            return
        }
        scope.launch {
            message = "Sende Wake ..."
            val ok = withContext(Dispatchers.IO) {
                sendMagicPacket(settings.mac, settings.broadcast)
            }
            Log.d(TAG, "WoL an ${settings.mac} via ${settings.broadcast} -> $ok")
            message = if (ok) "Wake gesendet (direkt)" else "Wake fehlgeschlagen"
            delay(4000)
            if (message?.startsWith("Wake") == true) message = null
        }
    }

    fun loadLog() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    httpCall(settings.base, "/log?lines=200", settings.authHeader())
                } catch (e: Exception) {
                    "Nicht erreichbar: ${e.message ?: "Fehler"}"
                }
            }
            logText = text.trim().ifBlank { "Noch keine Einträge." }
            showLog = true
        }
    }

    LaunchedEffect(settings, refresh) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                try {
                    val body = httpCall(settings.base, "/status", settings.authHeader())
                    if (body.startsWith("HTTP ")) {
                        val hint = when {
                            body.startsWith("HTTP 401") -> "Login fehlt oder falsch"
                            body.startsWith("HTTP 403") -> "Zugriff verweigert"
                            else -> body
                        }
                        AgentState(hint, PcStatus.ERROR, "?", 0)
                    } else {
                        val plan = Regex("\"power_plan\":\"([^\"]*)\"")
                            .find(body)?.groupValues?.get(1) ?: "?"
                        val pending = Regex("\"pending_shutdown\":(\\d+)")
                            .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val simulate = Regex("\"simulate\":(true|false)")
                            .find(body)?.groupValues?.get(1) == "true"
                        val text = "Online · Plan: $plan" +
                            if (pending > 0) " · Aus in ${pending / 60} min" else ""
                        AgentState(text, PcStatus.ONLINE, plan, pending, simulate)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Status-Abfrage fehlgeschlagen", e)
                    AgentState("Nicht erreichbar: ${e.message ?: "Fehler"}", PcStatus.OFFLINE, "?", 0)
                }
            }
            state = next
            delay(5000)
        }
    }

    Scaffold(
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
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    label = { Text("Energie") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ControlTab(
                    state = state,
                    message = message,
                    onShutdown = { showShutdownConfirm = true },
                    onTimer = {
                        timerHours = ""
                        timerMinutes = ""
                        showTimer = true
                    },
                    onWake = ::doWake,
                    onHibernate = { runAction("/hibernate", "Ruhezustand ausgeloest") },
                    onAbort = { runAction("/abort", "Herunterfahren abgebrochen") },
                    onShowLog = ::loadLog,
                    onOpenSettings = { tab = 2 },
                )
                1 -> PlanTab(
                    activePlan = state.plan,
                    message = message,
                    onSelect = { key, label ->
                        state = state.copy(plan = key)
                        runAction("/power-plan?name=$key", "Energieplan: $label")
                    },
                )
                else -> SettingsTab(
                    settings = settings,
                    message = message,
                    simulate = state.simulate,
                    onModeChange = { real ->
                        if (real) {
                            showRealModeConfirm = true
                        } else {
                            state = state.copy(simulate = true)
                            runAction("/mode?simulate=true", "Simulationsmodus aktiviert")
                        }
                    },
                    onSave = onSave,
                )
            }
        }
    }

    if (showShutdownConfirm) {
        val delay = settings.defaultDelay
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = { Text("PC herunterfahren?") },
            text = {
                Text(
                    if (delay == 0) {
                        "Der PC faehrt sofort herunter."
                    } else {
                        "Der PC faehrt in $delay Sekunden herunter."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showShutdownConfirm = false
                    runAction(
                        "/shutdown?delay=$delay",
                        if (delay == 0) {
                            "Herunterfahren ausgeloest"
                        } else {
                            "Herunterfahren in $delay s geplant"
                        }
                    )
                }) { Text("Ja") }
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
                TextButton(onClick = {
                    val total = (timerHours.toIntOrNull() ?: 0) * 3600 +
                        (timerMinutes.toIntOrNull() ?: 0) * 60
                    if (total > 0) {
                        showTimer = false
                        runAction("/shutdown?delay=$total", "Herunterfahren in ${total / 60} min geplant")
                    } else {
                        message = "Bitte Stunden oder Minuten angeben"
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
                    "Achtung: Befehle werden wirklich ausgefuehrt - " +
                        "Herunterfahren, Ruhezustand und Energieplan wirken sofort."
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("Schliessen") }
            }
        )
    }
}

@Composable
private fun ControlTab(
    state: AgentState,
    message: String?,
    onShutdown: () -> Unit,
    onTimer: () -> Unit,
    onWake: () -> Unit,
    onHibernate: () -> Unit,
    onAbort: () -> Unit,
    onShowLog: () -> Unit,
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
                    text = "Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor(state.status), RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.text,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.simulate?.let { sim ->
                    Text(
                        text = if (sim) "Modus: SIMULATION (nur Log)" else "Modus: ECHT",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sim) Color(0xFFF9A825) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (state.status == PcStatus.ERROR) {
            Text(
                text = "Bitte Benutzername/Passwort in den Einstellungen pruefen.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Einstellungen oeffnen")
            }
        }

        val online = state.status == PcStatus.ONLINE || state.status == PcStatus.ERROR
        if (online) {
            Button(
                onClick = onShutdown,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("PC herunterfahren", fontSize = 18.sp)
            }
            OutlinedButton(
                onClick = onTimer,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Timer...", fontSize = 15.sp)
            }
        } else {
            Button(
                onClick = onWake,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("PC einschalten", fontSize = 18.sp)
            }
        }

        if (state.pending > 0) {
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
                    TextButton(onClick = onAbort) { Text("Abbrechen") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onHibernate,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Ruhezustand", fontSize = 14.sp) }
            OutlinedButton(
                onClick = onShowLog,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Verlauf", fontSize = 14.sp) }
        }

        message?.let { text ->
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanTab(
    activePlan: String,
    message: String?,
    onSelect: (String, String) -> Unit,
) {
    val plans = listOf(
        Triple("high", "Hoechstleistung", "Maximale Leistung"),
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
            text = "Aktiver Plan: " + (plans.firstOrNull { it.first == activePlan }?.second ?: activePlan),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.outline
        )

        plans.forEach { (key, label, description) ->
            val active = key == activePlan
            Button(
                onClick = { onSelect(key, label) },
                modifier = Modifier.fillMaxWidth().height(78.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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

        message?.let { text ->
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsTab(
    settings: Settings,
    message: String?,
    simulate: Boolean?,
    onModeChange: (Boolean) -> Unit,
    onSave: (Settings) -> Unit,
) {
    var host by remember(settings) { mutableStateOf(settings.host) }
    var port by remember(settings) { mutableStateOf(settings.port) }
    var user by remember(settings) { mutableStateOf(settings.user) }
    var pass by remember(settings) { mutableStateOf(settings.pass) }
    var mac by remember(settings) { mutableStateOf(settings.mac) }
    var broadcast by remember(settings) { mutableStateOf(settings.broadcast) }
    var defaultDelay by remember(settings) { mutableStateOf(settings.defaultDelay) }
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
            text = "Verbindung und Zugangsdaten",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.outline
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
            onValueChange = { pass = it },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it },
            label = { Text("MAC-Adresse (fuer WoL)") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
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
                        false -> "ECHT - Befehle werden wirklich ausgefuehrt."
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
                onSave(
                    Settings(
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

        Text(
            text = "Zugangsdaten einmalig in der Web-Oberflaeche am PC anlegen: http://localhost:8000/",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        message?.let { text ->
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
