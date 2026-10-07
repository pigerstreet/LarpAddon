package com.github.noamm9.ui.hud

import java.awt.Color

/// fork: the timing and look of a vanilla title as Odin sets it (`setTimes(0, 20, 5)`): no fade in, 20 ticks fully
/// shown, 5 ticks fading out, drawn at 4x. HUD alerts use this so they look the same as Odin's but can be moved.
object AlertStyle {
    const val DURATION_MS = 1250L
    private const val FADE_OUT_MS = 250L

    const val DEFAULT_SCALE = 4f

    /** The y of a vanilla title's text, in unscaled gui units (it is drawn 10 units above the screen's centre, at 4x). */
    const val TITLE_Y_OFFSET = -40f

    fun alpha(until: Long): Int {
        val left = until - System.currentTimeMillis()
        return when {
            left <= 0L -> 0
            left >= FADE_OUT_MS -> 255
            // text with an alpha under 4 is drawn opaque, so never let the fade reach it
            else -> (left * 255 / FADE_OUT_MS).toInt().coerceAtLeast(8)
        }
    }

    fun color(until: Long) = Color(255, 255, 255, alpha(until))
}
