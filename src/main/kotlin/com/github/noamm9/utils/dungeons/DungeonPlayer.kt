package com.github.noamm9.utils.dungeons

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.dungeons.map.handlers.DungeonScanner
import com.github.noamm9.utils.dungeons.map.utils.MapUtils
import com.github.noamm9.utils.MathUtils
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

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

    // fork: the map only carries a new marker position every few packets, so the marker is eased
    // towards it. The easing is evaluated at READ time instead of being written by a ticking
    // coroutine: a timer loop sleeps in whole scheduler ticks (~16 ms on Windows even for delay(10)),
    // which left a visible step in the head motion at frame rates above that. Reading the pose means
    // every frame gets its own exact value, and it also lets a leap snap instead of sliding.
    private data class MapPose(val x: Float, val z: Float, val yaw: Float)
    private class MapAnim(val from: MapPose, val to: MapPose, val startMs: Long, val durationMs: Long)

    @Volatile private var mapPose = MapPose(0f, 0f, 0f)
    @Volatile private var mapAnim: MapAnim? = null

    val mapX: Float get() = currentPose().x
    val mapZ: Float get() = currentPose().z
    val yaw: Float get() = currentPose().yaw
    var icon = ""
    var hasMapPosition = false

    private fun currentPose(now: Long = System.currentTimeMillis()): MapPose {
        val anim = mapAnim ?: return mapPose
        if (anim.durationMs <= 0L) return anim.to
        val progress = ((now - anim.startMs).toFloat() / anim.durationMs).coerceIn(0f, 1f)
        if (progress >= 1f) return anim.to
        return MapPose(
            MathUtils.lerp(anim.from.x, anim.to.x, progress).toFloat(),
            MathUtils.lerp(anim.from.z, anim.to.z, progress).toFloat(),
            MathUtils.interpolateYaw(anim.from.yaw, anim.to.yaw, progress),
        )
    }

    /// Move the marker to a new map position. `durationMs` of 0 applies it instantly, which is what
    /// a leap or a teleport needs: easing a jump would draw the player between two rooms for the
    /// length of the animation, and everything that reads the position (room attribution, the leap
    /// menu, `getRealPos`) would agree with the wrong room for that time.
    fun setMapPosition(x: Float, z: Float, newYaw: Float, durationMs: Long) {
        val current = currentPose()
        if (current.x == x && current.z == z && current.yaw == newYaw) return
        if (durationMs <= 0L) {
            mapAnim = null
            mapPose = MapPose(x, z, newYaw)
        }
        else mapAnim = MapAnim(current, MapPose(x, z, newYaw), System.currentTimeMillis(), durationMs)
    }

    fun resetMapPosition() {
        mapAnim = null
        mapPose = MapPose(0f, 0f, 0f)
    }

    fun getRealPos(): Vec3? {
        if (!hasMapPosition) return null
        return Vec3(
            (mapX - MapUtils.startCorner.first) / MapUtils.coordMultiplier + DungeonScanner.startX - 15,
            entity?.y ?: .0,
            (mapZ - MapUtils.startCorner.second) / MapUtils.coordMultiplier + DungeonScanner.startZ - 15
        )
    }

    val clearedRooms = mutableSetOf<String>() to mutableSetOf<String>()
    val deaths = mutableListOf<String>()
    var secretsBeforeRun = 0L
}
