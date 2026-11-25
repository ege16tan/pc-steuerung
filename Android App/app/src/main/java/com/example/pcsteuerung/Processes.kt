package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/** Ein sichtbarer PC-Prozess, den der Agent per WM_CLOSE schliessen kann. */
data class RunningApp(
    val pid: Int,
    val name: String,
)

/** Antwort von `GET /processes`; ungültige Einträge werden verworfen. */
fun parseRunningApps(body: String): List<RunningApp> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val processes = JSONObject(body.trim()).optJSONArray("processes") ?: JSONArray()
        val byPid = LinkedHashMap<Int, RunningApp>()
        for (index in 0 until processes.length()) {
            val item = processes.optJSONObject(index) ?: continue
            val pid = item.optInt("pid", 0)
            val name = item.optString("name", "").trim()
            if (pid > 4 && name.isNotEmpty()) {
                byPid.putIfAbsent(pid, RunningApp(pid, name))
            }
        }
        byPid.values.sortedWith(compareBy(RunningApp::name, RunningApp::pid))
    }.getOrDefault(emptyList())
}

/** JSON-Body fuer die namensgebundene und gegen PID-Wiederverwendung gesicherte Anfrage. */
fun processCloseBody(app: RunningApp): String =
    JSONObject().put("expected_name", app.name).toString()
