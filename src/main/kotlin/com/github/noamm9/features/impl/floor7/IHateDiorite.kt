package com.github.noamm9.features.impl.floor7

//#if CHEAT

import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

object IHateDiorite: Feature("Replaces the pillars in P2 with glass") {
    private val pillars = arrayOf(
        Pillar(BlockPos(46, 169, 41), Blocks.STAINED_GLASS.lime().defaultBlockState()),
        Pillar(BlockPos(46, 169, 65), Blocks.STAINED_GLASS.yellow().defaultBlockState()),
        Pillar(BlockPos(100, 169, 65), Blocks.STAINED_GLASS.purple().defaultBlockState()),
        Pillar(BlockPos(100, 169, 41), Blocks.STAINED_GLASS.red().defaultBlockState())
    )

    private val DIORITE_BLOCKS = setOf(Blocks.DIORITE, Blocks.POLISHED_DIORITE)

    // fork: the 7,448 block reads (4 pillars x 7 x 38 x 7) only need doing when a block can have
    // changed, not every tick. The server puts diorite back only through a block or chunk packet, so
    // one of those in phase 2 rescans on the next tick; a slow timer covers anything else, and a
    // phase change resets so the next run rescans.
    private var scannedPhase: Int? = null
    private var rescanAt = 0L

    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (LocationUtils.F7Phase != 2) return@register
            val packet = event.packet
            if (packet is ClientboundBlockUpdatePacket || packet is ClientboundSectionBlocksUpdatePacket ||
                packet is ClientboundLevelChunkWithLightPacket) rescanAt = 0L
        }

        register<TickEvent.Start> {
            if (LocationUtils.F7Phase != 2) {
                scannedPhase = null
                return@register
            }
            val now = System.currentTimeMillis()
            if (scannedPhase == 2 && now < rescanAt) return@register
            scannedPhase = 2
            rescanAt = now + RESCAN_MS
            for (pillar in pillars) for (pos in pillar.area) {
                if (WorldUtils.getBlockAt(pos) !in DIORITE_BLOCKS) continue
                WorldUtils.setBlockAt(pos, pillar.glass)
            }
        }
    }

    private class Pillar(pos: BlockPos, val glass: BlockState) {
        val area = BlockPos.betweenClosed(
            pos.offset(- RADIUS, 0, - RADIUS),
            pos.offset(RADIUS, HEIGHT, RADIUS)
        )

        private companion object {
            const val RADIUS = 3
            const val HEIGHT = 37
        }
    }

    private const val RESCAN_MS = 5_000L
}
//#endif