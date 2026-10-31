package com.github.noamm9.features.impl.floor7

import com.github.noamm9.config.types.*
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.ColorUtils.lerpColor
import com.github.noamm9.utils.MathUtils.vec
import com.github.noamm9.utils.Utils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderBoundingBox
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import java.awt.Color

object TerminalESP: Feature(
    "Highlights the interactable hitboxes of the terminals in F7/M7",
    //#if LEGIT
    //$name = "Terminal Highlight",
    //#endif
    jsonName = "Terminal ESP"
) {
    private val mode by DropdownSetting("Mode", 1, listOf("Outline", "Fill", "Filled Outline"))
    private val phase by ToggleSetting("Phase", false)
    private val fillColor by ColorSetting("Fill Color", Utils.favoriteColor).hideIf { mode.value == 0 }
    private val outlineColor by ColorSetting("Outline Color", Utils.favoriteColor).hideIf { mode.value == 1 }
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1).hideIf { mode.value == 1 }

    /// fork: with Engineer Client, the terminals your class has to do are drawn in Your Color
    private val roleColors by ToggleSetting("Role Colors", true).withDescription("With Engineer Client, the terminals your class has to do (its line of Quality PF's roles) are drawn in Your Color; the others keep the colors above.")
    private val mineColor by ColorSetting("Your Color", Color(255, 0, 0, 120)).hideIf { ! roleColors.value }

    private val flashOnHit by ToggleSetting("Flash On Click", true)
    private val flashColor by ColorSetting("Flash Color", Color.RED).hideIf { ! flashOnHit.value }

    private val terminalPositions = listOf(
        listOf(vec(110, 113, 73), vec(110, 119, 79), vec(90, 112, 92), vec(90, 122, 101)),
        listOf(vec(68, 109, 122), vec(59, 119, 123), vec(47, 109, 122), vec(39, 108, 142), vec(40, 124, 123)),
        listOf(vec(- 2, 109, 112), vec(- 2, 119, 93), vec(18, 123, 93), vec(- 2, 109, 77)),
        listOf(vec(41, 109, 30), vec(44, 121, 30), vec(67, 109, 30), vec(72, 114, 47))
    )

    private val terminals = mutableMapOf<ArmorStand, HitboxInfo>()

    override fun init() {
        register<WorldChangeEvent> { terminals.clear() }

        register<MainThreadPacketReceivedEvent.Post> {
            if (LocationUtils.dungeonFloorNumber != 7 || LocationUtils.F7Phase != 3) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            val entity = level.getEntity(packet.id) as? ArmorStand ?: return@register
            val name = entity.customName?.unformattedText

            if (name == "Inactive Terminal") {
                for ((section, posList) in terminalPositions.withIndex()) {
                    val at = posList.indexOfFirst { entity.distanceToSqr(it) <= 1.5 }
                    if (at < 0) continue
                    terminals.putIfAbsent(entity, HitboxInfo(section + 1, at + 1, terminalBlock(entity)))
                }
            }
            else if (name == "Terminal Active") {
                terminals.remove(entity)
            }
        }

        register<PacketEvent.Sent> {
            if (! flashOnHit.value) return@register
            val packet = event.packet as? ServerboundInteractPacket ?: return@register
            val entity = level.getEntity(packet.entityId) as? ArmorStand ?: return@register
            terminals[entity]?.hit()
        }

        register<PacketEvent.Sent> {
            if (! flashOnHit.value) return@register
            val packet = event.packet as? ServerboundAttackPacket ?: return@register
            val entity = level.getEntity(packet.entityId) as? ArmorStand ?: return@register
            terminals[entity]?.hit()
        }

        register<RenderWorldEvent> {
            if (! LocationUtils.inDungeon || LocationUtils.F7Phase != 3) return@register
            val section = LocationUtils.P3Section ?: return@register

            val drawFill = mode.value == 1 || mode.value == 2
            val drawOutline = mode.value == 0 || mode.value == 2
            val ecBoxes = ecHitboxesOn()

            for ((entity, info) in terminals) {
                if (info.section != section) continue
                val progress = if (flashOnHit.value) info.update() else 0f

                val mine = roleColors.value && isMine(info.section, entity) == 1
                val baseOutline = outlineColor.value
                val baseFill = fillColor.value
                val currentOutline = if (progress > 0f) lerpColor(baseOutline, flashColor.value, progress) else baseOutline
                val currentFill = if (progress > 0f) lerpColor(baseFill, flashColor.value, progress) else baseFill

                // fork: your own terminals fill the terminal itself, and nothing is drawn on the stand while Engineer
                // Client's Terminal Hitboxes is on - its box is the one to read, and it was coming out under this one
                if (mine) event.ctx.renderBlock(
                    pos = info.block,
                    color = mineColor.value,
                    outline = false,
                    fill = true,
                    phase = phase.value,
                    lineWidth = 4.0
                )

                // fork: Engineer Client's Terminal Hitboxes draws this same stand box and comes out on top of it, so while
                // it is on the box is left to it: only the role's block above is drawn here
                if (ecBoxes) continue

                event.ctx.renderBoxBounds(
                    entity.renderBoundingBox,
                    currentOutline,
                    currentFill,
                    outline = drawOutline,
                    fill = drawFill,
                    phase = phase.value,
                    lineWidth = lineWidth.value
                )
            }
        }
    }

    /// fork: the command block a terminal is: the nearest one around its stand. The stand itself floats in the air in
    /// front of the terminal, so the block the stand is in (or the one under it) is not the terminal.
    private fun terminalBlock(entity: ArmorStand): BlockPos {
        val origin = entity.blockPosition()
        val level = entity.level()
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in - 2..2) for (dy in - 2..2) for (dz in - 2..2) {
            val at = origin.offset(dx, dy, dz)
            if (! level.hasChunkAt(at)) continue
            val block = level.getBlockState(at).block
            if (block != Blocks.COMMAND_BLOCK && block != Blocks.CHAIN_COMMAND_BLOCK && block != Blocks.REPEATING_COMMAND_BLOCK) continue
            val distance = entity.position().distanceToSqr(at.x + 0.5, at.y + 0.5, at.z + 0.5)
            if (distance < bestDistance) {
                bestDistance = distance
                best = at
            }
        }

        return best ?: origin.takeIf { ! level.getBlockState(it).isAir } ?: origin.below()
    }

    /// fork: Engineer Client's Terminal Hitboxes is on, looked up by name so this runs without Engineer Client. The
    /// INSTANCE field is the object's own: getMethod finds the inherited getEnabled on Odin's Module, and Module has no
    /// INSTANCE, so both have to come from the class that was looked up.
    private val ecHitboxes by lazy {
        runCatching {
            val type = Class.forName("com.engineerclient.practice.TerminalHitboxes")
            type.getField("INSTANCE").get(null) to type.getMethod("getEnabled")
        }.getOrNull()
    }

    private fun ecHitboxesOn(): Boolean = runCatching {
        val (instance, enabled) = ecHitboxes ?: return@runCatching false
        enabled.invoke(instance) as? Boolean == true
    }.getOrDefault(false)

    /// one of your class's jobs, 0 when it is not, -1 when the class (or Engineer Client) is not known.
    private val isMineMethod by lazy {
        runCatching {
            Class.forName("com.engineerclient.rotation.TermRoleMarks")
                .getMethod("isMine", String::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun isMine(section: Int, e: ArmorStand): Int =
        runCatching { isMineMethod?.invoke(null, "terminal", section, e.x, e.y, e.z) as? Int }.getOrNull() ?: - 1

    private data class HitboxInfo(val section: Int, val number: Int, val block: BlockPos) {
        private val flashAnimation = Animation(FLASH_ANIM_MS)
        private var lastHitTime = 0L

        companion object {
            const val FLASH_ANIM_MS = 200L
            const val FLASH_HOLD_MS = 250L
        }

        fun hit() {
            lastHitTime = System.currentTimeMillis()
        }

        fun update(): Float {
            val target = if (System.currentTimeMillis() - lastHitTime < FLASH_HOLD_MS) 1f else 0f
            flashAnimation.update(target)
            return flashAnimation.value
        }
    }
}