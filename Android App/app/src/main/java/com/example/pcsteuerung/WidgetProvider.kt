package com.example.pcsteuerung

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Homescreen-Widget:
 * - "Einschalten" sendet direkt ein Wake-on-LAN-Paket.
 * - "Herunterfahren" oeffnet die App mit Bestaetigungsdialog (kein
 *   versehentliches Ausschalten; nutzt den Standard-Timer aus den Einstellungen).
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
        if (intent.action != ACTION_WAKE) return

        val pendingResult = goAsync()
        Thread({
            try {
                handleWake(context)
            } finally {
                pendingResult.finish()
            }
        }, "widget-wol").start()
    }

    private fun handleWake(context: Context) {
        val active = ProfileStore.active(context)
        val mac = active?.mac?.trim().orEmpty()
        val broadcast = active?.broadcast?.ifBlank { DEFAULT_BROADCAST } ?: DEFAULT_BROADCAST

        if (mac.isBlank()) {
            showStatus(context, context.getString(R.string.widget_status_no_mac))
            return
        }

        showStatus(context, context.getString(R.string.widget_status_sending))
        val ok = sendMagicPacket(context, mac, broadcast)
        showStatus(
            context,
            context.getString(if (ok) R.string.widget_status_wake_sent else R.string.widget_status_wake_failed)
        )
    }

    private fun showStatus(context: Context, text: String) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, buildViews(context, text))
    }

    companion object {
        /** Aktualisiert die Statuszeile aller vorhandenen Widgets. */
        fun updateAll(context: Context, text: String) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, text))
        }

        /**
         * Baut die Widget-Ansicht inklusive aller Klick-Handler. Wichtig:
         * Status-Updates duerfen die RemoteViews nicht neu ohne PendingIntents
         * aufbauen, sonst reagieren die Buttons nach dem ersten Update nicht mehr.
         */
        private fun buildViews(context: Context, status: String? = null): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(
                R.id.widget_status,
                status ?: context.getString(R.string.widget_status_idle),
            )
            views.setOnClickPendingIntent(R.id.widget_wake, wakeIntent(context))
            views.setOnClickPendingIntent(R.id.widget_shutdown, confirmShutdownIntent(context))

            // Tippen auf den Titel oeffnet die App.
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

        private fun wakeIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetProvider::class.java).setAction(ACTION_WAKE)
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun confirmShutdownIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_CONFIRM_SHUTDOWN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val ACTION_WAKE = "com.example.pcsteuerung.action.WIDGET_WAKE"
        private const val DEFAULT_BROADCAST = "255.255.255.255"
    }
}
