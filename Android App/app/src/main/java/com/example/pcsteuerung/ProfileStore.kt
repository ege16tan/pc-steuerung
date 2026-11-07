package com.example.pcsteuerung

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Verwaltet mehrere PC-Profile in SharedPreferences.
 *
 * Format:
 *   "profiles"       -> JSON-Array aller Profile
 *   "active_profile" -> id des aktiven Profils
 *
 * Beim ersten Zugriff werden die alten Einzel-Keys
 * (host/port/user/pass/mac/broadcast/default_delay) in ein Profil migriert.
 */
object ProfileStore {

    private const val PREFS = "settings"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"

    // Alte Einzel-Keys (werden nach der Migration entfernt).
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_USER = "user"
    private const val K_PASS = "pass"
    private const val K_MAC = "mac"
    private const val K_BROADCAST = "broadcast"
    private const val K_DELAY = "default_delay"

    fun load(context: Context): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw != null) return decode(raw)

        // Migration aus den alten Einzel-Keys.
        val host = prefs.getString(K_HOST, "").orEmpty()
        if (host.isBlank()) return emptyList()
        val profile = Profile(
            id = newId(),
            name = "PC",
            host = host,
            port = prefs.getString(K_PORT, "8000").orEmpty(),
            user = prefs.getString(K_USER, "").orEmpty(),
            pass = prefs.getString(K_PASS, "").orEmpty(),
            mac = prefs.getString(K_MAC, "").orEmpty(),
            broadcast = prefs.getString(K_BROADCAST, "255.255.255.255").orEmpty(),
            defaultDelay = prefs.getInt(K_DELAY, 60),
        )
        save(context, listOf(profile), profile.id)
        return listOf(profile)
    }

    fun activeId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACTIVE, null)
        if (saved != null) return saved
        return load(context).firstOrNull()?.id
    }

    fun active(context: Context): Profile? {
        val profiles = load(context)
        val id = activeId(context)
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun save(context: Context, profiles: List<Profile>, activeId: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROFILES, encode(profiles))
            .putString(KEY_ACTIVE, activeId)
            .remove(K_HOST)
            .remove(K_PORT)
            .remove(K_USER)
            .remove(K_PASS)
            .remove(K_MAC)
            .remove(K_BROADCAST)
            .remove(K_DELAY)
            .apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun encode(profiles: List<Profile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("user", p.user)
                    .put("pass", p.pass)
                    .put("mac", p.mac)
                    .put("broadcast", p.broadcast)
                    .put("defaultDelay", p.defaultDelay)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<Profile> {
        val arr = JSONArray(raw)
        val result = ArrayList<Profile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                Profile(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name", "PC"),
                    host = o.optString("host"),
                    port = o.optString("port", "8000"),
                    user = o.optString("user"),
                    pass = o.optString("pass"),
                    mac = o.optString("mac"),
                    broadcast = o.optString("broadcast", "255.255.255.255"),
                    defaultDelay = o.optInt("defaultDelay", 60),
                )
            )
        }
        return result
    }
}
