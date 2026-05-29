import org.jetbrains.changelog.Changelog

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.snakeyaml)
    implementation(libs.coroutines.core)

    intellijPlatform {
        val type = providers.gradleProperty("platformType").get()
        val version = providers.gradleProperty("platformVersion").get()
        create(type, version)
    }

    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
    // IntelliJ Platform's JUnit5TestEnvironmentInitializer SPI needs JUnit 4 on test runtime
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.inazr.yadt"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = providers.gradleProperty("untilBuild").map { it.ifEmpty { null } }
        }

        // "What's New" is rendered from CHANGELOG.md (the changelog plugin converts
        // the current version's Markdown section to HTML). This overrides any
        // <change-notes> in plugin.xml, so that tag is no longer kept there.
        // Rendered eagerly (not in a lazy provider) so the patchPluginXml task stores
        // a plain String rather than capturing the changelog extension / Project, which
        // the configuration cache cannot serialize.
        changeNotes = run {
            val pluginVersion = providers.gradleProperty("pluginVersion").get()
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    // Flat bullet lists per version (no "### Added/Changed" group headers required).
    groups.empty()
    repositoryUrl = "https://github.com/inazr/yadt"
}

tasks {
    wrapper {
        gradleVersion = "8.12"
    }

    test {
        useJUnitPlatform()
    }
}
