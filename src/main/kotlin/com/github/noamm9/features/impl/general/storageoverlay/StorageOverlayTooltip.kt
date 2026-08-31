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
    /** The scale to draw a tooltip at, 1 while the overlay is not the thing on screen. */
    @JvmStatic
    fun scale(): Float {
        /// while the overlay is up it cancels vanilla's slots, labels and carried item, so every tooltip
        /// drawn over this screen is one of its own - including the cached pages, which never set
        /// `hoveredOverlayItem` because that only tracks the page the container actually has open
        val screen = UMinecraft.currentScreenObj as? ContainerScreen ?: return 1f
        StorageOverlay.activeFor(screen) ?: return 1f
        /// the slider is a percentage of the overlay's own size, so 100 keeps a tooltip exactly in proportion
        return Resolution.scale * StorageOverlay.scaleSetting.value * (StorageOverlay.tooltipScaleSetting.value / 100f)
    }
}
