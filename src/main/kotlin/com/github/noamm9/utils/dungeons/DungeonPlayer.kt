package com.github.noamm9.utils.dungeons

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier

data class DungeonPlayer(
    var name: String,
    var clazz: DungeonClass,
    var clazzLvl: Int,
    var skin: Identifier = mc.player !!.skin.body.texturePath(),
    var isDead: Boolean = false,
) {
    var entity: AbstractClientPlayer? = null
        set(value) {
            skin = value?.skin?.body?.texturePath() ?: skin
            field = value
        }

    var mapX = 0f
    var mapZ = 0f
    var yaw = 0f
    var icon = ""

    val clearedRooms = mutableSetOf<String>() to mutableSetOf<String>()
    val deaths = mutableListOf<String>()
    /// fork: -1 is "no start total" (the lookup failed or never ran); 0 is a real lifetime count.
    var secretsBeforeRun = -1L
}
