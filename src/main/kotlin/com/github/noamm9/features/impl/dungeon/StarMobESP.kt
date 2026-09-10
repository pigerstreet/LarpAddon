package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.init.ModCompatibility
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.equalsOneOf
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.LocationUtils.inBoss
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

        /// fork: this highlight is a glow, and Minecraft only decides whether an entity glows while it is
        /// extracting that entity to draw it. EntityCulling skips that extraction for mobs it can't see and
        /// draws only their nametag instead, so a starred mob behind a wall got no glow - and no Box3D box,
        /// which reads the same flag - until it came into view. Each tick the tracked mobs are marked
        /// visible to EntityCulling, which holds for a second; bats and fels are included while their
        /// toggles are on. Without EntityCulling installed this returns on its first line.
        register<TickEvent.Start> {
            if (! ModCompatibility.canKeepVisible) return@register
            if (! LocationUtils.inDungeon || inBoss) return@register
            for (id in starMobs) level.getEntity(id)?.let(ModCompatibility::keepVisible)
            if (! espBats.value && ! espFels.value) return@register
            for (entity in level.entitiesForRendering()) {
                if (getColor(entity) != null) ModCompatibility.keepVisible(entity)
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

            if (event.entity.id in starMobs) {
                event.color = starMobColor.value
                return@register
            }

            getColor(event.entity)?.let {
                event.color = it
            }
        }
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