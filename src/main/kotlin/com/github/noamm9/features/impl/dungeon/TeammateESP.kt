package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.CheckEntityGlowEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderVec
import com.github.noamm9.utils.render.world.Render3D.renderString
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity

object TeammateESP: Feature(
    "Highlights your dungeon party.",
    //#if LEGIT
    //$name = "Teammate Highlight",
    //#endif
    jsonName = "Teammate ESP"
) {
    private val highlight by ToggleSetting("Highlight Teammates", true)
    private val drawName by ToggleSetting("Show Teammate Name", true)

    private val cache = HashMap<Int, Boolean>()

    override fun init() {
        register<CheckEntityGlowEvent> {
            if (! highlight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (event.entity !is AbstractClientPlayer) return@register
            if (event.entity.uuid.version() != 4) return@register

            for (teammate in DungeonListener.dungeonTeammates.toList()) {
                if (teammate.entity?.id != event.entity.id) continue
                event.color = teammate.clazz.color
            }
        }

        register<RenderWorldEvent> {
            /// fork: this clear used to sit at the bottom of the loop below, so the cache was only emptied
            /// on a frame that actually drew a name - and not at all once one of the guards under it had
            /// returned. Turn `Show Teammate Name` off, finish the run, or walk a floor on your own, and
            /// the entity ids cached while it was on stay cached for the rest of the session. Ids are
            /// handed out per world and reused, so whatever inherits one of them in the next lobby loses
            /// its nametag for no visible reason. Once per frame, ahead of anything that can return, is
            /// what the loop was reaching for.
            cache.clear()

            if (! drawName.value) return@register
            if (! LocationUtils.inDungeon) return@register
            for (teammate in DungeonListener.dungeonTeammatesNoSelf) {
                val entity = teammate.entity ?: continue
                val color = teammate.clazz.code
                val renderVec = entity.renderVec
                val distance = renderVec.distanceTo(player.renderVec)
                val scale = (distance * 0.12f).coerceAtLeast(1.0)

                event.ctx.renderString(
                    "&e[${teammate.clazz.name[0]}&e] $color${teammate.name}",
                    renderVec.x,
                    renderVec.y + entity.bbHeight + 0.7 + distance * 0.015f,
                    renderVec.z,
                    scale = scale,
                    phase = true
                )
            }
        }
    }

    @JvmStatic
    fun shouldHideNametag(entity: Entity): Boolean {
        /// fork: these three answer the same for every entity in the frame and cost a field read each, so
        /// they are asked before the cache rather than from inside it. A cached `true` can no longer
        /// outlive the toggle that produced it, and only the teammate lookup - the part that is actually
        /// worth remembering for the rest of the frame - ends up in the map.
        if (! enabled) return false
        if (! drawName.value) return false
        if (! LocationUtils.inDungeon) return false

        return cache.getOrPut(entity.id) {
            DungeonListener.dungeonTeammatesNoSelf.any { it.entity?.id == entity.id }
        }
    }
}