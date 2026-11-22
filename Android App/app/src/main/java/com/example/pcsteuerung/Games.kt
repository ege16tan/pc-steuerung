package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spiele-Optimierung des Agenten: Spiele-Liste und Konfiguration.
 *
 * Vertrag (Kurzform), wie ihn `PC Agent Rust/src/games.rs` liefert:
 *  GET  /games       -> {"scanned_at":"2026-01-01 10:00:00",
 *                        "games":[{"name":"...","exe":"D:\\...","source":"Steam",
 *                                  "running":false,"hidden":false}]}
 *  POST /games/scan  -> sucht neu (Antwort wird nicht ausgewertet)
 *  GET  /game-config -> {"kill_list":["Discord.exe"],"scan_paths":[],"auto":false,
 *                        "hidden":["D:\\..."],
 *                        "custom":[{"name":"...","exe":"...","source":"manuell"}]}
 *  POST /game-config <- derselbe Koerper (immer der komplette Zustand)
 *
 * Gelesen wird bewusst tolerant (`optJSON*`): fehlende Felder, ein leerer Body
 * oder ein alter Agent ohne `hidden` duerfen nicht zu Fehlern fuehren. Weil
 * `hidden` im Vertrag neu ist, rechnet [isHiddenGame] das Kennzeichen zusaetzlich
 * aus der Konfiguration nach - Ausblenden wirkt damit auch dann, wenn die
 * Spiele-Antwort das Feld noch nicht enthaelt.
 */

/** Quelle fuer manuell in der App hinzugefuegte Spiele (wie im Agenten). */
const val GAME_SOURCE_MANUAL = "manuell"

/** Ein Eintrag der Spiele-Liste: gescannt oder manuell hinzugefuegt. */
data class GameEntry(
    val name: String = "",
    val exe: String = "",
    val source: String = "",
    val running: Boolean = false,
    val hidden: Boolean = false,
) {
    /** Manuell hinzugefuegt (darf in der App auch wieder geloescht werden). */
    val isManual: Boolean get() = source.equals(GAME_SOURCE_MANUAL, ignoreCase = true)
}

/** Kompletter Zustand von `GET/POST /game-config`. */
data class GameConfig(
    val killList: List<String> = emptyList(),
    val scanPaths: List<String> = emptyList(),
    val auto: Boolean = false,
    val hidden: List<String> = emptyList(),
    val custom: List<GameEntry> = emptyList(),
)

// ---------------------------------------------------------------------------
// JSON-Hilfen (tolerant, Datei-privat wie in Plugins.kt/Sensors.kt)
// ---------------------------------------------------------------------------

private fun JSONArray.gameTexts(): List<String> =
    (0 until length()).mapNotNull { index -> opt(index)?.toString()?.trim() }
        .filter { it.isNotEmpty() }

private fun JSONObject.gameTexts(name: String): List<String> =
    optJSONArray(name)?.gameTexts().orEmpty()

private fun JSONObject.gameText(name: String, fallback: String = ""): String {
    val value = optString(name, fallback)
    return if (value.isNullOrBlank()) fallback else value
}

/** Ein Objekt aus `games`/`custom` in ein [GameEntry] umwandeln. */
private fun JSONObject.toGameEntry(): GameEntry = GameEntry(
    name = gameText("name"),
    exe = gameText("exe"),
    source = gameText("source"),
    running = optBoolean("running", false),
    hidden = optBoolean("hidden", false),
)

private fun JSONArray.gameEntries(): List<GameEntry> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.toGameEntry() }
        .filter { it.name.isNotBlank() || it.exe.isNotBlank() }

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/**
 * Antwort von `GET /games` auswerten. Akzeptiert auch eine reine Liste
 * (`[{...}]`) und liefert bei HTTP-Fehlern oder kaputtem JSON eine leere Liste.
 */
fun parseGames(body: String): List<GameEntry> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val text = body.trim()
        val array = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("games")
        }
        array?.gameEntries().orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * Antwort von `GET /game-config` auswerten. Fehlende Felder werden zu leeren
 * Listen bzw. `false`; ein HTTP-Fehler oder kaputtes JSON ergibt `null`
 * (die Seite zeigt dann einen Hinweis statt Fantasiewerten).
 */
fun parseGameConfig(body: String): GameConfig? {
    if (body.startsWith("HTTP ")) return null
    return runCatching {
        val json = JSONObject(body.trim())
        GameConfig(
            killList = json.gameTexts("kill_list"),
            scanPaths = json.gameTexts("scan_paths"),
            auto = json.optBoolean("auto", false),
            hidden = json.gameTexts("hidden"),
            custom = json.optJSONArray("custom")?.gameEntries().orEmpty(),
        )
    }.getOrNull()
}

/** Kompletten Zustand als JSON fuer `POST /game-config` schreiben. */
fun gameConfigJson(config: GameConfig): String {
    val custom = JSONArray()
    config.custom.forEach { game ->
        custom.put(
            JSONObject()
                .put("name", game.name)
                .put("exe", game.exe)
                .put("source", game.source.ifBlank { GAME_SOURCE_MANUAL })
        )
    }
    return JSONObject()
        .put("kill_list", JSONArray(config.killList))
        .put("scan_paths", JSONArray(config.scanPaths))
        .put("auto", config.auto)
        .put("hidden", JSONArray(config.hidden))
        .put("custom", custom)
        .toString()
}

// ---------------------------------------------------------------------------
// Reine Hilfsfunktionen fuer die Oberflaeche (Ausblenden, manuelle Spiele)
// ---------------------------------------------------------------------------

/** Vergleichsschluessel eines EXE-Pfads: getrimmt, `\` und `/` gleich, klein. */
fun gamePathKey(exe: String): String = exe.trim().replace('/', '\\').lowercase()

/** Vergleichsschluessel eines Anzeigenamens: getrimmt, klein. */
fun gameNameKey(name: String): String = name.trim().lowercase()

/** Passt ein Eintrag der Ausblende-Liste (Pfad **oder** Name) zu diesem Spiel? */
private fun hiddenEntryMatches(entry: String, pathKey: String, nameKey: String): Boolean {
    val entryPath = gamePathKey(entry)
    val entryName = gameNameKey(entry)
    if (entryPath.isEmpty() && entryName.isEmpty()) return false
    return (pathKey.isNotEmpty() && entryPath == pathKey) ||
        (nameKey.isNotEmpty() && entryName == nameKey)
}

/** Ist das Spiel ausgeblendet (Kennzeichen der Liste **oder** `hidden` der Konfiguration)? */
fun isHiddenGame(game: GameEntry, hidden: List<String>): Boolean {
    if (game.hidden) return true
    val pathKey = gamePathKey(game.exe)
    val nameKey = gameNameKey(game.name)
    return hidden.any { entry -> hiddenEntryMatches(entry, pathKey, nameKey) }
}

/** Eintrag, der beim Ausblenden in `hidden` landet: EXE-Pfad, sonst der Name. */
fun hiddenEntryFor(game: GameEntry): String = game.exe.trim().ifBlank { game.name.trim() }

/**
 * Ausblenden/Einblenden umschalten und das **neue** `hidden`-Array liefern.
 *
 * [hide] = true haengt den Pfad an (falls nicht schon vorhanden), [hide] = false
 * entfernt alle passenden Eintraege (Pfad- oder Namensvergleich). Die uebergebene
 * Liste bleibt unveraendert.
 */
fun withHidden(hidden: List<String>, game: GameEntry, hide: Boolean): List<String> {
    val pathKey = gamePathKey(game.exe)
    val nameKey = gameNameKey(game.name)
    val rest = hidden.filterNot { entry -> hiddenEntryMatches(entry, pathKey, nameKey) }
    if (!hide) return rest
    val entry = hiddenEntryFor(game)
    return if (entry.isEmpty()) rest else rest + entry
}

/** Gescannte Spiele plus manuelle Eintraege; doppelte Pfade fallen weg. */
fun mergeCustomGames(games: List<GameEntry>, custom: List<GameEntry>): List<GameEntry> {
    val merged = games.toMutableList()
    val seen = games.map { gamePathKey(it.exe) }.filter { it.isNotEmpty() }.toMutableSet()
    custom.forEach { game ->
        val key = gamePathKey(game.exe)
        if (key.isEmpty() || seen.add(key)) {
            merged.add(game.copy(source = game.source.ifBlank { GAME_SOURCE_MANUAL }))
        }
    }
    return merged
}

/** Manuelles Spiel aufnehmen (gleicher Pfad wird ersetzt, nicht doppelt). */
fun withCustomGame(config: GameConfig, game: GameEntry): GameConfig {
    val key = gamePathKey(game.exe)
    val rest = config.custom.filterNot { existing ->
        key.isNotEmpty() && gamePathKey(existing.exe) == key
    }
    return config.copy(
        custom = rest + game.copy(source = game.source.ifBlank { GAME_SOURCE_MANUAL })
    )
}

/** Manuelles Spiel entfernen (Vergleich ueber den EXE-Pfad, sonst den Namen). */
fun withoutCustomGame(config: GameConfig, game: GameEntry): GameConfig {
    val pathKey = gamePathKey(game.exe)
    val nameKey = gameNameKey(game.name)
    val rest = config.custom.filterNot { existing ->
        if (pathKey.isNotEmpty()) {
            gamePathKey(existing.exe) == pathKey
        } else {
            gameNameKey(existing.name) == nameKey
        }
    }
    return config.copy(custom = rest)
}

/**
 * Kill-Liste aendern: [present] = true nimmt [name] auf (ohne Doppelte, die
 * vorhandene Schreibweise bleibt erhalten), `false` entfernt ihn. Der Vergleich
 * ignoriert Gross-/Kleinschreibung.
 */
fun withKillEntry(killList: List<String>, name: String, present: Boolean): List<String> {
    val entry = name.trim()
    if (entry.isEmpty()) return killList
    val exists = killList.any { it.equals(entry, ignoreCase = true) }
    if (present) return if (exists) killList else killList + entry
    return killList.filterNot { it.equals(entry, ignoreCase = true) }
}
