package com.example.pcsteuerung

import android.content.Context
import android.os.Bundle
import android.util.Base64
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

data class AuthHeader(val name: String, val value: String)

/** Blockierender HTTP-Aufruf, immer aus Dispatchers.IO heraus verwenden. */
fun httpCall(base: String, path: String, auth: AuthHeader?, method: String = "GET"): String {
    val conn = URL(base + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (auth != null) conn.setRequestProperty(auth.name, auth.value)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
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
                token = prefs.getString("token", "").orEmpty(),
            )
        )
    }
    var showSetup by remember { mutableStateOf(!settings.isComplete) }

    if (showSetup) {
        SetupScreen(
            initial = settings,
            onSave = { newSettings ->
                prefs.edit()
                    .putString("host", newSettings.host)
                    .putString("port", newSettings.port)
                    .putString("user", newSettings.user)
                    .putString("pass", newSettings.pass)
                    .putString("mac", newSettings.mac)
                    .putString("broadcast", newSettings.broadcast)
                    .apply()
                settings = newSettings
                showSetup = false
            },
            onCancel = if (settings.isComplete) ({ showSetup = false }) else null,
        )
    } else {
        MainScreen(settings = settings, onOpenSetup = { showSetup = true })
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

enum class PcStatus { ONLINE, ERROR, OFFLINE, UNKNOWN }

@Composable
fun MainScreen(settings: Settings, onOpenSetup: () -> Unit) {
    var state by remember { mutableStateOf("Verbinde ...") }
    var status by remember { mutableStateOf(PcStatus.UNKNOWN) }
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmShutdown by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runAction(path: String, success: String) {
        scope.launch {
            message = "Sende ..."
            val result = withContext(Dispatchers.IO) {
                try {
                    httpCall(settings.base, path, settings.authHeader(), "POST")
                } catch (e: Exception) {
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

    LaunchedEffect(settings, refresh) {
        while (true) {
            val text = withContext(Dispatchers.IO) {
                try {
                    val body = httpCall(settings.base, "/status", settings.authHeader())
                    val plan = Regex("\"power_plan\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                    val pending = Regex("\"pending_shutdown\":(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    "Online · Plan: ${plan ?: "?"}" + if (pending > 0) " · Aus in ${pending / 60} min" else ""
                } catch (e: Exception) {
                    "Nicht erreichbar: ${e.message ?: "Fehler"}"
                }
            }
            status = when {
                text.startsWith("Nicht") -> PcStatus.OFFLINE
                text.startsWith("HTTP ") -> PcStatus.ERROR
                else -> PcStatus.ONLINE
            }
            state = text
            delay(5000)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PCSteuerung",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = settings.base,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = onOpenSetup) { Text("Einstellungen") }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Status",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = when (status) {
                                        PcStatus.ONLINE -> Color(0xFF4CAF50)
                                        PcStatus.ERROR -> Color(0xFFFFC107)
                                        PcStatus.OFFLINE, PcStatus.UNKNOWN -> Color(0xFF9E9E9E)
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { confirmShutdown = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("PC Herunterfahren", fontSize = 16.sp)
                }

                FilledTonalButton(
                    onClick = { runAction("/abort", "Herunterfahren abgebrochen") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Abbrechen", fontSize = 16.sp)
                }

                FilledTonalButton(
                    onClick = { runAction("/hibernate", "Ruhezustand ausgeloest") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Ruhezustand", fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = { showPlan = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Energieplan", fontSize = 16.sp)
                }

                Button(
                    onClick = {
                        if (settings.mac.isBlank()) {
                            message = "MAC-Adresse fehlt"
                        } else {
                            scope.launch {
                                message = "Sende Wake ..."
                                val ok = withContext(Dispatchers.IO) {
                                    sendMagicPacket(settings.mac, settings.broadcast)
                                }
                                message = if (ok) "Wake gesendet (direkt)" else "Wake fehlgeschlagen"
                                delay(4000)
                                if (message?.startsWith("Wake") == true) message = null
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("PC einschalten", fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = { showTimer = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Spaeter herunterfahren", fontSize = 16.sp)
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
    }

    if (confirmShutdown) {
        AlertDialog(
            onDismissRequest = { confirmShutdown = false },
            title = { Text("PC herunterfahren?") },
            text = { Text("Der PC fährt in 60 Sekunden herunter. Abbrechen ist jederzeit möglich.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShutdown = false
                    runAction("/shutdown?delay=60", "Herunterfahren in 60 s geplant")
                }) { Text("Ja") }
            },
            dismissButton = {
                TextButton(onClick = { confirmShutdown = false }) { Text("Nein") }
            }
        )
    }

    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Timer: Herunterfahren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 2, 4, 8).forEach { hours ->
                        TextButton(
                            onClick = {
                                showTimer = false
                                runAction(
                                    "/shutdown?delay=${hours * 3600}",
                                    "Herunterfahren in $hours h geplant"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("In $hours Stunden") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimer = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showPlan) {
        AlertDialog(
            onDismissRequest = { showPlan = false },
            title = { Text("Energieplan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "high" to "Hoechstleistung",
                        "balanced" to "Ausbalanciert",
                        "saver" to "Energiesparen"
                    ).forEach { (key, label) ->
                        TextButton(
                            onClick = {
                                showPlan = false
                                runAction("/power-plan?name=$key", "Energieplan: $label")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlan = false }) { Text("Abbrechen") }
            }
        )
    }
}
