package com.github.noamm9.features.impl.floor7

//#if CHEAT

import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

object IHateDiorite: Feature("Replaces the pillars in P2 with glass") {
    private val pillars = arrayOf(
        Pillar(BlockPos(46, 169, 41), Blocks.LIME_STAINED_GLASS.defaultBlockState()),
        Pillar(BlockPos(46, 169, 65), Blocks.YELLOW_STAINED_GLASS.defaultBlockState()),
        Pillar(BlockPos(100, 169, 65), Blocks.PURPLE_STAINED_GLASS.defaultBlockState()),
        Pillar(BlockPos(100, 169, 41), Blocks.RED_STAINED_GLASS.defaultBlockState())
    )

    private val DIORITE_BLOCKS = setOf(Blocks.DIORITE, Blocks.POLISHED_DIORITE)

    // fork: the pillars do not move, so the 7,448 block reads per tick (4 pillars x 7 x 38 x 7)
    // only ever need doing once per phase-2 entry. Re-scan on a slow timer to catch a chunk that
    // was not loaded on the first pass, and reset on phase change so the next run rescans.
    private var scannedPhase: Int? = null
    private var rescanAt = 0L

    override fun init() {
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