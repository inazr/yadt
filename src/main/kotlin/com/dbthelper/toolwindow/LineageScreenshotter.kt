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
     * Device-pixel column range to keep when trimming a full-width capture to
     * [leftCss]..[rightCss] (on-screen CSS px). The captured image is device-resolution
     * (Retina), so CSS coords are scaled by imageWidth/componentWidthCss. Bounds are
     * clamped to [0, imageWidth]. Returns null when cropping is a no-op or invalid —
     * non-positive component width, the range covering the whole width, or width < 1px —
     * meaning "use the full image".
     */
    fun cropBounds(imageWidth: Int, componentWidthCss: Int, leftCss: Double, rightCss: Double): IntRange? {
        if (componentWidthCss <= 0 || imageWidth <= 0) return null
        val scale = imageWidth.toDouble() / componentWidthCss
        val x = Math.round(leftCss * scale).toInt().coerceIn(0, imageWidth)
        val right = Math.round(rightCss * scale).toInt().coerceIn(0, imageWidth)
        if (right - x < 1) return null
        if (x <= 0 && right >= imageWidth) return null
        return x until right
    }

    /**
     * Trim [image] horizontally to [leftCss]..[rightCss] (CSS px), keeping full height.
     * Returns [image] unchanged when [cropBounds] decides cropping is a no-op.
     */
    fun cropHorizontally(image: BufferedImage, componentWidthCss: Int, leftCss: Double, rightCss: Double): BufferedImage {
        val range = cropBounds(image.width, componentWidthCss, leftCss, rightCss) ?: return image
        return image.getSubimage(range.first, 0, range.last - range.first + 1, image.height)
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
