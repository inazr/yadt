package com.dbthelper.charts.query

import com.dbthelper.charts.DctExecutable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one `dct query` at a time, off the EDT. dct prints its JSON to stdout even when the query
 * fails (exit 1), so the exit code is ignored; only output that isn't JSON (a crash) is reported as
 * the output's last lines.
 */
@Service(Service.Level.PROJECT)
class DctQueryRunner(private val project: Project) {

    private val generation = AtomicInteger()
    @Volatile private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /** [onResult] is called on the EDT, unless the run was stopped or superseded. */
    fun run(
        root: Path,
        boardRelPath: String,
        queryName: String,
        limit: Int,
        vars: Map<String, String>,
        onResult: (DctQueryResult.Table) -> Unit,
    ) {
        stop()
        val runId = generation.get()
        ApplicationManager.getApplication().executeOnPooledThread {
            val table = execute(runId, root, boardRelPath, queryName, limit, vars) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed && runId == generation.get()) onResult(table)
            }, ModalityState.nonModal())
        }
    }

    fun stop() {
        val running = synchronized(this) {
            generation.incrementAndGet()
            process.also { process = null }
        }
        running?.let(::destroyTree)
    }

    /** Null when the run was stopped before or while it ran. */
    private fun execute(
        runId: Int,
        root: Path,
        boardRelPath: String,
        queryName: String,
        limit: Int,
        vars: Map<String, String>,
    ): DctQueryResult.Table? {
        val dct = DctExecutable.find(project) ?: return failed(DCT_NOT_FOUND)
        val output = Files.createTempFile("yadt-dct-query", ".json")
        try {
            val started = try {
                ProcessBuilder(DctQueryCommand.args(dct, root, boardRelPath, queryName, limit, vars))
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            } catch (e: IOException) {
                logger<DctQueryRunner>().info("Could not start dct query in $root", e)
                return failed("Could not start dct: ${e.message}")
            }
            // stop() may have run between the generation read and start(): don't leave it orphaned.
            val current = synchronized(this) { (runId == generation.get()).also { if (it) process = started } }
            if (!current) {
                destroyTree(started)
                return null
            }
            if (!started.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyTree(started)
                return failed("dct query timed out after $TIMEOUT_SECONDS s")
            }
            if (runId != generation.get()) return null
            val text = Files.readString(output)
            if (!text.trimStart().startsWith("{")) {
                val tail = text.lines().filter { it.isNotBlank() }.takeLast(LOG_TAIL_LINES).joinToString("\n")
                return failed("dct query failed (exit ${started.exitValue()})" + if (tail.isEmpty()) "" else ":\n$tail")
            }
            return DctQueryResult.parse(text)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun failed(message: String) = DctQueryResult.Table(success = false, errors = listOf(message))

    /** SIGTERM the tree now, SIGKILL whatever survives a short grace, without blocking the caller. */
    private fun destroyTree(process: Process) {
        val tree = process.toHandle().descendants().toList() + process.toHandle()
        tree.forEach { it.destroy() }
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(STOP_GRACE_MS)
            tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L
        private const val STOP_GRACE_MS = 2000L
        private const val LOG_TAIL_LINES = 15
        private const val DCT_NOT_FOUND =
            "dct not found. Install dbt-charts, or set the dct path in Settings → Tools → YADT."

        fun getInstance(project: Project): DctQueryRunner = project.service()
    }
}
