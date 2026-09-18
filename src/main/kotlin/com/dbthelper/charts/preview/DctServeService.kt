package com.dbthelper.charts.preview

import com.dbthelper.charts.DctExecutable
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.net.NetUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

sealed interface DctServeResult {
    data class Ready(val port: Int) : DctServeResult
    data class Failed(val message: String) : DctServeResult
}

/**
 * Runs one `dct serve` per dbt Charts project root for the board previews. Each preview holds a
 * lease; the server starts with the first lease and is killed when the last one is released. A
 * server that failed to start or died is replaced on the next [acquire], which is what Retry does.
 */
@Service(Service.Level.PROJECT)
class DctServeService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    private val logger = Logger.getInstance(DctServeService::class.java)
    private val servers = HashMap<Path, Server>()

    private inner class Server(val root: Path) {
        var leases = 0
        @Volatile var process: Process? = null
        @Volatile var stopped = false
        val log: Path = Files.createTempFile("yadt-dct-serve", ".log")
        val startup: Deferred<DctServeResult> = cs.async(Dispatchers.IO) { start(this@Server) }

        val isDead: Boolean get() = startup.isCompleted && process?.isAlive != true

        fun stop() {
            stopped = true
            startup.cancel()
            process?.let(::destroyTree)
            runCatching { Files.deleteIfExists(log) }
        }
    }

    /**
     * Reports on a background thread: [DctServeResult.Ready] or [DctServeResult.Failed] once the
     * server answers or gives up, then a further `Failed` if a ready server dies while leased.
     */
    fun acquire(root: Path, lease: Disposable, onResult: (DctServeResult) -> Unit) {
        val server = synchronized(servers) {
            servers[root]?.takeIf { it.isDead }?.let { servers.remove(root); it.stop() }
            servers.getOrPut(root) { Server(root) }.also { it.leases++ }
        }
        Disposer.register(lease) { release(server) }
        cs.launch {
            val result = server.startup.await()
            onResult(result)
            if (result !is DctServeResult.Ready) return@launch
            server.process?.onExit()?.await()
            if (!server.stopped) onResult(DctServeResult.Failed("dct serve stopped unexpectedly.\n${logTail(server.log)}"))
        }
    }

    private fun release(server: Server) {
        synchronized(servers) {
            if (--server.leases > 0) return
            servers.remove(server.root, server)
        }
        server.stop()
    }

    private suspend fun start(server: Server): DctServeResult {
        val dct = DctExecutable.find(project) ?: return DctServeResult.Failed(DCT_NOT_FOUND)
        val port = try {
            NetUtils.findAvailableSocketPort()
        } catch (e: IOException) {
            return DctServeResult.Failed("No free local port for dct serve: ${e.message}")
        }
        val process = try {
            ProcessBuilder(DctServeCommand.args(dct, server.root, port))
                .directory(server.root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(server.log.toFile())
                .start()
        } catch (e: IOException) {
            logger.info("Could not start dct serve for ${server.root}", e)
            return DctServeResult.Failed("Could not start dct: ${e.message}")
        }
        server.process = process
        if (server.stopped) {
            destroyTree(process)
            return DctServeResult.Failed("Preview closed.")
        }
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                return DctServeResult.Failed("dct serve exited with code ${process.exitValue()}.\n${logTail(server.log)}")
            }
            if (responds(port)) return DctServeResult.Ready(port)
            delay(POLL_INTERVAL_MS)
        }
        destroyTree(process)
        return DctServeResult.Failed("dct serve did not answer within ${STARTUP_TIMEOUT.inWholeSeconds} s.\n${logTail(server.log)}")
    }

    /** Any HTTP answer means the server is up; a board error is dct's page to show, not ours. */
    private fun responds(port: Int): Boolean = try {
        val connection = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 2000
        try {
            connection.responseCode > 0
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        false
    }

    private fun destroyTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
    }

    private fun logTail(log: Path): String =
        runCatching { Files.readAllLines(log).takeLast(LOG_TAIL_LINES).joinToString("\n") }.getOrDefault("")

    override fun dispose() {
        val all = synchronized(servers) { servers.values.toList().also { servers.clear() } }
        all.forEach { it.stop() }
    }

    companion object {
        private val STARTUP_TIMEOUT = 30.seconds
        private const val POLL_INTERVAL_MS = 250L
        private const val LOG_TAIL_LINES = 15
        private const val DCT_NOT_FOUND =
            "dct not found. Install dbt-charts, or set the dct path in Settings → Tools → YADT."

        fun getInstance(project: Project): DctServeService = project.service()
    }
}
