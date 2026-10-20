package com.github.noamm9.utils.dungeons.map.handlers

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.mixin.IMapState
import com.github.noamm9.utils.*
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonListener.dungeonTeammatesNoSelf
import com.github.noamm9.utils.dungeons.DungeonPlayer
import com.github.noamm9.utils.dungeons.map.core.*
import com.github.noamm9.utils.dungeons.map.utils.MapUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.saveddata.maps.*

object MapUpdater: ISelfInit {
    override fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon) return@register
            val packet = event.packet as? ClientboundMapItemDataPacket ?: return@register
            val mapId = PlayerUtils.getHotbarSlot(8)?.get(DataComponents.MAP_ID) ?: packet.mapId
            val mapData = mc.level?.getMapData(mapId) ?: return@register

            MapUtils.calibrated = MapUtils.calibrateMap(mapData)
            if (MapUtils.calibrated) {
                DungeonListener.dungeonStarted = true
                updateRooms(mapData)
                updatePlayers(mapData)
            }
        }
    }

    fun updatePlayers(mapData: MapItemSavedData) {
        val mapData = mapData as? IMapState ?: return
        val decorations = mapData.decorations ?: run {
            DungeonListener.dungeonTeammates.forEach {
                it.icon = ""
                it.hasMapPosition = false
            }
            return
        }
        // Marker suffixes refer to stable team slots. Filtering dead teammates first shifts every
        // later index and puts another player's marker on the wrong head after someone dies.
        val icons = mutableMapOf<DungeonPlayer, String>()

        decorations.forEach { (key, decoration) ->
            if (decoration.type.value() == MapDecorationTypes.FRAME.value()) {
                DungeonListener.thePlayer?.let { icons[it] = key }
            }
            else {
                val index = key.lastOrNull()?.digitToIntOrNull()
                if (index != null) dungeonTeammatesNoSelf.getOrNull(index)?.let { icons[it] = key }
            }
        }

        DungeonListener.dungeonTeammates.forEach { teammate ->
            val icon = icons[teammate]
            val decoration = icon?.let(decorations::get)
            if (decoration == null) {
                teammate.icon = ""
                teammate.hasMapPosition = false
                return@forEach
            }

            teammate.icon = icon
            teammate.mapX = decoration.mapX.toFloat()
            teammate.mapZ = decoration.mapZ.toFloat()
            teammate.yaw = decoration.yaw
            teammate.hasMapPosition = true
        }
    }

    fun updateRooms(mapData: MapItemSavedData) {
        if (LocationUtils.inBoss) return
        if (DungeonListener.dungeonEnded) return
        if (DungeonListener.thePlayer?.isDead == true) return
        HotbarMapScanner.updateMap(mapData)

        for (x in 0 .. 10) for (z in 0 .. 10) {
            val idx = z * 11 + x
            val room = DungeonScanner.dungeonList[idx]
            val mapTile = HotbarMapScanner.getTile(x, z)

            if (room is Unknown) {
                DungeonScanner.setTile(idx, mapTile)
                if (mapTile is RoomTile) {
                    val connected = HotbarMapScanner.getConnected(x, z)
                    connected.firstOrNull { it.data.name != "Unknown" }?.let {
                        mapTile.addToUnique(z, x, it.data.name)
                    }
                }
                continue
            }

            if (mapTile.state.ordinal < room.state.ordinal || mapTile is RoomTile && room is RoomTile && mapTile.data.type == RoomType.PUZZLE) {
                room.state = mapTile.state
            }

            if (mapTile is RoomTile && room is RoomTile && mapTile.data.type != room.data.type) {
                if (room.data.name == mapTile.data.name) room.data = mapTile.data
            }

            if (mapTile is DoorTile && room is DoorTile) {
                if (mapTile.type == DoorType.WITHER && room.type != DoorType.WITHER) {
                    room.type = mapTile.type
                }
            }

            if (room is DoorTile && room.type.equalsOneOf(DoorType.ENTRANCE, DoorType.WITHER, DoorType.BLOOD)) {
                if (mapTile is DoorTile && mapTile.type == DoorType.WITHER) room.opened = false
                else if (! room.opened) {
                    if (WorldUtils.isChunkLoaded(room.x, room.z)) {
                        val state = WorldUtils.getStateAt(room.x, 69, room.z)
                        if (state.isAir || state.`is`(Blocks.BARRIER)) room.opened = true
                    }
                    else if (mapTile is DoorTile && mapTile.state == RoomState.DISCOVERED) {
                        if (room.type == DoorType.BLOOD) {
                            val bloodRoomTile = DungeonScanner.dungeonList.filterIsInstance<RoomTile>().find { it.data.type == RoomType.BLOOD }
                            if (bloodRoomTile != null && bloodRoomTile.state != RoomState.UNOPENED) room.opened = true
                        }
                        else room.opened = true
                    }
                }
            }
        }
    }

    private val MapDecoration.mapX get() = (this.x + 128) shr 1
    private val MapDecoration.mapZ get() = (this.y + 128) shr 1
    private val MapDecoration.yaw get() = this.rot * 22.5f
}
