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

/**
 * Version, die fuer installierte Module ohne eigenen Eintrag gilt. Altdaten
 * kannten nur `installed_modules`, dort ist die Version unbekannt (= 1).
 */
const val MODULE_VERSION_FALLBACK = 1

/**
 * Was ein Modul in der App freischaltet - lokale Tabelle, unabhaengig vom
 * (aenderbaren) Katalogtext. Muss jeden Eintrag aus [SUPPORTED_MODULES] decken.
 */
val MODULE_FEATURES: Map<String, String> = mapOf(
    MODULE_TIMER to "Timer-Knopf und eigene Verzoegerung",
    MODULE_LOG to "Verlauf mit Kopieren/Teilen",
)

/** Kurztext "das schaltet das Modul frei"; unbekannte IDs bekommen einen Ersatz. */
fun moduleFeatureText(id: String): String =
    MODULE_FEATURES[id] ?: "Zusaetzliche PC-Funktion."

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

// ---------------------------------------------------------------------------
// Versionsspeicher der installierten Module (Phase F5)
//
// Bis v0.14.0 merkte sich die App nur die IDs (`installed_modules`). Damit ein
// Katalog-Update erkennbar ist, liegt daneben ein JSON-Objekt
// `{"<id>": <version>}`. Fehlt ein Eintrag (Altdaten), gilt Version 1.
// Alle Funktionen hier sind rein und ohne Android-Context testbar.
// ---------------------------------------------------------------------------

/**
 * Liest den Versionsspeicher. Leere/kaputte Werte ergeben ein leeres Mapping,
 * Werte unter 1 werden auf die Fallback-Version gehoben.
 */
fun installedVersions(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    try {
        val obj = JSONObject(raw)
        for (key in obj.keys()) {
            val id = key.trim()
            if (id.isEmpty()) continue
            val version = obj.optInt(key, MODULE_VERSION_FALLBACK)
            out[id] = version.coerceAtLeast(MODULE_VERSION_FALLBACK)
        }
    } catch (e: Exception) {
        // Kaputter Altspeicher darf die App nicht am Start hindern.
        return emptyMap()
    }
    return out
}

/** Schreibt den Versionsspeicher kanonisch (sortiert), damit er stabil bleibt. */
fun versionsJson(versions: Map<String, Int>): String {
    val obj = JSONObject()
    versions.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { it.key }
        .forEach { (id, version) ->
            obj.put(id.trim(), version.coerceAtLeast(MODULE_VERSION_FALLBACK))
        }
    return obj.toString()
}

/** Installationsversion eines Moduls; ohne Eintrag gilt die Fallback-Version. */
fun installedVersionOf(versions: Map<String, Int>, id: String): Int =
    versions[id] ?: MODULE_VERSION_FALLBACK

/** Installieren/Aktualisieren: Version festschreiben, andere Module bleiben. */
fun withInstalledVersion(raw: String?, id: String, version: Int): String =
    versionsJson(installedVersions(raw) + (id.trim() to version))

/** Entfernen: nur den Eintrag dieses Moduls loeschen. */
fun withoutInstalledVersion(raw: String?, id: String): String =
    versionsJson(installedVersions(raw) - id.trim())

/** Katalog neuer als installiert -> "Update verfuegbar" anbieten. */
fun moduleUpdateAvailable(catalog: CatalogModule, installedVersion: Int?): Boolean =
    installedVersion != null && catalog.version > installedVersion

/** Katalog aelter als installiert -> kein Downgrade anbieten. */
fun catalogOlderThanInstalled(catalog: CatalogModule, installedVersion: Int?): Boolean =
    installedVersion != null && catalog.version < installedVersion

/**
 * Persistiert installierte Modul-IDs. Gleich wie die Profile in derselben
 * Datei unter eigenem Key, weil Module fuer die ganze App gelten.
 */
object ModuleStore {

    private const val PREFS = "settings"
    private const val KEY_MODULES = "installed_modules"
    private const val KEY_VERSIONS = "module_versions"

    fun load(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODULES, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) {
            // Kaputter Altspeicher darf die App nicht am Start hindern.
            emptySet()
        }
    }

    fun save(context: Context, ids: Set<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODULES, arr.toString())
            .apply()
    }

    /**
     * Rohwert des Versionsspeichers (`{"<id>": <version>}`). Leer heisst:
     * noch nie geschrieben, also Altdaten ohne Versionen.
     */
    fun loadVersionsRaw(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERSIONS, null) ?: ""

    /** Versionen der installierten Module (fehlende Eintraege = Version 1). */
    fun loadVersions(context: Context): Map<String, Int> =
        installedVersions(loadVersionsRaw(context))

    /** Schreibt den Versionsspeicher; der Aufrufer uebergibt bereits JSON. */
    fun saveVersions(context: Context, raw: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERSIONS, raw)
            .apply()
    }

    /** Schreibt ein Mapping (kanonisch sortiert) in den Versionsspeicher. */
    fun saveVersions(context: Context, versions: Map<String, Int>) =
        saveVersions(context, versionsJson(versions))
}
