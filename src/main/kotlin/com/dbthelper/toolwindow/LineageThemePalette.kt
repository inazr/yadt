package com.dbthelper.toolwindow

import java.awt.Color

/**
 * The webview's CSS palette, derived from the live IDE theme so the graph blends with whatever
 * Look-and-Feel is configured (e.g. a dark-blue theme), instead of two hard-coded black/white
 * palettes. Only background, foreground and accent come from the theme — every L&F provides
 * them — and the remaining surfaces/borders/muted text are derived by lightening, darkening or
 * blending, so the result can never break on a missing theme key. Semantic node colors
 * (status/resource bars) are deliberately not themed.
 */
object LineageThemePalette {

    fun cssVars(bg: Color, fg: Color, accent: Color): Map<String, Any> {
        val isDark = luminance(bg) < 0.5
        val dir = if (isDark) 1.0 else -1.0

        val cardBg = shift(bg, 0.06 * dir)
        val cardBorder = shift(bg, 0.20 * dir)
        val cardIconBg = shift(bg, -0.03 * dir)
        val muted = blend(fg, bg, 0.45)
        val edge = blend(fg, bg, 0.62)

        return mapOf(
            "isDark" to isDark,
            "vars" to mapOf(
                "--bg-color" to hex(bg),
                "--text-color" to hex(fg),
                "--card-name" to hex(fg),
                "--card-schema" to hex(muted),
                "--card-bg" to hex(cardBg),
                "--card-border" to hex(cardBorder),
                "--card-icon-bg" to hex(cardIconBg),
                "--card-icon-fg" to hex(muted),
                "--card-selected-border" to hex(accent),
                "--card-selected-bg" to hex(cardBg),
                "--tooltip-bg" to hex(cardBg),
                "--tooltip-border" to hex(cardBorder),
                "--tooltip-text" to hex(fg),
                "--tooltip-name" to hex(fg),
                "--tooltip-detail" to hex(muted),
                "--btn-bg" to hex(cardBg),
                "--btn-border" to hex(cardBorder),
                "--btn-text" to hex(fg),
                "--btn-hover" to hex(shift(cardBg, 0.08 * dir)),
                "--edge-color" to hex(edge),
                "--loading-color" to hex(muted),
                "--stub-bg" to hex(shift(bg, 0.03 * dir)),
                "--stub-border" to hex(cardBorder),
                "--stub-text" to hex(muted)
            )
        )
    }

    private fun luminance(c: Color): Double =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    /** Lighten ([amount] > 0, toward white) or darken ([amount] < 0, toward black) [c]. */
    private fun shift(c: Color, amount: Double): Color =
        if (amount >= 0) blend(Color.WHITE, c, amount) else blend(Color.BLACK, c, -amount)

    /** Linear interpolation: [t] of [a] mixed with (1-[t]) of [b]. */
    private fun blend(a: Color, b: Color, t: Double): Color {
        val tt = t.coerceIn(0.0, 1.0)
        fun mix(x: Int, y: Int) = Math.round(x * tt + y * (1 - tt)).toInt().coerceIn(0, 255)
        return Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
    }

    private fun hex(c: Color): String = "#%06x".format(0xFFFFFF and c.rgb)
}
