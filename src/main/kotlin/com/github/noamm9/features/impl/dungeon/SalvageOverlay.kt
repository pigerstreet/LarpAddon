package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.customData
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.highlight
import java.awt.Color
import kotlin.jvm.optionals.getOrNull

object SalvageOverlay: Feature("Highlights salvageable dungeon gear.") {
    private val under50 by ColorSetting("Highlight Color", Color.CYAN.withAlpha(160))
    private val base50 by ColorSetting("50% stats Color", Color.RED.withAlpha(160))

    private val blacklist = setOf("ICE_SPRAY_WAND")

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            if (! LocationUtils.inSkyblock) return@register
            val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
            /// fork: this runs for every slot of every container screen on every frame, so the checks go
            /// cheapest-and-most-selective first. Only dungeon gear carries `baseStatBoostPercentage`, so
            /// almost every stack now bails after one nbt read instead of also paying for `skyblockId`
            /// (a second deep copy of the same nbt), a display name, and two lists built by `getArmor`.
            /// All four are pure predicates, so the order they are tested in does not change the outcome.
            val statBoost = stack.customData.getInt("baseStatBoostPercentage").getOrNull() ?: return@register
            if (stack.hoverName.string.contains("✪")) return@register
            if (stack.skyblockId in blacklist) return@register
            if (stack in PlayerUtils.getArmor()) return@register
            event.slot.highlight(event.context, if (statBoost == 50) base50.value else under50.value, 1)
        }
    }
}