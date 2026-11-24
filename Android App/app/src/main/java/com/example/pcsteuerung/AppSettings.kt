package com.example.pcsteuerung

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/** Darstellung: der Systemeinstellung folgen oder fest hell/dunkel. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UpdateInstallMethod { SYSTEM, SHIZUKU }

/**
 * Kleine UI-Einstellungen, die fuer alle PC-Profile gelten.
 * Getrennt von den Profilen, weil sie nichts mit der Verbindung zu tun haben.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val refreshSeconds: Int = 5,
    val updateInstallMethod: UpdateInstallMethod = UpdateInstallMethod.SYSTEM,
    /** Dauerkarte in der Statusleiste ("PC ist an" mit zwei Knoepfen). */
    val onlineCard: Boolean = true,
    /**
     * Plugin-Detailseite nach einem erfolgreichen Start automatisch schliessen
     * (zurueck zur Uebersicht). Default aus = "Manager offen lassen".
     * Pro Plugin-ID speicherbar.
     */
    val pluginAutoClose: Map<String, Boolean> = emptyMap(),
    /**
     * Aufbewahrung im Verlauf: Anzahl der Zeilen, die die App aus dem Log des
     * Agenten laedt (`GET /log?lines=`). Auswahl 200/500/1000, Default 200.
     */
    val historyLines: Int = HISTORY_LINES_DEFAULT,
)

object AppSettingsStore {

    private const val PREFS = "settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_REFRESH = "refresh_seconds"
    private const val KEY_UPDATE_INSTALL_METHOD = "update_install_method"
    private const val KEY_ONLINE_CARD = "online_card"
    private const val KEY_PLUGIN_AUTO_CLOSE = "plugin_auto_close_map"
    private const val KEY_HISTORY_LINES = "history_lines"

    /** Erlaubte Abstaende fuer die Status-Abfrage (Sekunden). */
    val REFRESH_CHOICES = listOf(2, 5, 10, 30)

    private val gson = Gson()
    private val mapType: Type = object : TypeToken<Map<String, Boolean>>() {}.type

    fun load(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        val saved = prefs.getInt(KEY_REFRESH, 5)
        val refresh = if (saved in REFRESH_CHOICES) saved else 5
        val installMethod = prefs.getString(KEY_UPDATE_INSTALL_METHOD, null)
            ?.let { runCatching { UpdateInstallMethod.valueOf(it) }.getOrNull() }
            ?: UpdateInstallMethod.SYSTEM
        val autoCloseJson = prefs.getString(KEY_PLUGIN_AUTO_CLOSE, null)
        val pluginAutoClose: Map<String, Boolean> = if (autoCloseJson != null) {
            runCatching<Map<String, Boolean>> { gson.fromJson(autoCloseJson, mapType) }.getOrDefault(emptyMap())
        } else {
            // Migration from old single boolean
            if (prefs.getBoolean("plugin_auto_close", false)) {
                mapOf("*" to true)
            } else {
                emptyMap()
            }
        }
        return AppSettings(
            themeMode = theme,
            refreshSeconds = refresh,
            updateInstallMethod = installMethod,
            onlineCard = prefs.getBoolean(KEY_ONLINE_CARD, true),
            pluginAutoClose = pluginAutoClose,
            historyLines = decodeHistoryLines(prefs.getString(KEY_HISTORY_LINES, null)),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putInt(KEY_REFRESH, settings.refreshSeconds)
            .putString(KEY_UPDATE_INSTALL_METHOD, settings.updateInstallMethod.name)
            .putBoolean(KEY_ONLINE_CARD, settings.onlineCard)
            .putString(KEY_PLUGIN_AUTO_CLOSE, gson.toJson(settings.pluginAutoClose))
            .putString(KEY_HISTORY_LINES, encodeHistoryLines(settings.historyLines))
            .apply()
    }
}
