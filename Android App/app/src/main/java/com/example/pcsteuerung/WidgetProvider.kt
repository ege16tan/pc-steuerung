package com.example.pcsteuerung

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL

/**
 * Homescreen-Widget mit zwei Buttons.
 *
 * Die Klicks kommen als Broadcasts mit eigenen Actions hier an. Der HTTP-Aufruf
 * laeuft in einem Hintergrund-Thread; goAsync() haelt den Broadcast so lange offen.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action != ACTION_WAKE && action != ACTION_SHUTDOWN) return

        val pendingResult = goAsync()
        Thread({
            try {
                handleAction(context, action)
            } finally {
                pendingResult.finish()
            }
        }, "widget-http").start()
    }

    /** Baut das Widget mit den beiden Klick-Zielen. */
    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_status_idle))
        views.setOnClickPendingIntent(R.id.widget_wake, broadcast(context, ACTION_WAKE, 1))
        views.setOnClickPendingIntent(R.id.widget_shutdown, broadcast(context, ACTION_SHUTDOWN, 2))

        // Tippen auf den Titel oeffnet die App (z. B. zum Einstellen von Host/MAC).
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
            val pending = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, pending)
        }
        return views
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Liest die Einstellungen und fuehrt den passenden HTTP-Aufruf aus. */
    private fun handleAction(context: Context, action: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "").orEmpty().trim()
        val port = prefs.getString(KEY_PORT, DEFAULT_PORT).orEmpty().trim().ifBlank { DEFAULT_PORT }
        val user = prefs.getString(KEY_USER, "").orEmpty().trim()
        val pass = prefs.getString(KEY_PASS, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        val mac = prefs.getString(KEY_MAC, "").orEmpty().trim()
        val broadcast = prefs.getString(KEY_BROADCAST, DEFAULT_BROADCAST).orEmpty()

        if (action == ACTION_WAKE) {
            if (mac.isBlank()) {
                showStatus(context, context.getString(R.string.widget_status_no_mac))
                return
            }
            showStatus(context, context.getString(R.string.widget_status_sending))
            val ok = sendMagicPacket(mac, broadcast)
            showStatus(
                context,
                context.getString(if (ok) R.string.widget_status_wake_sent else R.string.widget_status_wake_failed)
            )
            return
        }
        if (host.isBlank()) {
            showStatus(context, context.getString(R.string.widget_status_no_host))
            return
        }

        // Host tolerant behandeln: Schema und abschliessenden Slash entfernen.
        val cleanHost = host
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        val path = "/shutdown?delay=60"

        showStatus(context, context.getString(R.string.widget_status_sending))
        val message = try {
            when (val code = post("http://$cleanHost:$port$path", user, pass, token)) {
                in 200..299 -> context.getString(R.string.widget_status_shutdown_sent)
                401 -> context.getString(R.string.widget_status_login)
                403 -> context.getString(R.string.widget_status_forbidden)
                else -> context.getString(R.string.widget_status_http_error, code)
            }
        } catch (e: Exception) {
            context.getString(
                R.string.widget_status_unreachable,
                e.message ?: context.getString(R.string.widget_status_error),
            )
        }
        showStatus(context, message)
    }

    /** Blockierender POST ohne Body; immer aus einem Hintergrund-Thread aufrufen. */
    private fun post(url: String, user: String, pass: String, token: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            when {
                user.isNotBlank() && pass.isNotBlank() -> {
                    val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
                    connection.setRequestProperty(
                        "Authorization",
                        "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP),
                    )
                }
                token.isNotBlank() -> connection.setRequestProperty(HEADER_TOKEN, token)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes() }
            return code
        } finally {
            connection.disconnect()
        }
    }

    /** Aktualisiert nur die Statuszeile aller vorhandenen Widgets. */
    private fun showStatus(context: Context, text: String) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setTextViewText(R.id.widget_status, text)
        manager.updateAppWidget(ids, views)
    }

    companion object {
        private const val ACTION_WAKE = "com.example.pcsteuerung.action.WIDGET_WAKE"
        private const val ACTION_SHUTDOWN = "com.example.pcsteuerung.action.WIDGET_SHUTDOWN"

        private const val PREFS_NAME = "settings"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_TOKEN = "token"
        private const val KEY_MAC = "mac"
        private const val KEY_BROADCAST = "broadcast"
        private const val DEFAULT_PORT = "8000"
        private const val DEFAULT_BROADCAST = "255.255.255.255"

        private const val HEADER_TOKEN = "X-Token"
        private const val TIMEOUT_MS = 5000
    }
}
