package com.example.pcsteuerung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Baut die Dauerkarte nach einem Neustart des Handys (oder nach einem
 * App-Update) wieder auf.
 *
 * Ohne Empfaenger bliebe die Karte bis zum naechsten Oeffnen der App weg.
 * `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED` sind erlaubte Ausnahmen fuer den
 * Start eines Foreground-Dienstes aus dem Hintergrund. Ist die Dauerkarte in
 * den Einstellungen abgeschaltet, passiert hier nichts.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        if (!AppSettingsStore.load(context).onlineCard) return
        val profile = ProfileStore.active(context) ?: return
        if (!profile.isComplete) return
        Log.d("PCSteuerung", "Dauerkarte nach ${intent.action} neu starten")
        LiveStatusService.startSync(context, profile)
    }
}
