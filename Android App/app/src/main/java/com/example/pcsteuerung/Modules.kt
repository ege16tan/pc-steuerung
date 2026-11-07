package com.example.pcsteuerung

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein Modul aus dem Katalog des Marktplatzes. Basis (An/Aus, Status,
 * Ruhezustand, Energieplan) ist immer dabei; Module fuegen Zusaetze hinzu.
 */
data class CatalogModule(
    val id: String,
    val name: String,
    val description: String,
    val version: Int = 1,
)

/** Module, die die App schon unterstuetzt (Feature-Code vorhanden). */
val SUPPORTED_MODULES = setOf("timer", "log")

/** Modul-IDs als Konstanten, damit Tippfehler im UI-Fall auffallen. */
const val MODULE_TIMER = "timer"
const val MODULE_LOG = "log"

/** Lokaler Fallback, wenn der Remote-Katalog nicht erreichbar ist. */
fun defaultCatalog(): List<CatalogModule> = listOf(
    CatalogModule(
        id = MODULE_TIMER,
        name = "Zeitschaltuhr",
        description = "Herunterfahren in Sekunden oder Stunden planen.",
        version = 1,
    ),
    CatalogModule(
        id = MODULE_LOG,
        name = "Verlauf",
        description = "Log-Ausgabe des Agenten direkt in der App ansehen.",
        version = 1,
    ),
)

/** Remote-Quelle des Katalogs (oeffentliches Repo). */
const val CATALOG_URL =
    "https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/module-catalog/modules.json"

/**
 * Parst eine Katalog-JSON. Robust gegen fehlende Felder und doppelte IDs
 * (erster Treffer gewinnt); wirft nur bei unlesbarem JSON.
 */
fun parseCatalog(json: String): List<CatalogModule> {
    val arr = JSONObject(json).optJSONArray("modules") ?: JSONArray()
    val seen = HashSet<String>()
    val out = ArrayList<CatalogModule>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.optString("id").trim()
        if (id.isEmpty() || !seen.add(id)) continue
        out.add(
            CatalogModule(
                id = id,
                name = o.optString("name", "PC-Modul"),
                description = o.optString("description", ""),
                version = o.optInt("version", 1),
            )
        )
    }
    return out
}

/**
 * Combiniert den Remote-Katalog mit dem lokalen Fallback: Der Remote-Katalog
 * gewinnt, fehlende lokale Eintraege (z. B. offline neu) werden ergaenzt.
 */
fun mergeCatalog(
    local: List<CatalogModule>,
    remote: List<CatalogModule>,
): List<CatalogModule> {
    if (remote.isEmpty()) return local
    val merged = ArrayList<CatalogModule>(remote)
    val ids = merged.map { it.id }.toSet()
    for (m in local) if (m.id !in ids) merged.add(m)
    return merged
}

/**
 * Laedt den Remote-Katalog per HTTPS (immer ohne Auth, oeffentliches Repo).
 * Wirft bei Netzwerk-/Parsing-Fehlern; der Aufrufer behaelt dann den
 * zuletzt bekannten Stand bzw. den lokalen Fallback.
 */
fun fetchRemoteCatalog(url: String = CATALOG_URL): List<CatalogModule> {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseCatalog(body)
    } finally {
        conn.disconnect()
    }
}

/**
 * Persistiert installierte Modul-IDs. Gleich wie die Profile in derselben
 * Datei unter eigenem Key, weil Module fuer die ganze App gelten.
 */
object ModuleStore {

    private const val PREFS = "settings"
    private const val KEY_MODULES = "installed_modules"

    fun load(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODULES, null) ?: return emptySet()
        val arr = JSONArray(raw)
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun save(context: Context, ids: Set<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODULES, arr.toString())
            .apply()
    }
}
