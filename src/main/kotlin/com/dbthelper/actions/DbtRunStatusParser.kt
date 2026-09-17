package com.dbthelper.actions

/**
 * Stateless parser for dbt's human-readable progress lines emitted during
 * `run` / `build` / `test`. Maps a line to a (relationKey, status) pair, where
 * relationKey is the "schema.identifier" (or "database.schema.identifier") that
 * dbt prints, and status is the shared [RunStatus].
 *
 * Examples it recognizes (after ANSI stripping):
 *   "12:00:01  1 of 3 START sql table model analytics.dim_customers ... [RUN]"   -> running
 *   "12:00:02  1 of 3 OK created sql table model analytics.dim_customers [SUCCESS]" -> success
 *   "12:00:03  2 of 3 ERROR creating sql model analytics.fct_orders ... [ERROR]"  -> error
 *   "12:00:03  3 of 3 SKIP relation analytics.dim_dates ............... [SKIP]"   -> skipped
 *
 * Test lines (PASS/WARN/FAIL named by test, not by relation) generally do not
 * yield a resolvable relationKey and are left to the run_results.json reconcile.
 */
object DbtRunStatusParser {

    data class NodeStatusUpdate(val relationKey: String, val status: RunStatus)

    // Strip ANSI SGR sequences (ESC [ <params> m) — same shape as DbtRunnerTab.
    private val ansiRegex = Regex("\\u001B\\[[0-9;]*m")

    // "<n> of <m> <PHASE>" then the rest of the line.
    private val phaseRegex = Regex("""\b\d+ of \d+ ([A-Z]+)\b(.*)""")

    // First "word.word" token (schema.identifier). Allows a 3-part db.schema.id too.
    private val relationRegex = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+){1,2})""")

    fun parseLine(rawLine: String): NodeStatusUpdate? {
        val line = ansiRegex.replace(rawLine, "")
        val phaseMatch = phaseRegex.find(line) ?: return null
        val status = when (phaseMatch.groupValues[1]) {
            "START" -> RunStatus.RUNNING
            "OK", "SUCCESS", "PASS" -> RunStatus.SUCCESS
            "WARN" -> RunStatus.WARN
            "ERROR", "FAIL" -> RunStatus.ERROR
            "SKIP" -> RunStatus.SKIPPED
            else -> return null
        }
        val rest = phaseMatch.groupValues[2]
        val relation = relationRegex.find(rest)?.groupValues?.get(1) ?: return null
        return NodeStatusUpdate(relation.lowercase(), status)
    }
}
