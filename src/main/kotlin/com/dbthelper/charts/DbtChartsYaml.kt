package com.dbthelper.charts

import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** YAML-PSI facts about dbt Charts boards. Only used from classes registered in yadt-charts-yaml.xml. */
object DbtChartsYaml {
    /**
     * A chart is a mapping that is the value of an entry under `charts:`, or of an entry in a layout
     * list item (`rows: - chart_id: {…}`). Query and source mappings also have a `type:`; they never
     * sit there. A list item also holds the row's own keys (`- title: …` / `charts: {…}`), so there
     * only a mapping with a `type:` counts — otherwise each row's `charts:` was listed as a chart.
     */
    fun isChartMapping(mapping: YAMLMapping): Boolean {
        val entry = mapping.parent as? YAMLKeyValue ?: return false
        val container = entry.parentMapping ?: return false
        val containerEntry = container.parent
        return (containerEntry is YAMLKeyValue && containerEntry.keyText == "charts") ||
            (containerEntry is YAMLSequenceItem && mapping.getKeyValueByKey("type") != null)
    }
}
