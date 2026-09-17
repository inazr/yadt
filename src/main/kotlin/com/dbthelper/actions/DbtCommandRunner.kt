package com.dbthelper.actions

import java.nio.file.Path
import com.dbthelper.core.ExecutableLocator
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.ManifestService
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

enum class DbtEngine { CORE, FUSION, CLOUD_CLI, UNKNOWN }

class DbtCommandRunner(private val project: Project) {

    private val logger = Logger.getInstance(DbtCommandRunner::class.java)

    data class CommandResult(val exitCode: Int, val output: String, val success: Boolean)

    interface OutputListener {
        fun onLine(line: String)
        fun onFinished(result: CommandResult)
        fun onProcessStarted(process: Process) {}
    }

    fun findDbtExecutable(): String {
        val root = DbtProjectLocator.getInstance(project).findProjectRoot()?.path
        val projectVenvs = if (root == null) emptyList() else listOf(".venv", "venv", ".env").map { Path.of(root, it, "bin", "dbt") }
        val configured = DbtHelperSettings.getInstance(project).state.dbtExecutablePath
        return ExecutableLocator.find("dbt", configured, projectVenvs)?.toString() ?: "dbt"
    }

    /**
     * Classify the configured dbt by inspecting its --version banner.
     * dbt Cloud CLI prints "dbt Cloud CLI"; Fusion mentions "fusion"; dbt Core
     * prints "Core:" / "installed:". Unknown falls back to CORE semantics at
     * the call site.
     */
    fun detectEngine(): DbtEngine {
        val banner = try {
            val dbt = findDbtExecutable()
            val process = ProcessBuilder(dbt, "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output.lowercase() else return DbtEngine.UNKNOWN
        } catch (_: Exception) {
            return DbtEngine.UNKNOWN
        }
        return when {
            "cloud cli" in banner -> DbtEngine.CLOUD_CLI
            "fusion" in banner -> DbtEngine.FUSION
            "core:" in banner || "installed:" in banner -> DbtEngine.CORE
            else -> DbtEngine.UNKNOWN
        }
    }

    fun run(spec: DbtCommandSpec, listener: OutputListener) {
        val dbt = findDbtExecutable()
        val locator = DbtProjectLocator.getInstance(project)
        val projectRoot = locator.findProjectRoot()?.path

        if (projectRoot == null) {
            listener.onLine("ERROR: No dbt project found")
            listener.onFinished(CommandResult(-1, "", false))
            return
        }

        val command = DbtCommandBuilder.buildArgs(spec, dbt)

        runCommand(command, File(projectRoot), listener) { result ->
            // Auto-reload manifest after commands that can change it.
            if (result.success && spec.verb in setOf(
                    DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.GENERATE_DOCS
                )
            ) {
                ManifestService.getInstance(project).reparse()
            }
        }
    }


    fun runCommand(
        command: List<String>,
        workingDir: File,
        listener: OutputListener,
        onComplete: ((CommandResult) -> Unit)? = null
    ) {
        Thread {
            val output = StringBuilder()
            try {
                val colorsEnabled = DbtHelperSettings.getInstance(project).state.enableColoredOutput
                // Insert dbt's global color flag right after the executable, unless the
                // caller already specified one.
                val finalCommand = command.toMutableList()
                if (finalCommand.size > 1
                    && !finalCommand.contains("--use-colors")
                    && !finalCommand.contains("--no-use-colors")
                ) {
                    finalCommand.add(1, if (colorsEnabled) "--use-colors" else "--no-use-colors")
                }

                listener.onLine("$ ${finalCommand.joinToString(" ")}")
                listener.onLine("")

                val processBuilder = ProcessBuilder(finalCommand)
                    .directory(workingDir)
                    .redirectErrorStream(true)

                // The child inherits the IDE's environment; widen the terminal for dbt show output.
                val env = processBuilder.environment()
                env["COLUMNS"] = "500"
                if (colorsEnabled) {
                    env.remove("NO_COLOR")
                    env["FORCE_COLOR"] = "1"
                } else {
                    env["NO_COLOR"] = "1"
                }

                val process = processBuilder.start()
                listener.onProcessStarted(process)

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        output.appendLine(line)
                        listener.onLine(line)
                    }
                }

                val exitCode = process.waitFor()
                val result = CommandResult(exitCode, output.toString(), exitCode == 0)

                if (exitCode == 0) {
                    listener.onLine("")
                    listener.onLine("Process finished with exit code 0")
                } else {
                    listener.onLine("")
                    listener.onLine("Process finished with exit code $exitCode")
                }

                listener.onFinished(result)
                onComplete?.invoke(result)

            } catch (e: Exception) {
                logger.warn("Failed to run command: ${command.joinToString(" ")}", e)
                val errorMsg = e.message ?: "Unknown error"
                listener.onLine("ERROR: $errorMsg")
                val result = CommandResult(-1, output.toString(), false)
                listener.onFinished(result)
                onComplete?.invoke(result)
            }
        }.apply {
            name = "dbt-command-runner"
            isDaemon = true
            start()
        }
    }
}
