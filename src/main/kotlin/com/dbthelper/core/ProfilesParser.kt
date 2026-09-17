package com.dbthelper.core

import com.dbthelper.core.model.ProfilesConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml

@Service(Service.Level.PROJECT)
class ProfilesParser(private val project: Project) {

    private val logger = Logger.getInstance(ProfilesParser::class.java)
    private val locator = DbtProjectLocator.getInstance(project)

    @Volatile
    private var cachedConfig: ProfilesConfig? = null

    @Volatile
    private var cachedProjectName: String? = null

    fun parse(): ProfilesConfig? {
        cachedConfig?.let { return it }

        val file = locator.getProfilesFile() ?: return null
        return try {
            val yaml = Yaml()
            val data = file.inputStream().use { yaml.load<Map<String, Any>>(it) }

            // Read profile name from dbt_project.yml
            val profileName = readProfileFromProject() ?: data.keys.firstOrNull { it != "config" } ?: return null

            @Suppress("UNCHECKED_CAST")
            val profileData = data[profileName] as? Map<String, Any> ?: return null
            val defaultTarget = profileData["target"] as? String ?: "dev"

            @Suppress("UNCHECKED_CAST")
            val outputs = profileData["outputs"] as? Map<String, Any> ?: emptyMap()

            ProfilesConfig(
                defaultTarget = defaultTarget,
                targetNames = outputs.keys.toList()
            ).also { cachedConfig = it }
        } catch (e: Exception) {
            logger.warn("Failed to parse profiles.yml", e)
            null
        }
    }

    private fun readProfileFromProject(): String? = readDbtProjectYml()?.get("profile") as? String

    /** The dbt project's `name:` from dbt_project.yml (used for target/compiled/<name>/…). */
    fun getProjectName(): String? {
        cachedProjectName?.let { return it }
        return (readDbtProjectYml()?.get("name") as? String).also { cachedProjectName = it }
    }

    private fun readDbtProjectYml(): Map<String, Any>? = try {
        locator.findProjectRoot()?.findChild("dbt_project.yml")?.inputStream?.use { Yaml().load<Map<String, Any>>(it) }
    } catch (e: Exception) {
        logger.warn("Failed to read dbt_project.yml", e)
        null
    }

    fun getTargetNames(): List<String> = parse()?.targetNames ?: emptyList()

    fun getDefaultTarget(): String? = parse()?.defaultTarget

    fun invalidateCache() {
        cachedConfig = null
        cachedProjectName = null
    }

    companion object {
        fun getInstance(project: Project): ProfilesParser =
            project.service<ProfilesParser>()
    }
}
