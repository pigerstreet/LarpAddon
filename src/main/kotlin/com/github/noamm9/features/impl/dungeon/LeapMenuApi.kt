package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.NoammAddons
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.DoubleSupplier

/**
 * fork: lets another mod add to the custom leap menu without patching it. engineerClient uses it for its leap target ring,
 * Click Delay, Leap Outline, Leap Preview, leap-corner vignette and POV Previews (FORK.md, "The leap menu has an API").
 * Other mods reach this by reflection, so only JDK and Minecraft types cross it, and nothing public here can be renamed
 * without changing them too.
 *
 * Box order is the menu's: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. "Screen space" is the GUI-scaled space a
 * HUD or a screen draws in before any pose change.
 */
object LeapMenuApi {
    /** Drawn each frame the menu is up, before it (the boxes or Map Leap), in screen space. */
    @JvmField val beforeMenu = CopyOnWriteArrayList<Consumer<GuiGraphicsExtractor>>()

    /** Drawn after the four boxes, in screen space. Not called in Map Leap, or while nobody is listed. */
    @JvmField val afterBoxes = CopyOnWriteArrayList<Consumer<GuiGraphicsExtractor>>()

    /** Any of these true: a mouse click in the menu does nothing. The click is still taken, so the chest slot under it is not clicked either. */
    @JvmField val clickBlockers = CopyOnWriteArrayList<BooleanSupplier>()

    /** Multiplies the boxes' size (the grid shrinks toward the middle of the screen). Read every frame. */
    @JvmField @Volatile var boxScale: DoubleSupplier = DoubleSupplier { 1.0 }

    /** Multiplies the alpha of the boxes' background (not the heads or the text). Read every frame. */
    @JvmField @Volatile var boxAlpha: DoubleSupplier = DoubleSupplier { 1.0 }

    // The layout, in the menu's own space (after Resolution and the scale below). LeapMenu's render reads these too.
    const val BOX_WIDTH = 128f * 1.3f
    const val BOX_HEIGHT = 80f * 0.8f
    const val PADDING = 40f

    /** The custom menu is what opens in a dungeon: the feature and its Leap Menu toggle are on, and this is a dungeon. */
    @JvmStatic fun isOn(): Boolean = LeapMenu.enabled && LeapMenu.customLeapMenu.value && LocationUtils.inDungeon

    /** The custom menu is on screen. */
    @JvmStatic fun isShowing(): Boolean = LeapMenu.isActive()

    /** Map Leap is on screen in place of the boxes. */
    @JvmStatic fun isMapMode(): Boolean = isShowing() && LeapMenu.mapLeapEnabled && ! LocationUtils.inBoss

    /** Who is in each box, by name, null where nobody is. While the menu is closed: who will be when it opens. */
    @JvmStatic fun order(): Array<String?> {
        if (isShowing()) return Array(4) { LeapMenu.players[it]?.player?.name }
        val sorted = LeapMenu.sortedTeammates()
        return Array(4) { sorted.getOrNull(it)?.name }
    }

    /** The four boxes, x0, y0, x1, y1 each, in screen space, where they are drawn (Menu Scale and [boxScale] included). */
    @JvmStatic fun boxes(): FloatArray {
        Resolution.refresh()
        val userScale = userScale()
        val k = userScale * Resolution.scale
        val out = FloatArray(16)
        offsets(Resolution.width / userScale, Resolution.height / userScale).forEachIndexed { i, (x, y) ->
            out[i * 4] = x * k
            out[i * 4 + 1] = y * k
            out[i * 4 + 2] = (x + BOX_WIDTH) * k
            out[i * 4 + 3] = (y + BOX_HEIGHT) * k
        }
        return out
    }

    /** The menu's scale on top of Resolution: its Menu Scale setting times [boxScale]. */
    internal fun userScale() = (LeapMenu.scale.value.toFloat() / 100f) * 2.0f * scaleHook()

    /** The boxes' top-left corners in the menu's own space; the same arithmetic as LeapMenu's render. */
    private fun offsets(screenWidth: Float, screenHeight: Float): List<Pair<Float, Float>> {
        val startX = (screenWidth - (BOX_WIDTH * 2 + PADDING)) / 2f
        val startY = (screenHeight - (BOX_HEIGHT * 2 + PADDING)) / 2f
        return listOf(
            startX to startY,
            (startX + BOX_WIDTH + PADDING) to startY,
            startX to (startY + BOX_HEIGHT + PADDING),
            (startX + BOX_WIDTH + PADDING) to (startY + BOX_HEIGHT + PADDING)
        )
    }

    // Called by LeapMenu. A hook that throws is removed, with one log line, rather than breaking the menu every frame.

    internal fun drawBefore(ctx: GuiGraphicsExtractor) = beforeMenu.forEach { hook -> guard(beforeMenu, hook) { hook.accept(ctx) } }

    internal fun drawAfter(ctx: GuiGraphicsExtractor) = afterBoxes.forEach { hook -> guard(afterBoxes, hook) { hook.accept(ctx) } }

    internal fun clickBlocked() = clickBlockers.any { hook -> guard(clickBlockers, hook) { hook.asBoolean } == true }

    internal fun alpha(): Float = runCatching { boxAlpha.asDouble.toFloat() }.getOrDefault(1f).coerceIn(0f, 1f)

    private fun scaleHook(): Float = runCatching { boxScale.asDouble.toFloat() }.getOrDefault(1f).coerceIn(0.1f, 1f)

    private fun <H, R> guard(list: MutableList<H>, hook: H, call: () -> R): R? = try {
        call()
    }
    catch (t: Throwable) {
        list.remove(hook)
        NoammAddons.logger.error("Leap menu hook ${hook!!::class.java.name} threw and was removed", t)
        null
    }
}
