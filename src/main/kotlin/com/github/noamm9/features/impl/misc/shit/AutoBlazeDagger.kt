package com.github.noamm9.features.impl.misc.shit

//#if CHEAT

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.event.impl.*
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.EntityHitResult

object AutoBlazeDagger: Feature("Automatically swaps to the correct dagger for blaze!") {
    private val swapDelay by SliderSetting("Swap Delay", 100, 0, 300, 1).withDescription("Delay to swap in miliseconds")
    private var lastSwap = System.currentTimeMillis()
    private var clicked = false

    override fun init() {
        register<TickEvent.Start> {
            if (LocationUtils.world != WorldType.CrimsonIsle) return@register
            val hit = mc.hitResult as? EntityHitResult ?: return@register

            // fork: this ran once per TICK in the Crimson Isle, and it did the same work
            // twice over. `filter` walked every rendered entity and built a whole new List,
            // then `forEach` used only its first usable entry - the `return@register` below
            // fires as soon as one armour stand yields a shield and a dagger. So the scan
            // was paid in full to produce a list that was almost entirely discarded.
            //
            // Iterating directly gives the same answer - same order, same first match, the
            // `continue`s are the old `return@forEach` - while allocating nothing and
            // stopping at the first hit rather than at the end of the entity list.
            for (armor in level.entitiesForRendering()) {
                if (armor !is ArmorStand || armor.distanceTo(hit.entity) >= 5) continue
                val targetShield = HellionShield.fromText(armor.displayName.unformattedText) ?: continue
                val dagger = Dagger.entries.find { targetShield in it.shields } ?: continue
                trySwap(dagger, targetShield)
                return@register
            }
        }

        register<MainThreadPacketReceivedEvent.Pre>(EventPriority.HIGH) {
            val packet = event.packet as? ClientboundSetSubtitleTextPacket ?: return@register
            Dagger.updateActiveFromTitle(packet.text.unformattedText) ?: return@register
            clicked = false
        }

        register<NoammDebugFlagEvent.Add> {
            if (event.flag != "dagger") return@register
            val dagger = Dagger.fromStack(player.mainHandItem) ?: return@register

            ThreadUtils.scheduledTask(4) {
                trySwap(dagger.other(), dagger.activeShield().other())
            }
            event.cancel()
        }
    }

    private fun trySwap(dagger: Dagger, targetShield: HellionShield) {
        if (System.currentTimeMillis() - lastSwap < swapDelay.value) return
        if (clicked) return
        val heldStack = PlayerUtils.getHotbarSlot(player.inventory.selectedSlot) ?: return
        val currentDagger = Dagger.fromStack(heldStack) ?: return

        for (i in 0 .. 8) {
            val stack = PlayerUtils.getHotbarSlot(i) ?: continue
            if (stack.skyblockId !in dagger.skyblockIds) continue
            if (player.inventory.selectedSlot == i) continue
            player.inventory.selectedSlot = i
            lastSwap = System.currentTimeMillis()
            return
        }

        if (currentDagger == dagger && dagger.activeShield() != targetShield) {
            PlayerUtils.rightClick()
            dagger.activeShield().active = false
            dagger.activeShield().other().active = true
            lastSwap = System.currentTimeMillis()
            clicked = true
            return
        }
    }
}
//#endif