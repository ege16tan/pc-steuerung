package com.example.pcsteuerung

import android.content.Context

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
)

object AppSettingsStore {

    private const val PREFS = "settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_REFRESH = "refresh_seconds"
    private const val KEY_UPDATE_INSTALL_METHOD = "update_install_method"

    /** Erlaubte Abstaende fuer die Status-Abfrage (Sekunden). */
    val REFRESH_CHOICES = listOf(2, 5, 10, 30)

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
        return AppSettings(
            themeMode = theme,
            refreshSeconds = refresh,
            updateInstallMethod = installMethod,
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putInt(KEY_REFRESH, settings.refreshSeconds)
            .putString(KEY_UPDATE_INSTALL_METHOD, settings.updateInstallMethod.name)
            .apply()
    }
}
