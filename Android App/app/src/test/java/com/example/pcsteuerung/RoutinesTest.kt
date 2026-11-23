package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefungen fuer den erweiterten Routinen-Vertrag (Phase E): Parser von
 * `GET /routines` und `GET /routine-templates`, Serialisierung fuer
 * `POST /routines` (inkl. `wait` und `conditions`), die Kurztexte des
 * Ausloesers und die Pruefung vor dem Speichern.
 */
class RoutinesTest {

    private val fullBody = """
        {"routines":[{"id":"feierabend","name":"Feierabend","enabled":true,
          "trigger":{"type":"time","at":"18:00","days":[1,2,3,4,5],"event":"","idle_minutes":30,
                     "conditions":{"days":[],"plugin":"","power_plan":""}},
          "actions":[{"type":"power_plan","plan":"saver","delay":0,"wait":0},
                     {"type":"shutdown","delay":60,"wait":3}],
          "last_run":"2026-11-23 18:00","next_run":"2026-11-24 18:00",
          "history":[{"at":"2026-11-23 18:00","text":"Energieplan saver, Herunterfahren in 60s"}]}]}
    """

    // ---------------- GET /routines ----------------

    @Test
    fun `parseRoutines liest das vollstaendige Objekt`() {
        val routines = parseRoutines(fullBody)
        assertEquals(1, routines.size)
        val routine = routines.first()
        assertEquals("feierabend", routine.id)
        assertEquals("Feierabend", routine.name)
        assertTrue(routine.enabled)
        assertEquals("time", routine.trigger.type)
        assertEquals("18:00", routine.trigger.at)
        assertEquals(listOf(1, 2, 3, 4, 5), routine.trigger.days)
        assertEquals("", routine.trigger.event)
        assertTrue(routine.trigger.conditions.isEmpty)
        assertEquals(2, routine.actions.size)
        assertEquals("power_plan", routine.actions[0].type)
        assertEquals("saver", routine.actions[0].plan)
        assertEquals(0, routine.actions[0].wait)
        assertEquals("shutdown", routine.actions[1].type)
        assertEquals(60, routine.actions[1].delay)
        assertEquals(3, routine.actions[1].wait)
        assertEquals("2026-11-23 18:00", routine.lastRun)
        assertEquals("2026-11-24 18:00", routine.nextRun)
        assertEquals(1, routine.history.size)
        assertEquals("2026-11-23 18:00", routine.history.first().at)
        assertEquals("Energieplan saver, Herunterfahren in 60s", routine.history.first().text)
    }

    @Test
    fun `parseRoutines liest alte Routine ohne neue Felder mit Defaults`() {
        val body = """
            {"routines":[{"id":"alt","name":"Alt","enabled":false,
              "trigger":{"type":"time","at":"07:30","days":[6,7]},
              "actions":[{"type":"hibernate"}],"last_run":"","next_run":""}]}
        """
        val routine = parseRoutines(body).single()
        assertEquals("Alt", routine.name)
        assertFalse(routine.enabled)
        assertEquals("", routine.trigger.event)
        assertEquals(0, routine.trigger.idleMinutes)
        assertTrue(routine.trigger.conditions.isEmpty)
        assertTrue(routine.history.isEmpty())
        assertEquals("", routine.nextRun)
        // wait/plan/delay fehlen in der alten Form -> Defaults
        assertEquals(0, routine.actions.single().wait)
        assertEquals("", routine.actions.single().plan)
        assertEquals(0, routine.actions.single().delay)
        assertEquals("Sa–So 07:30", routine.triggerText)
        assertEquals(listOf("Ruhezustand"), routine.actionTexts)
    }

    @Test
    fun `parseRoutines liest einen Ereignis-Ausloeser`() {
        val body = """
            {"routines":[{"id":"dl","name":"Nach Download",
              "trigger":{"type":"event","event":"download_done"},
              "actions":[{"type":"hibernate","wait":10}]}]}
        """
        val routine = parseRoutines(body).single()
        assertEquals("event", routine.trigger.type)
        assertEquals("download_done", routine.trigger.event)
        assertEquals("Bei Download fertig", routine.triggerText)
        assertEquals(listOf("Ruhezustand (nach 10s)"), routine.actionTexts)
        // enabled fehlt -> an
        assertTrue(routine.enabled)
    }

    @Test
    fun `parseRoutines liest Bedingungen`() {
        val body = """
            {"routines":[{"id":"c","name":"C",
              "trigger":{"type":"time","at":"22:00","days":[],
                         "conditions":{"days":[1,5],"plugin":"game-optimizer","power_plan":"high"}},
              "actions":[{"type":"power_plan","plan":"saver"}]}]}
        """
        val routine = parseRoutines(body).single()
        assertEquals(listOf(1, 5), routine.trigger.conditions.days)
        assertEquals("game-optimizer", routine.trigger.conditions.plugin)
        assertEquals("high", routine.trigger.conditions.powerPlan)
        assertFalse(routine.trigger.conditions.isEmpty)
        assertEquals("Täglich 22:00", routine.triggerText)
        assertEquals(
            "Mo, Fr · game-optimizer · Höchstleistung",
            routineConditionsText(routine.trigger.conditions),
        )
    }

    @Test
    fun `parseRoutines begrenzt den angezeigten Verlauf auf fuenf`() {
        val body = """
            {"routines":[{"id":"h","name":"H","history":[
              {"at":"2026-11-20 18:00","text":"Lauf 1"},
              {"at":"2026-11-21 18:00","text":"Lauf 2"},
              {"at":"2026-11-22 18:00","text":"Lauf 3"},
              {"at":"2026-11-23 18:00","text":"Lauf 4"},
              {"at":"2026-11-24 18:00","text":"Lauf 5"},
              {"at":"2026-11-25 18:00","text":"Lauf 6"}]}]}
        """
        val routine = parseRoutines(body).single()
        assertEquals(6, routine.history.size)
        assertEquals(ROUTINE_HISTORY_LIMIT, routine.recentHistory.size)
        assertEquals("Lauf 1", routine.recentHistory.first().text)
    }

    @Test
    fun `parseRoutines ueberspringt Eintraege ohne Kennung`() {
        val body = """{"routines":[{},{"name":"Ohne Kennung"},{"id":"ok","name":"Ok"}]}"""
        val routines = parseRoutines(body)
        assertEquals(1, routines.size)
        assertEquals("ok", routines.first().id)
        // fehlender Name -> Kennung
        assertEquals("ok", parseRoutines("""{"routines":[{"id":"ok"}]}""").single().name)
    }

    @Test
    fun `parseRoutines nimmt auch eine reine Liste`() {
        val routines = parseRoutines("""[{"id":"a","name":"A"},{"id":"b","name":"B"}]""")
        assertEquals(listOf("a", "b"), routines.map { it.id })
    }

    @Test
    fun `parseRoutines liefert bei Fehlern eine leere Liste`() {
        assertTrue(parseRoutines("HTTP 404: not found").isEmpty())
        assertTrue(parseRoutines("HTTP 403: verboten").isEmpty())
        assertTrue(parseRoutines("").isEmpty())
        assertTrue(parseRoutines("kein json").isEmpty())
        assertTrue(parseRoutines("""{"scanned_at":"x"}""").isEmpty())
    }

    // ---------------- GET /routine-templates ----------------

    @Test
    fun `parseRoutineTemplates liest Vorlagen`() {
        val body = """
            {"templates":[
              {"id":"feierabend","name":"Feierabend",
               "trigger":{"type":"time","at":"18:00","days":[1,2,3,4,5]},
               "actions":[{"type":"power_plan","plan":"saver"},
                          {"type":"shutdown","delay":60}]},
              {"id":"nachts","name":"Nachts sparen",
               "trigger":{"type":"time","at":"23:00"},
               "actions":[{"type":"power_plan","plan":"saver"}]}
            ]}
        """
        val templates = parseRoutineTemplates(body)
        assertEquals(2, templates.size)
        assertEquals("Feierabend", templates[0].name)
        assertEquals("18:00", templates[0].trigger.at)
        assertEquals(2, templates[0].actions.size)
        assertEquals("Nachts sparen", templates[1].name)
        assertEquals("Täglich 23:00", routineTriggerText(templates[1].trigger))
    }

    @Test
    fun `parseRoutineTemplates liefert ohne Route eine leere Liste`() {
        assertTrue(parseRoutineTemplates("HTTP 404: not found").isEmpty())
        assertTrue(parseRoutineTemplates("").isEmpty())
        assertTrue(parseRoutineTemplates("kein json").isEmpty())
        assertTrue(parseRoutineTemplates("""{"templates":[]}""").isEmpty())
    }

    @Test
    fun `routineFromTemplate macht daraus eine eigene Routine`() {
        val template = RoutineTemplate(
            id = "feierabend",
            name = "Feierabend",
            trigger = RoutineTrigger(
                type = ROUTINE_TRIGGER_TIME,
                at = "18:00",
                days = listOf(1, 2, 3, 4, 5),
            ),
            actions = listOf(
                RoutineActionInfo(type = ROUTINE_ACTION_POWER_PLAN, plan = "saver"),
                RoutineActionInfo(type = ROUTINE_ACTION_HIBERNATE),
                RoutineActionInfo(type = ROUTINE_ACTION_HIBERNATE),
                RoutineActionInfo(type = ROUTINE_ACTION_SHUTDOWN, delay = 60),
            ),
        )
        val routine = routineFromTemplate(template)
        assertEquals("feierabend", routine.id)
        assertEquals("Feierabend", routine.name)
        assertTrue(routine.enabled)
        assertEquals(MAX_ROUTINE_STEPS, routine.actions.size)
    }

    // ---------------- POST /routines ----------------

    @Test
    fun `routineJson schreibt wait und conditions`() {
        val routine = RoutineInfo(
            id = "feierabend",
            name = "Feierabend",
            trigger = RoutineTrigger(
                type = ROUTINE_TRIGGER_TIME,
                at = "18:00",
                days = listOf(1, 2, 3, 4, 5),
                conditions = RoutineConditions(
                    days = listOf(6, 7),
                    plugin = "game-optimizer",
                    powerPlan = "saver",
                ),
            ),
            actions = listOf(
                RoutineActionInfo(type = ROUTINE_ACTION_POWER_PLAN, plan = "saver", wait = 0),
                RoutineActionInfo(type = ROUTINE_ACTION_SHUTDOWN, delay = 60, wait = 3),
            ),
        )
        val json = routineJson(routine)
        assertTrue(json.contains("\"wait\":3"))
        assertTrue(json.contains("\"conditions\""))
        assertTrue(json.contains("\"plugin\":\"game-optimizer\""))
        assertTrue(json.contains("\"power_plan\":\"saver\""))
        assertTrue(json.contains("\"days\":[6,7]"))
        // Hin und zurueck ergibt denselben Zustand (last_run fehlt bewusst).
        assertEquals(routine, parseRoutines("""{"routines":[$json]}""").single())
    }

    @Test
    fun `routineJson schreibt einen Ereignis-Ausloeser`() {
        val routine = RoutineInfo(
            id = "inaktiv",
            name = "Inaktiv",
            trigger = RoutineTrigger(
                type = ROUTINE_TRIGGER_EVENT,
                event = ROUTINE_EVENT_IDLE,
                idleMinutes = 45,
            ),
            actions = listOf(RoutineActionInfo(type = ROUTINE_ACTION_HIBERNATE, wait = 5)),
        )
        val json = routineJson(routine)
        assertTrue(json.contains("\"type\":\"event\""))
        assertTrue(json.contains("\"event\":\"idle\""))
        assertTrue(json.contains("\"idle_minutes\":45"))
        assertEquals(routine, parseRoutines("""{"routines":[$json]}""").single())
    }

    @Test
    fun `withRoutineEnabled aendert nur das Kennzeichen`() {
        val routine = parseRoutines(fullBody).single()
        val off = withRoutineEnabled(routine, false)
        assertFalse(off.enabled)
        assertEquals(routine.id, off.id)
        assertEquals(routine.trigger, off.trigger)
        assertEquals(routine.actions, off.actions)
        assertTrue(routineJson(off).contains("\"enabled\":false"))
    }

    // ---------------- Kurztexte ----------------

    @Test
    fun `routineTriggerText zeigt Zeit mit Wochentagen`() {
        assertEquals(
            "Mo–Fr 18:00",
            routineTriggerText(
                RoutineTrigger(type = ROUTINE_TRIGGER_TIME, at = "18:00", days = listOf(1, 2, 3, 4, 5))
            ),
        )
        assertEquals(
            "Täglich 18:00",
            routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_TIME, at = "18:00")),
        )
        assertEquals("Täglich", routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_TIME)))
        assertEquals(
            "Mo, Mi 07:15",
            routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_TIME, at = "07:15", days = listOf(1, 3))),
        )
        assertEquals(
            "Täglich 08:00",
            routineTriggerText(
                RoutineTrigger(type = ROUTINE_TRIGGER_TIME, at = "08:00", days = (1..7).toList())
            ),
        )
    }

    @Test
    fun `routineTriggerText zeigt Ereignisse und Manuell`() {
        assertEquals(
            "Bei Spiel beendet",
            routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_EVENT, event = ROUTINE_EVENT_GAME_STOPPED)),
        )
        assertEquals(
            "Bei Download fertig",
            routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_EVENT, event = ROUTINE_EVENT_DOWNLOAD_DONE)),
        )
        assertEquals(
            "Bei PC inaktiv (30 min)",
            routineTriggerText(
                RoutineTrigger(type = ROUTINE_TRIGGER_EVENT, event = ROUTINE_EVENT_IDLE, idleMinutes = 30)
            ),
        )
        assertEquals(
            "Bei PC inaktiv",
            routineTriggerText(
                RoutineTrigger(type = ROUTINE_TRIGGER_EVENT, event = ROUTINE_EVENT_IDLE, idleMinutes = 0)
            ),
        )
        assertEquals("Manuell", routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_MANUAL)))
        // unbekannte Art und fehlendes Ereignis fallen auf "Manuell" zurueck
        assertEquals("Manuell", routineTriggerText(RoutineTrigger(type = "irgendwas")))
        assertEquals("Manuell", routineTriggerText(RoutineTrigger(type = ROUTINE_TRIGGER_EVENT)))
    }

    @Test
    fun `routineDaysText fasst zusammen und kuerzt`() {
        assertEquals("", routineDaysText(emptyList()))
        assertEquals("Täglich", routineDaysText((1..7).toList()))
        assertEquals("Mo–Fr", routineDaysText(listOf(5, 4, 3, 2, 1, 2)))
        assertEquals("Sa–So", routineDaysText(listOf(6, 7)))
        assertEquals("Mo, Mi, Fr", routineDaysText(listOf(3, 1, 5, 3)))
        // ungueltige Tage fallen weg
        assertEquals("Mo", routineDaysText(listOf(0, 1, 9, -1)))
    }

    @Test
    fun `routineNextRunText ist bei leerem Feld leer`() {
        assertEquals("", routineNextRunText(""))
        assertEquals("", routineNextRunText("   "))
        assertEquals("2026-11-24 18:00", routineNextRunText("  2026-11-24 18:00 "))
    }

    @Test
    fun `withRoutineDay schaltet einen Tag um`() {
        assertEquals(listOf(1, 2, 3), withRoutineDay(listOf(1, 2), 3))
        assertEquals(listOf(1, 3), withRoutineDay(listOf(1, 2, 3), 2))
        assertEquals(listOf(1), withRoutineDay(listOf(1), 0))
    }

    // ---------------- Formular: Entwurf -> Routine ----------------

    @Test
    fun `routineFromDraft baut Ausloeser Bedingungen und Schritte`() {
        val draft = RoutineDraft(
            name = "Feierabend",
            triggerType = ROUTINE_TRIGGER_TIME,
            at = "18:00",
            days = listOf(5, 1, 2, 3, 4, 1),
            conditionDays = listOf(1, 1, 5),
            conditionPlugin = " game-optimizer ",
            actions = listOf(
                RoutineActionDraft(
                    type = ROUTINE_ACTION_POWER_PLAN,
                    plan = "saver",
                    delay = "0",
                    wait = "0",
                ),
                RoutineActionDraft(
                    type = ROUTINE_ACTION_SHUTDOWN,
                    plan = "",
                    delay = "60",
                    wait = "3",
                ),
            ),
        )
        assertNull(routineValidationError(draft))
        val routine = routineFromDraft(draft)
        assertEquals("feierabend", routine.id)
        assertEquals("Feierabend", routine.name)
        assertEquals(listOf(1, 2, 3, 4, 5), routine.trigger.days)
        assertEquals("18:00", routine.trigger.at)
        assertEquals("game-optimizer", routine.trigger.conditions.plugin)
        assertEquals(listOf(1, 5), routine.trigger.conditions.days)
        assertEquals(60, routine.actions[1].delay)
        assertEquals(3, routine.actions[1].wait)
        assertEquals("", routine.actions[1].plan)

        val json = routineJson(routine)
        assertTrue(json.contains("\"wait\":3"))
        assertTrue(json.contains("\"conditions\""))
        val back = parseRoutines("""{"routines":[$json]}""").single()
        assertEquals("18:00", back.trigger.at)
        assertEquals("Mo–Fr 18:00", back.triggerText)
        assertEquals(60, back.actions[1].delay)
        assertEquals(3, back.actions[1].wait)
    }

    @Test
    fun `routineFromDraft nimmt bei Ereignis die Minuten`() {
        val draft = RoutineDraft(
            name = "Inaktiv",
            triggerType = ROUTINE_TRIGGER_EVENT,
            event = ROUTINE_EVENT_IDLE,
            idleMinutes = "45",
            actions = listOf(RoutineActionDraft(type = ROUTINE_ACTION_HIBERNATE, wait = "10")),
        )
        assertNull(routineValidationError(draft))
        val json = routineJson(routineFromDraft(draft))
        assertTrue(json.contains("\"event\":\"idle\""))
        assertTrue(json.contains("\"idle_minutes\":45"))
        assertTrue(json.contains("\"at\":\"\""))
        assertTrue(json.contains("\"wait\":10"))
    }

    @Test
    fun `routineToDraft macht aus einer Routine wieder ein Formular`() {
        val routine = parseRoutines(fullBody).single()
        val draft = routineToDraft(routine)
        assertEquals("feierabend", draft.id)
        assertEquals("Feierabend", draft.name)
        assertEquals(ROUTINE_TRIGGER_TIME, draft.triggerType)
        assertEquals("18:00", draft.at)
        assertEquals(listOf(1, 2, 3, 4, 5), draft.days)
        assertEquals(2, draft.actions.size)
        assertEquals("60", draft.actions[1].delay)
        assertEquals("3", draft.actions[1].wait)
        assertNull(routineValidationError(draft))
    }

    @Test
    fun `updateRoutineStep ersetzt nur den gewaehlten Schritt`() {
        val draft = RoutineDraft(
            actions = listOf(
                RoutineActionDraft(type = ROUTINE_ACTION_POWER_PLAN),
                RoutineActionDraft(type = ROUTINE_ACTION_HIBERNATE),
            )
        )
        val next = updateRoutineStep(draft, 1) { it.copy(wait = "30") }
        assertEquals("30", next.actions[1].wait)
        assertEquals(ROUTINE_ACTION_POWER_PLAN, next.actions[0].type)
        // ungueltiger Index aendert nichts
        assertEquals(draft, updateRoutineStep(draft, 5) { it.copy(wait = "1") })
    }

    @Test
    fun `withNewRoutineStep haelt Herunterfahren am Ende`() {
        val start = listOf(RoutineActionDraft(type = ROUTINE_ACTION_POWER_PLAN))
        val two = withNewRoutineStep(start)
        assertEquals(listOf(ROUTINE_ACTION_POWER_PLAN, ROUTINE_ACTION_POWER_PLAN), two.map { it.type })

        val withShutdown = listOf(
            RoutineActionDraft(type = ROUTINE_ACTION_POWER_PLAN),
            RoutineActionDraft(type = ROUTINE_ACTION_SHUTDOWN),
        )
        val three = withNewRoutineStep(withShutdown)
        assertEquals(3, three.size)
        assertEquals(ROUTINE_ACTION_SHUTDOWN, three.last().type)
        assertEquals(ROUTINE_ACTION_POWER_PLAN, three[1].type)
        // mehr als drei Schritte gibt es nicht
        assertEquals(3, withNewRoutineStep(three).size)
    }

    // ---------------- Pruefung vor dem Speichern ----------------

    @Test
    fun `routineValidationError prueft die Eingaben`() {
        val ok = RoutineDraft(name = "Test", at = "18:00")
        assertNull(routineValidationError(ok))
        assertEquals(RoutineValidationError.NAME_MISSING, routineValidationError(ok.copy(name = "  ")))
        assertEquals(RoutineValidationError.TIME_INVALID, routineValidationError(ok.copy(at = "25:00")))
        assertEquals(RoutineValidationError.TIME_INVALID, routineValidationError(ok.copy(at = "18")))
        assertEquals(
            RoutineValidationError.EVENT_MISSING,
            routineValidationError(ok.copy(triggerType = ROUTINE_TRIGGER_EVENT, event = "")),
        )
        assertEquals(
            RoutineValidationError.IDLE_INVALID,
            routineValidationError(
                ok.copy(
                    triggerType = ROUTINE_TRIGGER_EVENT,
                    event = ROUTINE_EVENT_IDLE,
                    idleMinutes = "0",
                )
            ),
        )
        assertEquals(
            RoutineValidationError.STEP_MISSING,
            routineValidationError(ok.copy(actions = emptyList())),
        )
        assertEquals(
            RoutineValidationError.TOO_MANY_STEPS,
            routineValidationError(ok.copy(actions = List(4) { RoutineActionDraft() })),
        )
        assertEquals(
            RoutineValidationError.PLAN_MISSING,
            routineValidationError(ok.copy(actions = listOf(RoutineActionDraft(plan = "")))),
        )
        assertEquals(
            RoutineValidationError.WAIT_INVALID,
            routineValidationError(ok.copy(actions = listOf(RoutineActionDraft(wait = "301")))),
        )
        assertEquals(
            RoutineValidationError.DELAY_INVALID,
            routineValidationError(
                ok.copy(
                    actions = listOf(
                        RoutineActionDraft(type = ROUTINE_ACTION_SHUTDOWN, delay = "4000")
                    )
                )
            ),
        )
        assertEquals(
            RoutineValidationError.SHUTDOWN_NOT_LAST,
            routineValidationError(
                ok.copy(
                    actions = listOf(
                        RoutineActionDraft(type = ROUTINE_ACTION_SHUTDOWN),
                        RoutineActionDraft(type = ROUTINE_ACTION_HIBERNATE),
                    )
                )
            ),
        )
        // "Manuell" braucht weder Uhrzeit noch Ereignis
        assertNull(routineValidationError(ok.copy(triggerType = ROUTINE_TRIGGER_MANUAL, at = "")))
    }

    @Test
    fun `normalizeRoutineTime ergaenzt fuehrende Nullen`() {
        assertEquals("18:00", normalizeRoutineTime("18:00"))
        assertEquals("09:05", normalizeRoutineTime("9:05"))
        assertEquals("00:00", normalizeRoutineTime("0:00"))
        assertNull(normalizeRoutineTime("24:00"))
        assertNull(normalizeRoutineTime("18:60"))
        assertNull(normalizeRoutineTime(""))
    }

    @Test
    fun `uniqueRoutineId weicht vorhandenen Kennungen aus`() {
        assertEquals("feierabend", uniqueRoutineId("feierabend", emptyList()))
        assertEquals("feierabend", uniqueRoutineId("feierabend", listOf("nachts")))
        assertEquals("feierabend-2", uniqueRoutineId("feierabend", listOf("feierabend")))
        assertEquals(
            "feierabend-3",
            uniqueRoutineId("Feierabend", listOf("feierabend", "feierabend-2")),
        )
        assertEquals("routine", uniqueRoutineId("   ", emptyList()))
    }

    @Test
    fun `routineIdFromName erzeugt eine lesbare Kennung`() {
        assertEquals("feierabend", routineIdFromName("Feierabend"))
        assertEquals("buero-co", routineIdFromName("Büro & Co"))
        assertEquals("nachts-sparen", routineIdFromName("  Nachts sparen  "))
    }
}
