package com.github.noamm9.features.impl.general.storageoverlay

import com.github.noamm9.ui.utils.Resolution
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen

/**
 * Scales the tooltip of an item inside [StorageOverlay] so it matches the overlay it belongs to.
 *
 * The overlay draws itself inside [Resolution]'s space scaled again by [StorageOverlay.scaleSetting],
 * while vanilla draws tooltips in plain gui space. A shrunken overlay therefore ends up with full sized
 * tooltips covering it, so tooltips get the overlay's total transform applied on top.
 */
object StorageOverlayTooltip {
    /** The scale to draw a tooltip at, 1 for anything that is not an item inside the overlay. */
    @JvmStatic
    fun scale(): Float {
        val screen = UMinecraft.currentScreenObj as? ContainerScreen ?: return 1f
        val overlay = StorageOverlay.activeFor(screen) ?: return 1f
        if (overlay.hoveredOverlayItem == null) return 1f
        /// the slider is a percentage of the overlay's own size, so 100 keeps a tooltip exactly in proportion
        return Resolution.scale * StorageOverlay.scaleSetting.value * (StorageOverlay.tooltipScaleSetting.value / 100f)
    }
}
