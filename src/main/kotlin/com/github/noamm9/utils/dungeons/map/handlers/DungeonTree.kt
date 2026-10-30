package com.github.noamm9.utils.dungeons.map.handlers

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.dungeons.map.core.DoorTile
import com.github.noamm9.utils.dungeons.map.core.RoomTile
import com.github.noamm9.utils.dungeons.map.core.RoomState
import com.github.noamm9.utils.dungeons.map.core.RoomType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.equalsOneOf

object DungeonTree: ISelfInit {
    private var splitsCache: Map<UniqueRoom, Set<UniqueRoom>>? = null
    private var bloodRushCache: List<UniqueRoom>? = null
    private var entranceSplitsCache: List<Set<Int>>? = null
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
    /**
     * fork: the room graph over the map grid. A room the map shows but nobody has been near has no name, so no
     * UniqueRoom; a graph of UniqueRooms lost it, which delayed the splits until the blood path was all named and
     * let one split swallow every room behind a door to an unnamed one. [cells] are each room's grid indices.
     */
    private class Rooms(val cells: List<Set<Int>>, val type: List<RoomType>, val edges: List<Set<Int>>, val state: List<RoomState>)

    private fun roomGraph(): Rooms {
        val grid = DungeonScanner.dungeonList
        val owner = IntArray(grid.size) { - 1 }
        val cells = mutableListOf<MutableSet<Int>>()
        val named = HashMap<UniqueRoom, Int>()
        fun near(c: Int) = listOf(c - 1, c + 1, c - 11, c + 11).filter { it in grid.indices && (it % 11 - c % 11) in - 1 .. 1 }
        for (i in grid.indices) {
            val tile = grid[i] as? RoomTile ?: continue
            val unique = tile.uniqueRoom
            if (unique != null) {
                val n = named.getOrPut(unique) { cells.add(mutableSetOf()); cells.size - 1 }
                owner[i] = n; cells[n] += i
                continue
            }
            if (owner[i] >= 0) continue
            val n = cells.size
            cells.add(mutableSetOf()); owner[i] = n
            val queue = ArrayDeque(listOf(i))
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst(); cells[n] += c
                for (nb in near(c)) if (owner[nb] < 0 && (grid[nb] as? RoomTile)?.uniqueRoom == null && grid[nb] is RoomTile) { owner[nb] = n; queue.add(nb) }
            }
        }
        val edges = List(cells.size) { mutableSetOf<Int>() }
        for (tile in grid) {
            val door = tile as? DoorTile ?: continue
            val rooms = door.roomTileIndices.map { owner[it] }.filter { it >= 0 }.distinct()
            if (rooms.size == 2) { edges[rooms[0]] += rooms[1]; edges[rooms[1]] += rooms[0] }
        }
        val first = cells.map { grid[it.first()] as RoomTile }
        return Rooms(cells, first.map { it.data.type }, edges, first.map { it.state })
    }

    /** The rooms reachable from [from] without passing [blocked], [from] included. */
    private fun Rooms.reach(from: Int, blocked: Set<Int>): Set<Int> {
        val seen = HashSet(blocked); seen += from
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) edges[queue.removeFirst()].forEach { if (seen.add(it)) queue.add(it) }
        return seen - blocked
    }

    private fun Rooms.cellsOf(rooms: Collection<Int>) = rooms.flatMapTo(HashSet()) { cells[it] }

    /** The grid cells of the rooms behind each of the entrance's doors; the tree has no cycles, so the sets never overlap. */
    fun getEntranceSplits(): List<Set<Int>> = entranceSplitsCache ?: run {
        val g = roomGraph()
        val entrance = g.type.indexOf(RoomType.ENTRANCE).takeIf { it >= 0 } ?: return emptyList()
        g.edges[entrance].map { g.cellsOf(g.reach(it, setOf(entrance))) }.also { entranceSplitsCache = it }
    }

    private val classSplitCache = mutableMapOf<Pair<DungeonClass, Boolean>, Set<Int>?>()

    /** Whether the blood room has been opened: the blood rush is over and Archer and Healer move on to a split. */
    fun bloodOpened(): Boolean {
        val g = roomGraph()
        val blood = g.type.indexOf(RoomType.BLOOD).takeIf { it >= 0 } ?: return false
        return g.state[blood] != RoomState.UNOPENED && g.state[blood] != RoomState.UNDISCOVERED
    }

    /**
     * The grid cells of the split a class takes off the entrance: Bers the biggest, Tank the smallest left, the rest
     * the blood-rush one. With the blood room open the clear guide moves Archer to the split off the fairy room and
     * Healer to the biggest split (to follow Bers). Drawn every frame, so computed once per tile change (the cache
     * is cleared by every one).
     */
    fun classSplit(cls: DungeonClass, bloodOpen: Boolean = false): Set<Int>? {
        classSplitCache[cls to bloodOpen]?.let { return it }
        return computeClassSplit(cls, bloodOpen)?.also { classSplitCache[cls to bloodOpen] = it }
    }

    /** The biggest group of rooms hanging off the fairy room that are not on the shortest way from the entrance to blood. */
    private fun Rooms.fairySplit(entrance: Int, blood: Int): Set<Int>? {
        val previous = hashMapOf(entrance to - 1)
        val queue = ArrayDeque(listOf(entrance))
        while (queue.isNotEmpty() && blood !in previous) {
            val room = queue.removeFirst()
            for (n in edges[room]) if (n !in previous) { previous[n] = room; queue.add(n) }
        }
        if (blood !in previous) return null
        val path = generateSequence(blood) { previous[it]?.takeIf { p -> p >= 0 } }.toSet()
        val fairy = path.firstOrNull { type[it] == RoomType.FAIRY } ?: return null
        return edges[fairy].filter { it !in path }.map { reach(it, path) }.maxByOrNull { it.size }?.let { cellsOf(it) }
    }

    private fun computeClassSplit(cls: DungeonClass, bloodOpen: Boolean): Set<Int>? {
        val g = roomGraph()
        val entrance = g.type.indexOf(RoomType.ENTRANCE).takeIf { it >= 0 } ?: return null
        val blood = g.type.indexOf(RoomType.BLOOD).takeIf { it >= 0 } ?: return null
        val splits = g.edges[entrance].map { g.reach(it, setOf(entrance)) }.ifEmpty { return null }
        val bloodSplit = splits.firstOrNull { blood in it }
        val others = splits.filter { it !== bloodSplit }.sortedByDescending { it.size }
        val out = when {
            bloodOpen && cls == DungeonClass.Archer -> g.fairySplit(entrance, blood)?.let { return it } ?: others.firstOrNull() ?: bloodSplit
            bloodOpen && cls == DungeonClass.Healer -> others.firstOrNull() ?: bloodSplit
            else -> when (cls) {
                DungeonClass.Archer, DungeonClass.Mage, DungeonClass.Healer -> bloodSplit
                DungeonClass.Berserk -> others.firstOrNull() ?: bloodSplit
                DungeonClass.Tank -> if (others.size > 1) others.last() else others.firstOrNull() ?: bloodSplit
                DungeonClass.Empty -> null
            }
        }
        return out?.let { g.cellsOf(it) }
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