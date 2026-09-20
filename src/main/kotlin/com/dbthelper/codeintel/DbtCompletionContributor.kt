package com.dbthelper.codeintel

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.core.model.ManifestIndex
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class DbtCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet
            ) {
                val file = parameters.originalFile
                val index = codeIntelIndex(file) ?: return

                val offset = parameters.offset
                val textBefore = file.text.substring(0, offset)

                when (val ctx = DbtJinjaUtils.detectCompletionContext(textBefore, allowMacros = !DbtChartsBoardLocator.isBoardFile(file.virtualFile))) {
                    is DbtJinjaUtils.CompletionContext.Ref -> completeRef(ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.SourceName -> completeSourceName(ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.SourceTable -> completeSourceTable(ctx.sourceName, ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.Macro -> completeMacro(ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.Column ->
                        completeColumn(ctx.qualifier, ctx.prefix, file.text, index, result)
                    null -> return
                }
            }
        })
    }

    private fun completeRef(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, node) in index.nodes) {
            if (node.resourceType == "test") continue
            matcher.addElement(
                LookupElementBuilder.create(node.name)
                    .withTypeText(node.resourceType)
                    .withTailText(" (${node.packageName})", true)
                    .withIcon(AllIcons.Nodes.DataTables)
            )
        }
    }

    /**
     * Columns of the table the qualifier stands for: `fct_orders.` after `FROM {{ ref('fct_orders') }}`,
     * or an alias for it. Silent when the qualifier isn't a table this SQL selects from, or when the
     * manifest/catalog knows no columns for it — a wrong list is worse than none.
     */
    private fun completeColumn(
        qualifier: String,
        prefix: String,
        sql: String,
        index: ManifestIndex,
        result: CompletionResultSet,
    ) {
        val columns = when (val target = SqlRefScope.of(sql)[qualifier]) {
            null -> return
            is SqlRefScope.Target.Model ->
                index.nodes.values.firstOrNull { it.name == target.name && it.resourceType != "test" }?.columns
            is SqlRefScope.Target.Source ->
                index.sources.values.firstOrNull { it.sourceName == target.source && it.name == target.table }?.columns
        } ?: return
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, column) in columns) {
            matcher.addElement(
                LookupElementBuilder.create(column.name)
                    .withTypeText(column.dataType ?: "column")
                    .withTailText(column.description.takeIf { it.isNotBlank() }?.let { " $it" }, true)
                    .withIcon(AllIcons.Nodes.Field)
            )
        }
    }

    private fun completeSourceName(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        val names = index.sources.values.map { it.sourceName }.distinct()
        for (name in names) {
            matcher.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText("source")
                    .withIcon(AllIcons.Nodes.DataTables)
            )
        }
    }

    private fun completeSourceTable(sourceName: String, prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, source) in index.sources) {
            if (source.sourceName == sourceName) {
                matcher.addElement(
                    LookupElementBuilder.create(source.name)
                        .withTypeText(source.schema ?: "")
                        .withIcon(AllIcons.Nodes.DataTables)
                )
            }
        }
    }

    private fun completeMacro(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, macro) in index.macros) {
            if (macro.name.startsWith("__")) continue
            val argsText = if (macro.arguments.isNotEmpty()) {
                macro.arguments.joinToString(", ") { it.name }
            } else ""
            matcher.addElement(
                LookupElementBuilder.create(macro.name)
                    .withTailText("($argsText)", true)
                    .withTypeText(macro.packageName)
                    .withIcon(AllIcons.Nodes.Function)
            )
        }
    }
}
