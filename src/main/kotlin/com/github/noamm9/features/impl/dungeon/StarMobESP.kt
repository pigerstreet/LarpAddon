package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.interfaces.IGlowingEntity
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.equalsOneOf
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.LocationUtils.inBoss
import com.github.noamm9.utils.render.RenderHelper.renderBoundingBox
import com.github.noamm9.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import java.awt.Color

object StarMobESP: Feature(
    "Highlights all starred mobs in a dungeon.",
    //#if LEGIT
    //$name = "Star Mob Highlight",
    //#endif
    jsonName = "Star Mob ESP"
) {
    private val espBats by ToggleSetting("Highlight Bats", true).withDescription("Highlights Bats in Dungeons.")
    private val espFels by ToggleSetting("Highlight Fels", false).withDescription("Highlights Fels, even when they are invisible.")

    private val starMobColor by ColorSetting("Star Mob Color", Color.YELLOW, false).section("General Colors").withDescription("Default color for all Starred mobs.")
    private val batColor by ColorSetting("Bat Color", Color.GREEN, false).withDescription("The color used for highlighted bats.").showIf { espBats.value }
    private val felColor by ColorSetting("Fel Color", Color.PINK, false).withDescription("The color used for fels.").showIf { espFels.value }

    //#if CHEAT
    /// fork: OdinClient's way of drawing these. A glow only exists while Minecraft is drawing the entity, so
    /// render distance, EntityCulling and every other renderer that skips a mob took its highlight with it.
    /// This draws a box straight from the mobs this feature already tracks, every frame, through walls.
    private val boxEsp by ToggleSetting("Box ESP", true).section("Render").withDescription("Draws boxes from the tracked mobs directly, so they show through walls at any distance. Off uses glow.")
    private val boxStyle by DropdownSetting("Box Style", 0, listOf("Outline", "Fill", "Filled Outline")).showIf { boxEsp.value }
    //#endif

    private val starMobs = HashSet<Int>()
    private val checked = HashSet<Int>()

    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            if (event.packet !is ClientboundSetEntityDataPacket) return@register
            val entity = level.getEntity(event.packet.id) ?: return@register
            if (entity is ArmorStand) {
                val name = entity.customName?.formattedText ?: return@register
                if (name.endsWith("§c❤") && name.contains("✯")) {
                    checkStarMob(entity, name)
                }
            }
            else if (entity is Player) {
                val name = mc.connection?.getPlayerInfo(entity.uuid)?.profile?.name ?: return@register
                if (name.equalsOneOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")) {
                    starMobs.add(entity.id)
                }
            }
        }

        register<EntityUnloadEvent> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            starMobs.remove(event.entity.id)
            checked.remove(event.entity.id)
        }

        register<WorldChangeEvent> {
            starMobs.clear()
            checked.clear()
        }

        register<CheckEntityGlowEvent> {
            if (! LocationUtils.inDungeon || inBoss) return@register

            event.color = if (event.entity.id in starMobs) starMobColor.value else getColor(event.entity) ?: return@register

            //#if CHEAT
            /// fork: with Box ESP on the box below is the highlight, so the glow is still answered - which keeps
            /// EntityCulling from culling the mob and freezing its movement - but cancelled, the same way Box3D
            /// does it: the render thread then draws no outline, and Box3D, which skips cancelled events, draws
            /// no second box. Its flag is cleared in case Box3D lit it before Box ESP was switched on.
            if (boxEsp.value) {
                (event.entity as IGlowingEntity).`noammaddons$isGlowing`(false)
                event.isCanceled = true
            }
            //#endif
        }

        //#if CHEAT
        register<RenderWorldEvent> {
            if (! boxEsp.value || ! LocationUtils.inDungeon || inBoss) return@register
            val outline = boxStyle.value.equalsOneOf(0, 2)
            val fill = boxStyle.value.equalsOneOf(1, 2)

            fun draw(entity: Entity, color: Color) {
                if (! entity.isAlive) return
                event.ctx.renderBoxBounds(entity.renderBoundingBox, color, color.withAlpha(60), outline, fill, phase = true, lineWidth = 2.0)
            }

            for (id in starMobs) level.getEntity(id)?.let { draw(it, starMobColor.value) }
            if (espBats.value || espFels.value) for (entity in level.entitiesForRendering()) getColor(entity)?.let { draw(entity, it) }
        }
        //#endif
    }

    private fun getColor(entity: Entity): Color? {
        if (entity is Bat) return if (espBats.value && ! entity.isInvisible && ! entity.isPassenger) batColor.value else null
        if (entity is EnderMan) return if (espFels.value && entity.customName?.string == "Dinnerbone") felColor.value else null
        return null
    }

    private fun checkStarMob(armorStand: Entity, name: String) {
        /// fork: a nametag used to be marked checked before its mob was looked for, so if the mob hadn't
        /// loaded yet on that first metadata packet the nametag was never looked at again. It is only
        /// recorded once a mob has actually been found, so the next health update retries it.
        if (armorStand.id in checked) return
        val name = name.removeFormatting().uppercase()
        // withermancers are always -3 to real entity the -1 and -2 are the wither skulls that they shoot
        val offset = if (name.contains("WITHERMANCER")) 3 else 1
        val id = armorStand.id - offset

        val mob = armorStand.level().getEntity(id)
        if (mob !is ArmorStand && id !in starMobs && mob != null) {
            starMobs.add(id)
            checked.add(armorStand.id)
            return
        }

        /// fork: nametag stands are markers, whose hitbox is `EntityDimensions.fixed(0, 0)`, so shifting it
        /// down a block searched a single point - a mob that had moved since its nametag last updated was
        /// missed. The box now reaches from the nametag two blocks down, which still contains that point.
        val possibleEntities = armorStand.level().getEntities(
            armorStand, armorStand.boundingBox.expandTowards(0.0, - 2.0, 0.0)
        ) { it !is ArmorStand && it !is ExperienceOrb }

        possibleEntities.find {
            ! starMobs.contains(it.id) && when (it) {
                is Player -> ! it.isInvisible && it.uuid.version() == 2 && it != player
                is WitherBoss -> false
                is AbstractArrow -> false
                else -> true
            }
        }?.let {
            starMobs.add(it.id)
            checked.add(armorStand.id)
        }
    }
}