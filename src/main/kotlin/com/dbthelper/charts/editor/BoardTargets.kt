package com.dbthelper.charts.editor

import com.dbthelper.charts.DbtChartsYaml
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

sealed interface Target {
    val mapping: YAMLMapping
    data class Board(override val mapping: YAMLMapping) : Target
    data class Chart(val id: String, override val mapping: YAMLMapping) : Target
}

/** Where the Design tab can point in a board file, and how it reads values. */
object BoardTargets {

    fun root(file: YAMLFile): YAMLMapping? = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping

    fun targets(file: YAMLFile): List<Target> {
        val root = root(file) ?: return emptyList()
        val charts = PsiTreeUtil.findChildrenOfType(root, YAMLMapping::class.java)
            .filter(DbtChartsYaml::isChartMapping)
            .map { Target.Chart((it.parent as YAMLKeyValue).keyText, it) }
        return listOf(Target.Board(root)) + charts
    }

    fun targetAt(file: YAMLFile, offset: Int): Target? {
        val element = file.findElementAt(offset)
        var mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
        while (mapping != null) {
            if (DbtChartsYaml.isChartMapping(mapping)) return Target.Chart((mapping.parent as YAMLKeyValue).keyText, mapping)
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java)
        }
        return root(file)?.let(Target::Board)
    }

    fun keyValue(mapping: YAMLMapping, path: List<String>): YAMLKeyValue? {
        var current: YAMLMapping = mapping
        path.dropLast(1).forEach { current = current.getKeyValueByKey(it)?.value as? YAMLMapping ?: return null }
        return current.getKeyValueByKey(path.last())
    }

    fun valueText(mapping: YAMLMapping, path: List<String>): String? =
        keyValue(mapping, path)?.value?.let { (it as? YAMLScalar)?.textValue ?: it.text }

    fun keyOffset(mapping: YAMLMapping, path: List<String>): Int? = keyValue(mapping, path)?.key?.textRange?.startOffset
}
