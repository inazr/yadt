package com.dbthelper.actions

enum class DbtVerb(val display: String) {
    RUN("Run"),
    BUILD("Build"),
    TEST("Test"),
    COMPILE("Compile"),
    PREVIEW("Preview"),
    GENERATE_DOCS("Generate Docs");

    /** Verbs that send `--select <selector>`. */
    val usesSelector: Boolean get() = this != GENERATE_DOCS

    /** Verbs that accept `--full-refresh`. */
    val supportsFullRefresh: Boolean get() = this == RUN || this == BUILD
}

data class DbtCommandSpec(
    val verb: DbtVerb,
    val selector: String,
    val target: String,        // blank = no --target
    val fullRefresh: Boolean,
    val previewLimit: Int
)

/**
 * Single source of truth for the dbt command shown in the action bar's preview
 * field and the args handed to ProcessBuilder. Both methods walk the same
 * when(verb) so display and execution can never drift.
 */
object DbtCommandBuilder {

    fun buildArgs(spec: DbtCommandSpec, dbtExe: String): List<String> =
        buildTokens(spec, dbtExe)

    /** Human-readable command, always prefixed with "dbt" (not the exe path). */
    fun buildDisplay(spec: DbtCommandSpec): String =
        buildTokens(spec, "dbt").joinToString(" ")

    private fun buildTokens(spec: DbtCommandSpec, exe: String): List<String> {
        val sel = spec.selector.trim()
        // Omit --select entirely when the selector is blank, rather than emitting a
        // dangling "--select".
        val select = if (sel.isNotEmpty()) listOf("--select", sel) else emptyList()
        val cmd = mutableListOf(exe)
        when (spec.verb) {
            DbtVerb.RUN -> {
                cmd += listOf("run") + select
                if (spec.fullRefresh) cmd += "--full-refresh"
            }
            DbtVerb.BUILD -> {
                cmd += listOf("build") + select
                if (spec.fullRefresh) cmd += "--full-refresh"
            }
            DbtVerb.TEST -> cmd += listOf("test") + select
            DbtVerb.COMPILE -> cmd += listOf("compile") + select
            DbtVerb.PREVIEW -> cmd += listOf("show") + select +
                listOf("--limit", spec.previewLimit.toString(), "--output", "json")
            DbtVerb.GENERATE_DOCS -> cmd += listOf("docs", "generate")
        }
        if (spec.target.isNotBlank()) cmd += listOf("--target", spec.target)
        return cmd
    }
}
