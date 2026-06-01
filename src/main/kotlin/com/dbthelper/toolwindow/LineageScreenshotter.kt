package com.dbthelper.toolwindow

import java.awt.Component
import java.awt.Image
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage

/**
 * Captures the lineage webview to an image and copies it to the system clipboard.
 * Kept separate from [LineageTab] so the tab only orchestrates; this object holds the
 * native-capture details and the one piece of branchable logic ([pickLargestVariant]).
 */
object LineageScreenshotter {

    /**
     * Grab the on-screen pixels of [component] into a [BufferedImage].
     *
     * Returns null when the component is not actually on screen (e.g. the tool window
     * is hidden) or has zero size — the caller treats that as "panel not visible".
     * Uses a multi-resolution capture and the largest variant so the result is crisp
     * on HiDPI/Retina displays rather than the down-scaled logical-resolution image.
     *
     * May throw (e.g. AWTException / SecurityException in a headless or locked-down
     * environment); the caller is expected to catch and report.
     */
    fun capture(component: Component): BufferedImage? {
        if (!component.isShowing) return null
        val size = component.size
        if (size.width <= 0 || size.height <= 0) return null
        val origin = component.locationOnScreen
        val rect = Rectangle(origin.x, origin.y, size.width, size.height)
        val capture = Robot().createMultiResolutionScreenCapture(rect)
        return toBufferedImage(pickLargestVariant(capture.resolutionVariants))
    }

    /** Put [image] on the system clipboard as an image (pasteable into docs/Slack/etc.). */
    fun copyToClipboard(image: BufferedImage) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                return image
            }
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    /**
     * Pick the highest-resolution variant (largest pixel area) from a
     * MultiResolutionImage's variants — on HiDPI/Retina the largest is the crisp,
     * device-resolution image. Keeps the first element on ties. Throws if empty.
     */
    fun pickLargestVariant(variants: List<Image>): Image {
        require(variants.isNotEmpty()) { "no resolution variants to choose from" }
        return variants.maxBy {
            it.getWidth(null).toLong() * it.getHeight(null).toLong()
        }
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return buffered
    }
}
