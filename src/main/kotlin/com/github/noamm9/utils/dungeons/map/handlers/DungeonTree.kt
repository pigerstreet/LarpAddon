package com.github.noamm9.utils.dungeons.map.handlers

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.dungeons.map.core.DoorTile
import com.github.noamm9.utils.dungeons.map.core.RoomState
import com.github.noamm9.utils.dungeons.map.core.RoomType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.equalsOneOf

object DungeonTree: ISelfInit {
    private var splitsCache: Map<UniqueRoom, Set<UniqueRoom>>? = null
    private var bloodRushCache: List<UniqueRoom>? = null
    private var entranceSplitsCache: List<Set<UniqueRoom>>? = null
    private var fairyRoom: UniqueRoom? = null
    private var nextRoomAfterFairy: UniqueRoom? = null
    private var roomBeforeFairy: UniqueRoom? = null

    override fun init() {
        EventBus.register<WorldChangeEvent> { clearCache() }
    }

    fun clearCache() {
        splitsCache = null
        bloodRushCache = null
        entranceSplitsCache = null
        //#if CHEAT
        classSplitCache.clear()
        //#endif
        fairyRoom = null
        nextRoomAfterFairy = null
        roomBeforeFairy = null
    }

    fun isFairy(door: DoorTile): Boolean {
        val currentRoom = ScanUtils.currentRoom ?: return false
        val lastRoom = ScanUtils.lastKnownRoom ?: return false

        getBloodRush()

        val fRoom = fairyRoom ?: return false
        val prevRoom = roomBeforeFairy ?: return false
        val nextRoom = nextRoomAfterFairy ?: return false
        if (! prevRoom.equalsOneOf(currentRoom, lastRoom)) return false

        val rooms = door.roomTiles.mapNotNull { it.uniqueRoom }
        if (rooms.size != 2) return false
        val (r1, r2) = rooms

        return (r1 == fRoom && r2 == nextRoom) || (r1 == nextRoom && r2 == fRoom)
    }

    fun getBloodRush(): List<UniqueRoom> = bloodRushCache ?: run {
        val graph = getSplits()
        val start = graph.keys.find { it.data.type == RoomType.ENTRANCE } ?: return emptyList()
        val target = graph.keys.find { it.data.type == RoomType.BLOOD } ?: return emptyList()

        val queue = ArrayDeque<UniqueRoom>()
        val previous = mutableMapOf<UniqueRoom, UniqueRoom?>()

        queue.add(start)
        previous[start] = null

        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            if (room == target) break

            graph[room]?.forEach { neighbor ->
                if (neighbor !in previous) {
                    previous[neighbor] = room
                    queue.add(neighbor)
                }
            }
        }

        if (target !in previous) return emptyList()

        val path = mutableListOf<UniqueRoom>()
        var room: UniqueRoom? = target
        while (room != null) {
            path.add(room)
            room = previous[room]
        }

        val reversedPath = path.asReversed()
        bloodRushCache = reversedPath

        val fairyIndex = reversedPath.indexOfFirst { it.data.type == RoomType.FAIRY }
        if (fairyIndex > 0 && fairyIndex < reversedPath.size - 1) {
            roomBeforeFairy = reversedPath[fairyIndex - 1]
            fairyRoom = reversedPath[fairyIndex]
            nextRoomAfterFairy = reversedPath[fairyIndex + 1]
        }

        return reversedPath
    }

    //#if CHEAT
    /// fork: the rooms behind each of the entrance's doors; the tree has no cycles, so the sets never overlap.
    fun getEntranceSplits(): List<Set<UniqueRoom>> = entranceSplitsCache ?: run {
        val graph = getSplits()
        val entrance = graph.keys.find { it.data.type == RoomType.ENTRANCE } ?: return emptyList()
        graph[entrance].orEmpty().map { first ->
            val seen = mutableSetOf(entrance, first)
            val queue = ArrayDeque(listOf(first))
            while (queue.isNotEmpty()) graph[queue.removeFirst()]?.forEach { if (seen.add(it)) queue.add(it) }
            seen - entrance
        }.also { splits -> if (splits.any { s -> s.any { it.data.type == RoomType.BLOOD } }) entranceSplitsCache = splits }
    }

    private val classSplitCache = mutableMapOf<Pair<DungeonClass, Boolean>, Set<UniqueRoom>?>()

    /** Whether the blood room has been opened: the blood rush is over and Archer and Healer move on to a split. */
    fun bloodOpened(): Boolean {
        val state = getBloodRush().lastOrNull()?.mainRoom?.state ?: return false
        return state != RoomState.UNOPENED && state != RoomState.UNDISCOVERED
    }

    /**
     * The split a class takes off the entrance: Bers the biggest, Tank the smallest left, the rest the blood-rush one.
     * With the blood room open the clear guide moves Archer to the split off the fairy room and Healer to the biggest
     * split (to follow Bers). Drawn every frame, so computed once per scan.
     */
    fun classSplit(cls: DungeonClass, bloodOpen: Boolean = false): Set<UniqueRoom>? {
        if (entranceSplitsCache == null) { classSplitCache.clear(); getEntranceSplits() }
        if (entranceSplitsCache == null) return null
        return classSplitCache.getOrPut(cls to bloodOpen) { computeClassSplit(cls, bloodOpen) }
    }

    /** The biggest group of rooms hanging off the fairy room that are not on the blood rush path ("fairy opens into a 2 room split"). */
    private fun fairySplit(): Set<UniqueRoom>? {
        val rush = getBloodRush()
        val fairy = rush.firstOrNull { it.data.type == RoomType.FAIRY } ?: return null
        val path = rush.toSet()
        val graph = getSplits()
        return graph[fairy].orEmpty().filter { it !in path }.map { first ->
            val seen = path.toMutableSet().apply { add(first) }
            val queue = ArrayDeque(listOf(first))
            while (queue.isNotEmpty()) graph[queue.removeFirst()]?.forEach { if (seen.add(it)) queue.add(it) }
            seen - path
        }.maxByOrNull { it.size }
    }

    private fun computeClassSplit(cls: DungeonClass, bloodOpen: Boolean): Set<UniqueRoom>? {
        val splits = getEntranceSplits().ifEmpty { return null }
        val blood = splits.firstOrNull { s -> s.any { it.data.type == RoomType.BLOOD } }
        val others = splits.filter { it !== blood }.sortedByDescending { it.size }
        if (bloodOpen) when (cls) {
            DungeonClass.Archer -> return fairySplit() ?: others.firstOrNull() ?: blood
            DungeonClass.Healer -> return others.firstOrNull() ?: blood
            else -> {}
        }
        return when (cls) {
            DungeonClass.Archer, DungeonClass.Mage, DungeonClass.Healer -> blood
            DungeonClass.Berserk -> others.firstOrNull() ?: blood
            DungeonClass.Tank -> if (others.size > 1) others.last() else others.firstOrNull() ?: blood
            DungeonClass.Empty -> null
        }
    }
    //#endif

    private fun getSplits(): Map<UniqueRoom, Set<UniqueRoom>> = splitsCache ?: run {
        val graph = mutableMapOf<UniqueRoom, MutableSet<UniqueRoom>>()

        for (tile in DungeonScanner.dungeonList) {
            val door = tile as? DoorTile ?: continue
            val rooms = door.roomTiles.mapNotNull { it.uniqueRoom }
            if (rooms.size != 2 || rooms[0] == rooms[1]) continue

            graph.getOrPut(rooms[0], ::mutableSetOf).add(rooms[1])
            graph.getOrPut(rooms[1], ::mutableSetOf).add(rooms[0])
        }

        return graph.also(::splitsCache::set)
    }
}