package com.dbthelper.charts

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Keeps every schema suggestion for a chart's `type:` but greys out, and sorts last, the types that
 * don't fit the fields the chart already has — with the reason, e.g. "not allowed: color, sort" or
 * "missing: value". The fields per type come from dct's schema ([DctSchemaResolver.chartTypes]).
 * Registered in yadt-charts-yaml.xml (needs the YAML PSI).
 */
class DbtChartsTypeCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        if (!DbtChartsBoardLocator.isBoardFile(file)) return
        val keyValue = PsiTreeUtil.getParentOfType(parameters.position, YAMLKeyValue::class.java) ?: return
        if (keyValue.keyText != "type") return
        val chart = keyValue.parentMapping ?: return
        if (!isChart(chart)) return
        val types = DctSchemaResolver.getInstance(parameters.originalFile.project).chartTypes
        if (types.isEmpty()) return

        val presentKeys = chart.keyValues.map { it.keyText }.toSet()
        result.runRemainingContributors(parameters) { completion ->
            val type = types[completion.lookupElement.lookupString]
            val mismatch = type?.mismatch(presentKeys)
            result.passResult(
                when {
                    type == null -> completion
                    mismatch == null -> completion.withLookupElement(PrioritizedLookupElement.withPriority(completion.lookupElement, 1.0))
                    else -> completion.withLookupElement(PrioritizedLookupElement.withPriority(Unfit(completion.lookupElement, mismatch), -1.0))
                },
            )
        }
    }

    /**
     * A chart is a mapping that is the value of an entry under `charts:`, or of an entry in a layout
     * list item (`rows: - chart_id: {…}`). Query and source mappings also have a `type:`; they never
     * sit there.
     */
    private fun isChart(mapping: YAMLMapping): Boolean {
        val entry = mapping.parent as? YAMLKeyValue ?: return false
        val container = entry.parentMapping ?: return false
        val containerEntry = container.parent
        return (containerEntry is YAMLKeyValue && containerEntry.keyText == "charts") || containerEntry is YAMLSequenceItem
    }

    private class Unfit(delegate: LookupElement, private val mismatch: DctChartTypes.Mismatch) :
        LookupElementDecorator<LookupElement>(delegate) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            presentation.itemTextForeground = JBColor.GRAY
            presentation.setTypeText(reason(), null)
            presentation.isTypeGrayed = true
        }

        private fun reason(): String = listOfNotNull(
            mismatch.notAllowed.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "not allowed: "),
            mismatch.missing.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "missing: "),
        ).joinToString("; ")
    }
}
