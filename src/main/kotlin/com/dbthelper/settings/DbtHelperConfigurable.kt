package com.dbthelper.settings

import com.dbthelper.core.ProfilesParser
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import kotlin.reflect.KMutableProperty0

class DbtHelperConfigurable(private val project: Project) : BoundConfigurable("YADT") {

    private val settings get() = DbtHelperSettings.getInstance(project)

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(SettingsChangeListener.TOPIC).onSettingsChanged()
    }

    override fun createPanel() = panel {
        group("Executable") {
            row("dbt executable path:") {
                textField()
                    .bindText(settings.state::dbtExecutablePath)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Path to dbt CLI binary, e.g. /usr/local/bin/dbt or just dbt if it's in PATH")
            }
        }

        group("dbt Charts") {
            row("dct executable path:") {
                textField()
                    .bindText(settings.state::dctExecutablePath)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Path to the dbt Charts CLI, or just dct if it's in PATH. Its installed schema drives board YAML completion and validation")
            }
            row {
                checkBox("Download the board schema from GitHub when dct isn't installed")
                    .bindSelected(settings.state::downloadChartsSchema)
                    .comment("Fetches the newest released schema from github.com/dbt-labs/dbt-charts and caches it")
            }
        }

        group("Project") {
            row("Project root override:") {
                textField()
                    .bindText(settings.state::dbtProjectRootOverride)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Absolute path to dbt project root. Leave empty to auto-detect from dbt_project.yml")
            }
            row("Active target:") {
                val targets = ProfilesParser.getInstance(project).getTargetNames()
                if (targets.isNotEmpty()) {
                    comboBox(targets)
                        .bindItem(settings.state::activeTarget.toNullableProperty())
                        .comment("Target from profiles.yml to use for compilation, e.g. dev, prod")
                } else {
                    textField()
                        .bindText(settings.state::activeTarget)
                        .comment("Target from profiles.yml to use for compilation, e.g. dev, prod")
                }
            }
        }

        group("Lineage Defaults") {
            row("Upstream depth:") {
                spinner(1..20)
                    .bindIntValue(settings.state::upstreamDepth)
                    .comment("How many levels of parent models to show above the current node")
            }
            row("Downstream depth:") {
                spinner(1..20)
                    .bindIntValue(settings.state::downstreamDepth)
                    .comment("How many levels of child models to show below the current node")
            }
            row("Edge style:") {
                val styles = listOf("bezier", "taxi", "round-taxi", "segments", "straight", "unbundled-bezier", "haystack")
                comboBox(styles)
                    .bindItem(settings.state::edgeCurveStyle.toNullableProperty())
                    .comment("Line style for edges. Try taxi or round-taxi for orthogonal connectors")
            }
            row("Layout direction:") {
                labeledComboBox(
                    listOf("Left \u2192 Right" to "LR", "Top \u2192 Bottom" to "TB", "Right \u2192 Left" to "RL", "Bottom \u2192 Top" to "BT"),
                    settings.state::layoutDirection
                )
            }
            row("Node color:") {
                labeledComboBox(
                    listOf("Resource type" to "resource", "Schema name" to "schema", "Status" to "status"),
                    settings.state::nodeColorMode
                ).comment("How lineage node colors are derived. \"Status\" colors nodes by their last dbt run result.")
            }
            row("Cluster mode:") {
                labeledComboBox(
                    listOf("None" to "none", "Schema" to "schema", "Folder" to "folder", "Tag" to "tag"),
                    settings.state::defaultClusterMode
                ).comment("Group lineage nodes into compound clusters by schema, folder, or tag.")
            }
        }

        group("Preview") {
            row("Row limit:") {
                spinner(1..1000)
                    .bindIntValue(settings.state::previewRowLimit)
                    .comment("Maximum number of rows returned by dbt show (preview)")
            }
        }

        group("Behavior") {
            row {
                checkBox("Auto-open tool window on SQL file")
                    .bindSelected(settings.state::autoOpenOnSqlFile)
                    .comment("Automatically show the dbt Helper panel when opening a .sql file")
            }
            row {
                checkBox("Show exposures in lineage")
                    .bindSelected(settings.state::showExposures)
                    .comment("Display dbt exposures (dashboards, reports) in the lineage graph")
            }
            row {
                checkBox("Show test failure badge on lineage cards")
                    .bindSelected(settings.state::showTestFailureBadge)
                    .comment("Show a red badge with the failure count on cards that had test failures in the last run")
            }
            row {
                checkBox("Send system notifications")
                    .bindSelected(settings.state::enableSystemNotifications)
                    .comment("Send native OS notifications (macOS Notification Center) when dbt commands finish")
            }
            row {
                checkBox("Colored dbt output")
                    .bindSelected(settings.state::enableColoredOutput)
                    .comment("Pass --use-colors to dbt and render ANSI colors in the Runner output panel")
            }
            lateinit var autoParse: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
            row {
                autoParse = checkBox("Auto-parse on save")
                    .bindSelected(settings.state::autoParseOnSave)
                    .comment("Runs `dbt parse` in the background after saving a model/YAML so lineage stays current (dbt Core/Fusion).")
            }
            row {
                checkBox("…also for dbt Cloud CLI")
                    .bindSelected(settings.state::autoParseOnCloudCli)
                    .comment("dbt Cloud CLI parses run against the platform — a network round-trip on every save.")
                    .enabledIf(autoParse.selected)
            }
        }
    }

    /**
     * A combo box showing the labels of [options] (label to stored value) and bound to [property].
     * A stored value that matches no option shows, and saves back as, the first option.
     */
    private fun Row.labeledComboBox(options: List<Pair<String, String>>, property: KMutableProperty0<String>) =
        comboBox(options.map { it.first }).bindItem(
            { options.firstOrNull { it.second == property.get() }?.first ?: options.first().first },
            { label -> property.set(options.firstOrNull { it.first == label }?.second ?: options.first().second) }
        )
}
