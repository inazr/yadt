package com.dbthelper.toolwindow

import java.awt.Image

/**
 * Captures the lineage webview to an image and copies it to the system clipboard.
 * Kept separate from [LineageTab] so the tab only orchestrates; this object holds the
 * native-capture details and the one piece of branchable logic ([pickLargestVariant]).
 */
object LineageScreenshotter {

    /**
     * Pick the highest-resolution variant (largest pixel area) from a
     * MultiResolutionImage's variants — on HiDPI/Retina the largest is the crisp,
     * device-resolution image. Keeps the first element on ties. Throws if empty.
     */
    fun pickLargestVariant(variants: List<Image>): Image {
        require(variants.isNotEmpty()) { "no resolution variants to choose from" }
        return variants.maxByOrNull {
            it.getWidth(null).toLong() * it.getHeight(null).toLong()
        }!!
    }
}
