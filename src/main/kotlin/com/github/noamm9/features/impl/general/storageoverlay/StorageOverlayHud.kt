package com.github.noamm9.features.impl.general.storageoverlay

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.PADDING
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.PAGE_WIDTH
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.PLAYER_HEIGHT
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.PLAYER_WIDTH
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.SCROLL_BAR_HEIGHT
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.SCROLL_BAR_WIDTH
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayScreen.Companion.SLOT_SIZE
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * Makes [StorageOverlay] movable and scalable from the hud editor.
 *
 * The overlay itself is drawn by [StorageOverlayScreen] on top of the container screen, so this
 * element never draws on the game hud ([shouldDraw] is always false) and only renders an outline
 * of the menu inside the editor.
 */
object StorageOverlayHud: HudElement() {
    /** Position of an overlay that was never moved, such an overlay stays centered on screen. */
    private const val AUTO = - 1f

    private val backgroundColor = Color(24, 24, 27)
    private val borderColor = Color(60, 60, 65)
    private val slotColor = Color(30, 30, 34).rgb
    private val slotBorderColor = Color(55, 55, 60).rgb
    private val scrollBarColor = Color(30, 30, 35, 180)
    private val scrollKnobColor = Color(120, 120, 130)

    override val name = "Storage Overlay"
    override val shouldDraw = false

    override val toggle: Boolean
        get() {
            /// the hud editor is the only thing reading this, so it is the moment to turn AUTO into a real position
            ensurePositioned()
            return StorageOverlay.enabled
        }

    /// negative values are clamped away, so AUTO can only come from the default or the config, never from a drag
    override var x = AUTO
        set(value) {
            field = if (value == AUTO) AUTO else value.coerceAtLeast(0f)
        }

    override var y = AUTO
        set(value) {
            field = if (value == AUTO) AUTO else value.coerceAtLeast(0f)
        }

    /** The hud scale and the feature's scale setting are the same value. */
    override var scale: Float
        get() = StorageOverlay.scaleSetting.value
        set(value) {
            StorageOverlay.scaleSetting.value = StorageOverlay.scaleSetting.snapToStep(value.toDouble())
        }

    private val positioned get() = x != AUTO && y != AUTO

    /// the editor's Reset drops every element at (20, 20); for a whole menu that is the corner, so
    /// this one goes back to being centered instead
    override fun reset() {
        x = AUTO
        y = AUTO
        scale = 1f
    }


    /// the overlay draws itself scaled, so its space is the resolution space divided by the scale
    private val screenWidth get() = (Resolution.width / scale).toInt()
    private val screenHeight get() = (Resolution.height / scale).toInt()

    /// mirrors the layout of StorageOverlayScreen.Measurements, used for the editor preview only
    private fun columnsFor(screenWidth: Int) = StorageOverlay.columnsSetting.value
        .coerceAtMost((screenWidth - PADDING) / (PAGE_WIDTH + PADDING))
        .coerceAtLeast(1)

    private fun panelWidthFor(columns: Int) = PAGE_WIDTH * columns + (columns - 1) * PADDING + 3 * PADDING + SCROLL_BAR_WIDTH

    private fun panelHeightFor(screenHeight: Int) = minOf(
        screenHeight - PLAYER_HEIGHT - minOf(80, screenHeight / 10),
        StorageOverlay.maxHeightSetting.value
    )

    private fun ensurePositioned() {
        if (positioned) return
        val panelWidth = panelWidthFor(columnsFor(screenWidth))
        val panelHeight = panelHeightFor(screenHeight) + PLAYER_HEIGHT
        x = (screenWidth - panelWidth) / 2f * scale
        y = (screenHeight - panelHeight) / 2f * scale
    }

    /** X of the menu in the overlay's own space, centered while it has no position of its own. */
    fun panelX(screenWidth: Int, panelWidth: Int): Int {
        if (! positioned) return screenWidth / 2 - panelWidth / 2
        return (x / scale).toInt().coerceAtMost(screenWidth - panelWidth).coerceAtLeast(0)
    }

    /** Y of the menu in the overlay's own space, centered while it has no position of its own. */
    fun panelY(screenHeight: Int, panelHeight: Int): Int {
        if (! positioned) return screenHeight / 2 - panelHeight / 2
        return (y / scale).toInt().coerceAtMost(screenHeight - panelHeight).coerceAtLeast(0)
    }

    override fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Float, Float> {
        val columns = columnsFor(screenWidth)
        val panelWidth = panelWidthFor(columns)
        val panelHeight = panelHeightFor(screenHeight)

        ctx.drawRect(0, 0, panelWidth, panelHeight, backgroundColor)
        ctx.drawBorder(0, 0, panelWidth, panelHeight, borderColor)

        drawPages(ctx, columns, panelHeight)
        drawScrollBar(ctx, columns, panelHeight)
        drawPlayerInventory(ctx, panelWidth, panelHeight)

        return panelWidth.toFloat() to (panelHeight + PLAYER_HEIGHT).toFloat()
    }

    /** Walks the cached pages, or a placeholder layout while nothing got cached yet. */
    private inline fun forEachPage(action: (name: String, rows: Int) -> Unit) {
        val data = StorageOverlay.storageMenuData
        if (data.isEmpty()) return repeat(6) { action(StoragePage(it).name, 5) }
        for ((page, inventory) in data) action(page.name, inventory?.rows ?: 1)
    }

    private fun drawPages(ctx: GuiGraphicsExtractor, columns: Int, panelHeight: Int) {
        val bottom = panelHeight - PADDING
        var column = 0
        var y = PADDING
        var rowHeight = 0

        forEachPage { name, rows ->
            val pageHeight = rows * SLOT_SIZE + 6 + mc.font.lineHeight
            if (y + pageHeight > bottom) return

            val x = PADDING + (PAGE_WIDTH + PADDING) * column
            ctx.drawString(name, x + 6f, y + 3f)
            drawSlotGrid(ctx, x + 2, y + 5 + mc.font.lineHeight, rows)

            rowHeight = maxOf(rowHeight, pageHeight)
            if (++ column >= columns) {
                column = 0
                y += rowHeight
                rowHeight = 0
            }
        }
    }

    private fun drawScrollBar(ctx: GuiGraphicsExtractor, columns: Int, panelHeight: Int) {
        val x = PADDING + PAGE_WIDTH * columns + (columns - 1) * PADDING + PADDING
        ctx.drawRect(x, PADDING, SCROLL_BAR_WIDTH, panelHeight - PADDING * 2, scrollBarColor)
        ctx.drawRect(x, PADDING, SCROLL_BAR_WIDTH, SCROLL_BAR_HEIGHT, scrollKnobColor)
    }

    private fun drawPlayerInventory(ctx: GuiGraphicsExtractor, panelWidth: Int, panelHeight: Int) {
        val x = (panelWidth / 2 - PLAYER_WIDTH / 2) + (PLAYER_WIDTH - 9 * SLOT_SIZE) / 2 - SLOT_SIZE / 2 + 1
        val y = panelHeight + 2 + 8

        drawSlotGrid(ctx, x - 1, y - 1, 3)
        drawSlotGrid(ctx, x - 1, y + 3 * SLOT_SIZE + 3, 1)
    }

    private fun drawSlotGrid(ctx: GuiGraphicsExtractor, x: Int, y: Int, rows: Int) {
        val width = 9 * SLOT_SIZE
        val height = rows * SLOT_SIZE

        ctx.fill(x, y, x + width, y + height, slotColor)
        for (column in 0 .. 9) {
            val lineX = x + column * SLOT_SIZE
            ctx.fill(lineX, y, lineX + 1, y + height, slotBorderColor)
        }
        for (row in 0 .. rows) {
            val lineY = y + row * SLOT_SIZE
            ctx.fill(x, lineY, x + width, lineY + 1, slotBorderColor)
        }
    }
}
