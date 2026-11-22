package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein Plugin des PC-Agenten. JavaScript-Plugins laufen direkt im Agenten
 * (siehe `jsplugins.rs` im Projekt), alte EXE-Plugins bleiben ueber `kind`
 * erkennbar.
 */
data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val kind: String,
    val permissions: List<String>,
    val builtin: Boolean,
    val installed: Boolean,
    val running: Boolean,
    val uptime: Int,
    val status: String,
    val tail: List<String>,
)

/** Eintrag im Plugin-Katalog des Agenten (`GET /plugin-catalog`). */
data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val installed: Boolean,
)

private fun JSONArray.toStrings(): List<String> =
    (0 until length()).mapNotNull { index -> opt(index)?.toString() }

private fun JSONObject.toStrings(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return array.toStrings()
}

private fun JSONObject.string(name: String, fallback: String = ""): String {
    val value = optString(name, fallback)
    return if (value.isNullOrBlank()) fallback else value
}

/** Antwort von `GET /plugins` auswerten. */
fun parsePlugins(body: String): List<PluginInfo> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val array = JSONObject(body).optJSONArray("plugins") ?: JSONArray()
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PluginInfo(
                id = item.string("id"),
                name = item.string("name", item.string("id")),
                description = item.string("description"),
                version = item.string("version", "1"),
                kind = item.string("kind", "javascript"),
                permissions = item.toStrings("permissions"),
                builtin = item.optBoolean("builtin", false),
                installed = item.optBoolean("available", false),
                running = item.optBoolean("running", false),
                uptime = item.optInt("uptime", 0),
                status = item.string("status"),
                tail = item.toStrings("tail"),
            )
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())
}

/** Antwort von `GET /plugin-catalog` auswerten. */
fun parsePluginCatalog(body: String): List<PluginCatalogEntry> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val array = JSONObject(body).optJSONArray("plugins") ?: JSONArray()
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PluginCatalogEntry(
                id = item.string("id"),
                name = item.string("name", item.string("id")),
                description = item.string("description"),
                version = item.string("version", "1"),
                installed = item.optBoolean("installed", false),
            )
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())
}

/** Alles, was der Markt+ fuer Plugins braucht, in einer Antwort. */
data class PluginSnapshot(
    val plugins: List<PluginInfo>,
    val catalog: List<PluginCatalogEntry>,
)

/** Art des Plugins als Kurzzeichen fuer die Oberflaeche ("JS" oder "EXE"). */
fun pluginKindLabel(plugin: PluginInfo): String =
    if (plugin.kind.equals("javascript", ignoreCase = true)) "JS" else "EXE"

/**
 * Ausgabe eines Plugins (`GET /plugins/{id}/output?lines=n`) aufbereiten.
 *
 * Der Agent antwortet mit reinem Text (eine Zeile je Eintrag, am Ende ein
 * Zeilenumbruch). HTTP-Fehler kommen als "HTTP 404: ..." an und bleiben
 * sichtbar; ueberzaehlige Leerzeilen am Ende fallen weg.
 */
fun parsePluginOutput(body: String): String = body.trimEnd()
