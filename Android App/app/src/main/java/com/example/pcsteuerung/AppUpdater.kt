package com.example.pcsteuerung

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val RELEASE_API =
    "https://api.github.com/repos/ege16tan/pc-steuerung/releases/latest"
private const val APK_ASSET = "app-release.apk"
private const val SHIZUKU_REQUEST_CODE = 4217

private data class AppRelease(
    val tag: String,
    val notes: String,
    val downloadUrl: String,
    val sha256: String?,
)

private object GithubReleases {
    fun latest(): AppRelease {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PC-Steuerung-Android-Updater")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub antwortet mit HTTP ${connection.responseCode}.")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val asset = release.getJSONArray("assets")
                .let { assets -> (0 until assets.length()).map(assets::getJSONObject) }
                .firstOrNull { it.optString("name") == APK_ASSET }
                ?: error("In diesem GitHub-Release fehlt app-release.apk.")
            return AppRelease(
                tag = release.getString("tag_name"),
                notes = release.optString("body").trim(),
                downloadUrl = asset.getString("browser_download_url"),
                sha256 = asset.optString("digest").removePrefix("sha256:")
                    .takeIf { it.length == 64 },
            )
        } finally {
            connection.disconnect()
        }
    }

    fun isNewer(remote: String, installed: String): Boolean =
        compareVersions(remote, installed) > 0

    private fun compareVersions(first: String, second: String): Int {
        fun parts(value: String): Pair<List<Int>, Boolean> {
            val clean = value.trim().removePrefix("v")
            val core = clean.substringBefore('-')
            val numbers = core.split('.').map { it.toIntOrNull() ?: 0 }
            return numbers to !clean.contains('-')
        }
        val (a, stableA) = parts(first)
        val (b, stableB) = parts(second)
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return stableA.compareTo(stableB)
    }

    fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "update.apk")
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PC-Steuerung-Android-Updater")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("APK-Download fehlgeschlagen (HTTP ${connection.responseCode}).")
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !actualHash.equals(release.sha256, ignoreCase = true)) {
                file.delete()
                error("Die Prüfsumme der APK stimmt nicht mit GitHub überein.")
            }
            onProgress(1f)
            return file
        } finally {
            connection.disconnect()
        }
    }
}

private object ShizukuApkInstaller {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(context: Context, apk: File, onResult: (String) -> Unit) {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Gemeinsamer Speicher ist nicht verfügbar.")
        if (!externalDir.exists()) externalDir.mkdirs()
        val shellReadableApk = File(externalDir, "pc-steuerung-update.apk")
        apk.inputStream().use { input ->
            shellReadableApk.outputStream().use(input::copyTo)
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ShizukuInstallService::class.java)
        ).daemon(false).tag("pc-steuerung-updater").version(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val installer = IUpdateInstaller.Stub.asInterface(service)
                Thread {
                    val message = runCatching {
                        installer.install(shellReadableApk.absolutePath)
                    }.getOrElse { "Installation über Shizuku fehlgeschlagen: ${it.message}" }
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    mainHandler.post { onResult(message) }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mainHandler.post { onResult("Shizuku-Verbindung wurde beendet.") }
            }
        }
        Shizuku.bindUserService(args, connection)
    }
}

@Composable
internal fun AppUpdaterPanel(
    versionName: String,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Noch nicht nach Updates gesucht.") }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var permissionGranted by remember {
        mutableStateOf(runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false))
    }
    var waitingForShizukuPermission by remember { mutableStateOf(false) }
    var pendingShizukuApk by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                if (permissionGranted) {
                    pendingShizukuApk?.let { file ->
                        waitingForShizukuPermission = false
                        ShizukuApkInstaller.install(context, file) { result -> status = result }
                    }
                } else {
                    waitingForShizukuPermission = false
                    status = "Shizuku-Berechtigung wurde nicht erteilt."
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun installWithSelectedMethod(file: File) {
        when (settings.updateInstallMethod) {
            UpdateInstallMethod.SYSTEM -> {
                try {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                        status = "Bitte Installationen aus dieser Quelle erlauben und danach erneut tippen."
                        return
                    }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    status = "Android-Installationsdialog wurde geöffnet."
                } catch (error: Exception) {
                    status = "System-Installation konnte nicht geöffnet werden: ${error.message}"
                }
            }
            UpdateInstallMethod.SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    status = "Shizuku läuft nicht. Bitte Shizuku starten und erneut versuchen."
                    return
                }
                if (!permissionGranted) {
                    pendingShizukuApk = file
                    waitingForShizukuPermission = true
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                    return
                }
                ShizukuApkInstaller.install(context, file) { result -> status = result }
                status = "Installation über Shizuku wird gestartet …"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text(
            text = "App-Update",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text("Prüft GitHub Releases und lädt die APK direkt aus dem neuesten stabilen Release.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline)

        Text("Installationsmethode", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = settings.updateInstallMethod == UpdateInstallMethod.SYSTEM,
                onClick = {
                    onSettingsChange(settings.copy(updateInstallMethod = UpdateInstallMethod.SYSTEM))
                },
            )
            Text("Android-Systemdialog", modifier = Modifier.weight(1f), fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = settings.updateInstallMethod == UpdateInstallMethod.SHIZUKU,
                onClick = {
                    onSettingsChange(settings.copy(updateInstallMethod = UpdateInstallMethod.SHIZUKU))
                },
            )
            Text("Shizuku", modifier = Modifier.weight(1f), fontSize = 13.sp)
        }
        if (settings.updateInstallMethod == UpdateInstallMethod.SHIZUKU) {
            val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            Text(
                when {
                    !running -> "Shizuku muss laufen und dieser App die Berechtigung erteilen."
                    permissionGranted -> "Shizuku ist verbunden und autorisiert."
                    else -> "Shizuku läuft; beim Installieren wird die Berechtigung angefragt."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    checking = true
                    release = null
                    apkFile = null
                    status = "Suche auf GitHub …"
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { GithubReleases.latest() } }
                            .onSuccess { found ->
                                release = found
                                status = if (GithubReleases.isNewer(found.tag, versionName)) {
                                    "Update verfügbar: ${found.tag}"
                                } else {
                                    "Du verwendest ${versionName}; aktuelles GitHub-Release: ${found.tag}."
                                }
                            }
                            .onFailure { status = "Update-Prüfung fehlgeschlagen: ${it.message}" }
                        checking = false
                    }
                },
                enabled = !checking && !downloading,
                modifier = Modifier.weight(1f),
            ) {
                if (checking) CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                else Text("Updates suchen")
            }
            val found = release
            if (found != null && GithubReleases.isNewer(found.tag, versionName)) {
                Button(
                    onClick = {
                        downloading = true
                        progress = 0f
                        status = "APK wird heruntergeladen …"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    GithubReleases.download(context, found) { value ->
                                        scope.launch { progress = value }
                                    }
                                }
                            }.onSuccess {
                                apkFile = it
                                status = "${found.tag} geladen. Prüfsumme geprüft; bereit zur Installation."
                            }.onFailure {
                                status = "Download fehlgeschlagen: ${it.message}"
                            }
                            downloading = false
                        }
                    },
                    enabled = !downloading,
                    modifier = Modifier.weight(1f),
                ) { Text("Herunterladen") }
            }
        }
        apkFile?.let { file ->
            Button(
                onClick = { installWithSelectedMethod(file) },
                enabled = !waitingForShizukuPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (waitingForShizukuPermission) "Shizuku-Freigabe abwarten …" else "Update installieren")
            }
        }
        release?.takeIf { it.notes.isNotBlank() && GithubReleases.isNewer(it.tag, versionName) }?.let {
            Text("Release-Notizen: ${it.notes.take(500)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline)
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

/** Shizuku startet diese Binder-Klasse mit Shell- oder Root-Rechten. */
class ShizukuInstallService : IUpdateInstaller.Stub() {
    override fun install(apkPath: String): String {
        val process = ProcessBuilder("pm", "install", "-r", apkPath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        return if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
            "Update über Shizuku installiert. Öffne die App erneut."
        } else {
            "Shizuku-Installation fehlgeschlagen: ${output.ifBlank { "Exit-Code $exitCode" }}"
        }
    }
}
