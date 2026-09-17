package com.dbthelper.charts

import com.dbthelper.settings.DbtHelperSettings
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves the dbt Charts board JSON Schema: from the user's installed dct (default), else, when
 * enabled in settings, from a sha256-verified GitHub download cache. Holds the result so the
 * schema provider only reads a field; announces changes on [DctSchemaListener.TOPIC].
 */
@Service(Service.Level.PROJECT)
class DctSchemaResolver(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(DctSchemaResolver::class.java)
    private val refreshLock = Mutex()
    // Own scope, not an injected one: YADT bundles kotlinx-coroutines, so an injected CoroutineScope
    // parameter is a different class than the platform's and the service can't be constructed.
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var schemaPath: Path? = null
        private set

    @Volatile
    var schemaFile: VirtualFile? = null
        private set

    fun refresh(): Job = cs.launch(Dispatchers.IO) {
        refreshLock.withLock {
            val resolved = resolveLocal()
                ?: if (DbtHelperSettings.getInstance(project).state.downloadChartsSchema) resolveDownloaded() else null
            if (resolved == schemaPath) return@withLock
            schemaFile = resolved?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
            schemaPath = resolved
            logger.info("dbt Charts board schema: ${resolved ?: "none"}")
            project.messageBus.syncPublisher(DctSchemaListener.TOPIC).onSchemaChanged(resolved)
        }
    }

    private fun resolveLocal(): Path? {
        val dct = findDct() ?: return null
        val candidates = DctVenvLocator.venvCandidates(dct, uvToolDir(), Path.of(System.getProperty("user.home")))
        val dir = DctVenvLocator.findSchemaDir(candidates) ?: return null
        return newestVerified(dir)
    }

    private fun newestVerified(dir: Path): Path? {
        val manifest = dir.resolve("manifest.json")
        val entry = runCatching { DctSchemaManifest.newestReleased(Files.readString(manifest)) }.getOrNull()
        if (entry == null) {
            logger.info("No released dbt Charts schema listed in $manifest")
            return null
        }
        val file = dir.resolve(entry.file)
        if (!DctSchemaManifest.sha256Matches(file, entry.sha256)) {
            logger.warn("sha256 mismatch for $file; ignoring it")
            return null
        }
        return file
    }

    private fun findDct(): Path? {
        val configured = DbtHelperSettings.getInstance(project).state.dctExecutablePath.trim()
        if (configured.isNotEmpty() && configured != "dct") {
            return Path.of(configured).takeIf { Files.isRegularFile(it) }
        }
        PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("dct")?.let { return it.toPath() }
        return Path.of(System.getProperty("user.home"), ".local", "bin", "dct").takeIf { Files.isRegularFile(it) }
    }

    private fun uvToolDir(): Path? {
        val uv = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("uv")?.toPath()
            ?: Path.of(System.getProperty("user.home"), ".local", "bin", "uv").takeIf { Files.isRegularFile(it) }
            ?: return null
        return try {
            val proc = ProcessBuilder(uv.toString(), "tool", "dir").redirectErrorStream(true).start()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0 && out.isNotEmpty()) Path.of(out.lines().last()) else null
        } catch (e: Exception) {
            logger.info("`uv tool dir` failed", e)
            null
        }
    }

    private fun resolveDownloaded(): Path? {
        val cacheDir = Path.of(PathManager.getSystemPath(), "yadt", "dbt-charts-schema")
        if (downloadAttempted.compareAndSet(false, true)) {
            try {
                download(cacheDir)
            } catch (e: Exception) {
                logger.info("dbt Charts schema download failed; falling back to the cache", e)
            }
        }
        return DctSchemaManifest.newestCached(cacheDir)
    }

    private fun download(cacheDir: Path) {
        val entry = DctSchemaManifest.newestReleased(HttpRequests.request("$GITHUB_SCHEMA_BASE/manifest.json").readString())
            ?: return
        val target = cacheDir.resolve("${entry.version}.json")
        if (DctSchemaManifest.sha256Matches(target, entry.sha256)) return
        Files.createDirectories(cacheDir)
        val part = Files.createTempFile(cacheDir, entry.version, ".part")
        try {
            HttpRequests.request("$GITHUB_SCHEMA_BASE/${entry.file}").saveToFile(part, null)
            if (!DctSchemaManifest.sha256Matches(part, entry.sha256)) {
                logger.warn("Downloaded dbt Charts schema ${entry.version} failed sha256 verification; discarded")
                return
            }
            Files.writeString(cacheDir.resolve("${entry.version}.sha256"), entry.sha256)
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(part)
        }
    }

    override fun dispose() {
        cs.cancel()
    }

    companion object {
        private const val GITHUB_SCHEMA_BASE =
            "https://raw.githubusercontent.com/dbt-labs/dbt-charts/main/src/dbt_charts/data/schemas/yaml"

        /** Download at most once per IDE session; later refreshes reuse the cache. */
        private val downloadAttempted = AtomicBoolean(false)

        fun getInstance(project: Project): DctSchemaResolver = project.service()
    }
}
