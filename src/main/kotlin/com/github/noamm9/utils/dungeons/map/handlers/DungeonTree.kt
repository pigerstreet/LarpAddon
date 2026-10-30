package com.github.noamm9.utils.dungeons.map.handlers

import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.dungeons.map.core.DoorTile
import com.github.noamm9.utils.dungeons.map.core.DoorType
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
    private var fairyRoom: UniqueRoom? = null
    private var nextRoomAfterFairy: UniqueRoom? = null
    private var roomBeforeFairy: UniqueRoom? = null

    override fun init() {
        EventBus.register<WorldChangeEvent> { clearCache() }
    }

    fun clearCache() {
        splitsCache = null
        bloodRushCache = null
        //#if CHEAT
        classSplitCache.clear()
        roomGraphCache = null
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
    private class Rooms(val cells: List<Set<Int>>, val type: List<RoomType>, val edges: List<Set<Int>>, val doors: List<Triple<Int, Int, Int>>, val rushEdges: List<Set<Int>>)

    /** Built once per tile change (every one clears it), not per frame. */
    @Volatile private var roomGraphCache: Rooms? = null

    private fun roomGraph() = roomGraphCache ?: buildRoomGraph().also { roomGraphCache = it }

    private fun buildRoomGraph(): Rooms {
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
        val doors = mutableListOf<Triple<Int, Int, Int>>()
        val rushEdges = List(cells.size) { mutableSetOf<Int>() }
        for ((idx, tile) in grid.withIndex()) {
            val door = tile as? DoorTile ?: continue
            val rooms = door.roomTileIndices.map { owner[it] }.filter { it >= 0 }.distinct()
            if (rooms.size == 2) { edges[rooms[0]] += rooms[1]; edges[rooms[1]] += rooms[0]; doors += Triple(idx, rooms[0], rooms[1])
                // Wither and blood doors lie on the way to blood, so they show the rush before blood itself is on the map.
                if (door.type == DoorType.WITHER || door.type == DoorType.BLOOD) { rushEdges[rooms[0]] += rooms[1]; rushEdges[rooms[1]] += rooms[0] }
            }
        }
        return Rooms(cells, cells.map { (grid[it.first()] as? RoomTile)?.data?.type ?: RoomType.NORMAL }, edges, doors, rushEdges)
    }

    /** The rooms' cells plus the doors between two of them, so the outline runs round the split, not round each room. */
    private fun Rooms.cellsOf(rooms: Collection<Int>) = rooms.flatMapTo(HashSet()) { cells[it] }.also { out ->
        for ((door, a, b) in doors) if (a in rooms && b in rooms) out += door
    }

    // Concurrent: a map packet can clear it from the network thread while the renderer reads it.
    private val classSplitCache = java.util.concurrent.ConcurrentHashMap<Pair<DungeonClass, Boolean>, BooleanArray>()

    /** Whether the blood room has been opened: the blood rush is over and Archer and Healer move on to a split. */
    fun bloodOpened(): Boolean {
        val g = roomGraph()
        val blood = g.type.indexOf(RoomType.BLOOD).takeIf { it >= 0 } ?: return false
        // Read live: a room's state changes without a tile change, so the cached graph can't hold it.
        val state = (DungeonScanner.dungeonList[g.cells[blood].first()] as? RoomTile)?.state
        return state != null && state != RoomState.UNOPENED && state != RoomState.UNDISCOVERED
    }

    /**
     * The grid cells to outline for a class: Bers the biggest split, Tank the smallest real one, the rest the blood rush
     * itself; with the blood room open Archer takes the biggest split off the fairy room and Healer the biggest. Drawn
     * every frame, so a result is kept until the next tile change clears it.
     */
    fun classSplit(cls: DungeonClass, bloodOpen: Boolean = false): BooleanArray? {
        classSplitCache[cls to bloodOpen]?.let { return it }
        // A flat flag per grid cell: the renderer asks about up to 5 cells for each of the 121 every frame.
        return computeClassSplit(cls, bloodOpen)?.let { cells -> BooleanArray(121).also { for (c in cells) it[c] = true } }
            ?.also { classSplitCache[cls to bloodOpen] = it }
    }

    /** The rooms from the entrance to blood by fewest doors (the blood rush), entrance first; null if blood is not joined to it. */
    private fun Rooms.rushPath(entrance: Int, blood: Int): List<Int>? {
        val previous = hashMapOf(entrance to - 1)
        val queue = ArrayDeque(listOf(entrance))
        while (queue.isNotEmpty() && blood !in previous) {
            val room = queue.removeFirst()
            for (n in edges[room]) if (n !in previous) { previous[n] = room; queue.add(n) }
        }
        if (blood !in previous) return null
        return generateSequence(blood) { previous[it]?.takeIf { p -> p >= 0 } }.toList().asReversed()
    }

    /**
     * The rush as far as the doors show it, for when blood is not on the map: from spawn through wither and blood doors
     * (the one door out of spawn counts either way). Every split off it is a real split, and the rest of the rush, which
     * carries on into the rooms nobody has seen, is never one.
     */
    private fun Rooms.rushChain(entrance: Int): List<Int> {
        val chain = mutableListOf(entrance)
        val seen = hashSetOf(entrance)
        var i = 0
        while (i < chain.size) {
            val room = chain[i++]
            val next = if (room == entrance && edges[entrance].size == 1) edges[entrance] else rushEdges[room]
            for (n in next) if (seen.add(n)) chain += n
        }
        return chain
    }

    /** A split: the rooms hanging off the rush path in one piece, and the path positions they hang off. */
    private class Branch(val rooms: Set<Int>, val attach: Set<Int>)

    private fun Rooms.branches(path: List<Int>): List<Branch> {
        val onPath = path.withIndex().associate { it.value to it.index }
        val seen = HashSet<Int>()
        val out = mutableListOf<Branch>()
        for (start in cells.indices) {
            if (start in onPath || !seen.add(start)) continue
            val rooms = linkedSetOf(start); val attach = HashSet<Int>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) for (n in edges[queue.removeFirst()]) {
                val at = onPath[n]
                if (at != null) attach += at else if (seen.add(n)) { rooms += n; queue.add(n) }
            }
            if (attach.isNotEmpty()) out += Branch(rooms, attach)   // a piece joined to nothing on the path is not a route
        }
        return out
    }

    /**
     * The guide's definition: a split is a branch of rooms splitting off the main path to the blood room. Bers takes
     * the biggest, Tank the smallest, the rest the rush itself; with blood open Archer takes the biggest split off the
     * fairy room and Healer the biggest. The path rooms up to where the split leaves it are shared and outlined too.
     */
    private fun computeClassSplit(cls: DungeonClass, bloodOpen: Boolean): Set<Int>? {
        val g = roomGraph()
        val entrance = g.type.indexOf(RoomType.ENTRANCE).takeIf { it >= 0 } ?: return null
        val blood = g.type.indexOf(RoomType.BLOOD).takeIf { it >= 0 }
        // Blood not on the map yet (or not joined up): Bers and Tank go by the rush the doors show so far, so the outline
        // is there from the start and never sits on the blood rush; the rush classes need blood to know their rooms.
        val bloodPath = blood?.let { g.rushPath(entrance, it) }
        if (bloodPath == null && cls != DungeonClass.Berserk && cls != DungeonClass.Tank) return null
        val path = bloodPath ?: g.rushChain(entrance).takeIf { it.size > 1 } ?: return g.fallbackSplit(cls)?.let { g.cellsOf(it) }
        val branches = g.branches(path).sortedByDescending { it.rooms.size }
        fun with(b: Branch) = g.cellsOf(b.rooms + path.subList(1, (b.attach.min() + 1).coerceAtMost(path.size)))
        val rush = g.cellsOf(path.drop(1))
        val fairy = path.indexOfFirst { g.type[it] == RoomType.FAIRY }
        return when {
            bloodOpen && cls == DungeonClass.Archer -> (branches.firstOrNull { fairy >= 0 && fairy in it.attach } ?: branches.firstOrNull())?.let(::with) ?: rush
            bloodOpen && cls == DungeonClass.Healer -> branches.firstOrNull()?.let(::with) ?: rush
            else -> when (cls) {
                DungeonClass.Archer, DungeonClass.Mage, DungeonClass.Healer -> rush
                DungeonClass.Berserk -> branches.firstOrNull()?.let(::with)
                // Tank (owner, 2026-10-31: "the smallest chain of rooms off spawn that's not in the blood rush"): of the real splits
                // (two rooms or more), those leaving the rush nearest spawn, then the smallest; Bers's own when it is the only one.
                // Only when there is none, a lone puzzle or dead end will do. With no split at all neither outlines the rush.
                DungeonClass.Tank -> run {
                    val real = branches.filter { it.rooms.size > 1 }
                    val nearest = real.minOfOrNull { it.attach.min() }
                    real.filter { it.attach.min() == nearest }.lastOrNull() ?: branches.lastOrNull()
                }?.let(::with)
                DungeonClass.Empty -> null
            }
        }
    }

    /** Blood not on the map yet (or not joined up): the biggest and smallest branch off the first fork after spawn. */
    private fun Rooms.fallbackSplit(cls: DungeonClass): Set<Int>? {
        val g = this
        val entrance = g.type.indexOf(RoomType.ENTRANCE).takeIf { it >= 0 } ?: return null
        // A split is a branch of the tree, not everything behind the entrance: when spawn has one way on, the rooms
        // up to the first one that forks into several routes are shared by every route (Quartz Knight on most maps) and belong to none.
        val stem = linkedSetOf(entrance)
        var hub = entrance
        while (true) {
            // A dead-end room off the way (King Midas off Rails) is not a route to split: only rooms that lead on count.
            val onward = g.edges[hub].filter { it !in stem && g.edges[it].size > 1 }
            if (onward.size != 1) break
            hub = onward.first(); stem += hub
        }
        val starts = g.edges[hub].filter { it !in stem }.ifEmpty { return null }
        // Rooms can loop back (two routes into the same room), so a plain flood from each door would swallow the whole
        // map: each room goes to the door it is fewest doors from, and ties to whichever got there first.
        val branch = HashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        starts.forEachIndexed { i, r -> branch[r] = i; queue.add(r) }
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (n in g.edges[r]) if (n !in stem && n !in branch) { branch[n] = branch.getValue(r); queue.add(n) }
        }
        val splits = starts.indices.map { i -> branch.filterValues { it == i }.keys }.sortedByDescending { it.size }
        val out = if (cls == DungeonClass.Berserk) splits.first() else splits.lastOrNull { it.size > 1 } ?: splits.last()
        // The shared first rooms are outlined with it: every route starts there.
        return out + (stem - entrance)
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