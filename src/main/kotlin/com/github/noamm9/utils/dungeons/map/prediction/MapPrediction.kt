package com.github.noamm9.utils.dungeons.map.prediction

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.impl.dungeon.map.MapConfig
import com.github.noamm9.features.impl.dungeon.map.MapRenderer
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.map.core.*
import com.github.noamm9.utils.dungeons.map.handlers.DungeonScanner
import com.github.noamm9.utils.dungeons.map.handlers.HotbarMapScanner
import com.github.noamm9.utils.dungeons.map.handlers.ScoreTracker
import com.github.noamm9.utils.dungeons.map.utils.MapUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawTexture
import com.github.noamm9.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Room Prediction on the dungeon map (F7/M7): what the unopened rooms probably are, from what the map has
 * shown so far and the rules every recorded F7/M7 map follows ([MapPredictor], [MapStats]).
 *
 * Adapted from engineerClient's Room Prediction (SoftwareEngineerAndrew/engineerClient @ 844c99cd2a, the
 * Odin adapter `OdinMapPrediction`), rebuilt on this mod's map.
 *
 * Only what the map item shows is used: the room colours, checkmarks and doors it draws, read straight
 * from [HotbarMapScanner] (never the world scan of rooms nobody has seen), plus the secret total the tab
 * list gives. A room's name goes in only once it is cleared (white, green or failed checkmark). This
 * never reads a block or a chunk, so it is the same information a player has looking at the vanilla map.
 * Off while Cheater Map is on, which shows rooms the map does not.
 */
object MapPrediction: ISelfInit {
    private const val W = 6
    private const val SAMPLES = 2000
    private const val BATCH = 100
    /** From this sure (percent) a dead end always gets the guess tile, whatever Dead End Chance is. */
    private const val ALWAYS_DEAD_END = 95
    /** An exact last room: every sample agrees (allowing for float sums). */
    private const val EXACT = 0.999f
    private val BIG_GREY = Color(85, 85, 85)

    @Volatile private var shown: Prediction? = null
    @Volatile private var shownView: MapView? = null
    private var lastKey = ""
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "noammaddons-map-prediction").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Doors the map has shown as wither or blood doors this run (the map stops showing that once they're open). */
    private val pathDoors = HashMap<Int, Byte>()

    override fun init() {
        EventBus.register<TickEvent.End> {
            if (mc.player == null || (mc.level?.gameTime ?: 0L) % 5L != 0L) return@register
            runCatching { update() }
        }
        EventBus.register<WorldChangeEvent> { reset() }
    }

    private fun reset() {
        generation.incrementAndGet()
        shown = null; shownView = null; lastKey = ""
        pathDoors.clear()
    }

    private fun active() = MapConfig.roomPrediction.value && ! MapConfig.dungeonMapCheater.value

    private fun supported() = LocationUtils.inDungeon && ! LocationUtils.inBoss && LocationUtils.dungeonFloorNumber == 7

    private fun pair(a: Int, b: Int) = minOf(a, b) * 64 + maxOf(a, b)

    /** The map as the map item shows it: rooms that are discovered (not the grey unopened ones) and every door drawn. */
    private fun view(): MapView? {
        if (! MapUtils.calibrated) return null
        val puzzles = DungeonListener.maxPuzzleCount
        if (puzzles <= 0) return null

        val rooms = arrayOfNulls<RoomTile>(W * W)
        for (z in 0 until W) for (x in 0 until W) {
            val tile = HotbarMapScanner.getTile(x * 2, z * 2) as? RoomTile ?: continue
            if (tile.state == RoomState.UNOPENED || tile.state == RoomState.UNDISCOVERED) continue
            rooms[z * W + x] = tile
        }

        // a big room is rooms joined by a drawn connector between them
        val parent = IntArray(W * W) { it }
        fun find(i: Int): Int { var n = i; while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }; return n }
        for (z in 0 until W) for (x in 0 until W) {
            val i = z * W + x
            if (rooms[i] == null) continue
            if (x + 1 < W && rooms[i + 1] != null && HotbarMapScanner.getTile(x * 2 + 1, z * 2) is RoomTile) parent[find(i + 1)] = find(i)
            if (z + 1 < W && rooms[i + W] != null && HotbarMapScanner.getTile(x * 2, z * 2 + 1) is RoomTile) parent[find(i + W)] = find(i)
        }

        val v = MapView(W, W, puzzles)
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until W * W) if (rooms[i] != null) groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        for (tiles in groups.values) {
            val head = rooms[tiles[0]] !!
            val kind = when (head.data.type) {
                RoomType.ENTRANCE -> Kind.ENTRANCE
                RoomType.NORMAL -> Kind.NORMAL
                RoomType.RARE -> Kind.RARE
                RoomType.PUZZLE -> Kind.PUZZLE
                RoomType.TRAP -> Kind.TRAP
                RoomType.CHAMPION -> Kind.YELLOW
                RoomType.BLOOD -> Kind.BLOOD
                RoomType.FAIRY -> Kind.FAIRY
            }
            v.addRoom(kind, tiles.toIntArray(), nameOf(tiles, rooms)) ?: return null
        }
        if (v.kinds.none { it == Kind.ENTRANCE }) return null

        v.secretTotals = SecretNames.totals(ScoreTracker.foundSecrets, ScoreTracker.secretPercent.toFloat())

        val doors = HashMap<Int, Byte>()
        for (z in 0 .. 10) for (x in 0 .. 10) {
            val horizontal = z % 2 == 0 && x % 2 == 1
            val vertical = z % 2 == 1 && x % 2 == 0
            if (! horizontal && ! vertical) continue
            val door = HotbarMapScanner.getTile(x, z) as? DoorTile ?: continue
            val a = if (horizontal) (z / 2) * W + (x - 1) / 2 else ((z - 1) / 2) * W + x / 2
            val b = if (horizontal) (z / 2) * W + (x + 1) / 2 else ((z + 1) / 2) * W + x / 2
            val k = when (door.type) { DoorType.WITHER -> Edge.WITHER; DoorType.BLOOD -> Edge.BLOOD; else -> Edge.DOOR }
            doors[pair(a, b)] = k
            if (k != Edge.DOOR) pathDoors[pair(a, b)] = k
        }
        v.closeEdges { a, b -> pathDoors[pair(a, b)] ?: doors[pair(a, b)] }
        return v
    }

    /** A room's name, only once somebody has cleared it (what the map's own checkmark shows too); null otherwise. */
    private fun nameOf(tiles: List<Int>, rooms: Array<RoomTile?>): String? {
        val cleared = tiles.any { rooms[it]?.state.let { s -> s == RoomState.GREEN || s == RoomState.CLEARED || s == RoomState.FAILED } }
        if (! cleared) return null
        for (t in tiles) {
            val data = (DungeonScanner.dungeonList[(t / W) * 2 * 11 + (t % W) * 2] as? RoomTile)?.data ?: continue
            if (! data.isUnknown()) return data.name
        }
        return null
    }

    private fun update() {
        if (! active() || ! supported()) return
        val v = view() ?: return
        val key = v.key()
        if (key == lastKey) return
        lastKey = key
        val gen = generation.incrementAndGet()
        worker.execute {
            runCatching {
                val predictor = MapPredictor(v)
                val rnd = Random(System.nanoTime())
                // batches until enough samples agree, publishing as it goes; stops when the map changes
                // again, when nothing fits even the loosest rules, or when no more fit
                while (generation.get() == gen && predictor.accepted < SAMPLES) {
                    val kept = predictor.run(BATCH, 40_000, rnd)
                    if (generation.get() != gen) return@runCatching
                    predictor.prediction()?.let { shown = it; shownView = v }
                    if (kept == 0 && (predictor.accepted > 0 || predictor.level > 2)) break
                }
            }
        }
    }

    private fun colorOf(c: Int): Color = when (c) {
        Cls.PUZZLE -> MapConfig.colorPuzzle.value
        Cls.TRAP -> MapConfig.colorTrap.value
        Cls.YELLOW -> MapConfig.colorMiniboss.value
        Cls.BLOOD -> MapConfig.colorBlood.value
        Cls.FAIRY -> MapConfig.colorFairy.value
        Cls.RARE -> MapConfig.colorRoom.value
        else -> MapConfig.colorUnopened.value
    }

    private fun Color.shade(f: Float) = Color((red * f).toInt(), (green * f).toInt(), (blue * f).toInt(), alpha)
    private fun Color.alpha(a: Float) = Color(red, green, blue, (a.coerceIn(0f, 1f) * 255).toInt())

    private val SPECIALS = intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.BLOOD, Cls.FAIRY, Cls.RARE)

    /** Draws the prediction over the unopened and unseen tiles, in the map's own space (as [MapRenderer.draw] has it). */
    fun render(g: GuiGraphicsExtractor) {
        if (! active() || ! supported()) return
        runCatching { draw(g) }
    }

    private fun draw(g: GuiGraphicsExtractor) {
        val p = shown ?: return
        val v = shownView ?: return
        val room = MapUtils.mapRoomSize
        val tile = room + (HotbarMapScanner.quarterRoom.takeUnless { it == - 1 } ?: 4)
        val min = MapConfig.predictionMinChance.value.toFloat()
        val deadEndAt = minOf(MapConfig.predictionDeadEndChance.value.toInt(), ALWAYS_DEAD_END) / 100f
        val oneAt = MapConfig.predictionOneByOneChance.value.toInt() / 100f
        val bigAt = MapConfig.predictionBigRoomChance.value.toInt() / 100f
        val splits = p.splits.associateBy { it.tile }
        val ctx = Ctx(g, room, tile)
        fun known(t: Int) = v.roomAt[t] >= 0

        // the last rooms the secret total leaves only one size and place for: in full, named when only one name fits
        val exact = BooleanArray(W * W)
        if (MapConfig.predictionExactRooms.value) for ((_, names) in p.namedRooms.groupBy { it.tiles.joinToString(",") }) {
            val tiles = names[0].tiles
            if (names.sumOf { it.chance.toDouble() } < EXACT || tiles.any { known(it) || exact[it] }) continue
            for (t in tiles) exact[t] = true
            ctx.exactRoom(tiles, names)
        }
        // the big rooms it's sure of, greyed out at their whole size (likeliest first, never two on one tile)
        val inBig = arrayOfNulls<BigRoom>(W * W)
        for (big in p.bigRooms) {
            if (big.chance < bigAt || big.chance <= 0f || big.tiles.any { known(it) || exact[it] || inBig[it] != null }) continue
            for (t in big.tiles) inBig[t] = big
            ctx.greyRoom(big.tiles)
        }
        for (t in 0 until W * W) {
            val probs = p.probs[t] ?: continue
            if (known(t) || exact[t]) continue
            val x = (t % W) * tile; val y = (t / W) * tile
            inBig[t]?.let { big ->
                // its door's tile keeps the split's label (or the "?") on the grey
                splits[t]?.let { split ->
                    if (MapConfig.predictionSplitText.value) ctx.splitLabel(x, y, split) else ctx.icon(x, y)
                    if (MapConfig.predictionShowChances.value) ctx.small("${(big.chance * 100).roundToInt()}%", x + room / 2, y + room * 13 / 16)
                }
                continue
            }
            val shownCls = SPECIALS.filter { probs[it] >= min }.sortedByDescending { probs[it] }.take(3)
            val split = splits[t]
            val deadEnd = probs[Cls.PUZZLE] + probs[Cls.TRAP] + probs[Cls.YELLOW] + probs[Cls.RARE]
            val one = probs[Cls.NORMAL]
            val isDeadEnd = deadEnd > 0f && deadEnd >= deadEndAt
            val isOne = one > 0f && one >= oneAt
            if (isDeadEnd && (! isOne || deadEnd >= one)) {
                ctx.guessTile(x, y, probs, deadEnd)
                if (MapConfig.predictionShowChances.value) ctx.small("${(deadEnd * 100).roundToInt()}%", x + room / 2, y + room * 13 / 16)
            }
            else if (isOne) {
                // a normal 1x1, greyed out like the big rooms; a door's tile keeps the split label
                ctx.greyRoom(intArrayOf(t))
                split?.let { if (MapConfig.predictionSplitText.value) ctx.splitLabel(x, y, it) else ctx.icon(x, y) }
                if (MapConfig.predictionShowChances.value) ctx.small("${(one * 100).roundToInt()}%", x + room / 2, y + room * 13 / 16)
            }
            else if (split != null) {
                // the "?" tile: its chances as bands across it, the rest grey
                ctx.fill(x, y, x + room, y + room, MapConfig.colorUnopened.value)
                var at = x.toFloat()
                for (c in shownCls) {
                    val width = probs[c] * room
                    ctx.fill(at.roundToInt(), y, (at + width).roundToInt().coerceAtMost(x + room), y + room, colorOf(c).shade(0.5f))
                    at += width
                }
                if (MapConfig.predictionSplitText.value) ctx.splitLabel(x, y, split) else ctx.icon(x, y)
                if (MapConfig.predictionShowChances.value && shownCls.isNotEmpty()) ctx.small("${(probs[shownCls[0]] * 100).roundToInt()}%", x + room / 2, y + room * 13 / 16)
            }
            else if (MapConfig.predictionHiddenHints.value && shownCls.isNotEmpty()) {
                val c = shownCls[0]
                val a = (0.35f + 0.65f * probs[c]).coerceAtMost(1f)
                val inset = room * 5 / 16
                ctx.fill(x + inset, y + inset, x + room - inset, y + room - inset, colorOf(c).shade(0.6f).alpha(a))
            }
        }
    }

    /** The drawing helpers, with the map's current room and tile (room + connector) sizes. */
    private class Ctx(val g: GuiGraphicsExtractor, val room: Int, val tile: Int) {
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Color) = g.fill(x0, y0, x1, y1, c.rgb)

        /** A room's tiles and the gaps between them, as the map draws a big room, in grey. */
        fun greyRoom(tiles: IntArray) = fillTiles(tiles, BIG_GREY)

        fun fillTiles(tiles: IntArray, c: Color) {
            val set = tiles.toHashSet()
            for (t in tiles) {
                val x = (t % W) * tile; val y = (t / W) * tile
                val right = t % W + 1 < W && t + 1 in set; val down = t + W in set
                fill(x, y, x + room, y + room, c)
                if (right) fill(x + room, y, x + tile, y + room, c)
                if (down) fill(x, y + room, x + room, y + tile, c)
                if (right && down && t + W + 1 in set) fill(x + room, y + room, x + tile, y + tile, c)
            }
        }

        /**
         * An exact last room: its shape in its type's colour (a little see-through, as nobody has opened it) and
         * its name when only one fits, else "?" with the secrets if every name that fits has the same count.
         */
        fun exactRoom(tiles: IntArray, names: List<NamedRoom>) {
            val best = names.maxBy { it.chance }
            val color = when (best.name.pool) {
                MapStats.POOL_PUZZLE -> MapConfig.colorPuzzle.value
                MapStats.POOL_TRAP -> MapConfig.colorTrap.value
                MapStats.POOL_YELLOW -> MapConfig.colorMiniboss.value
                MapStats.POOL_BLOOD -> MapConfig.colorBlood.value
                MapStats.POOL_FAIRY -> MapConfig.colorFairy.value
                else -> MapConfig.colorRoom.value
            }
            fillTiles(tiles, color.alpha(0.75f))
            val cx = tiles.map { (it % W) * tile + room / 2f }.average().toFloat()
            val cz = tiles.map { (it / W) * tile + room / 2f }.average().toFloat()
            val secrets = names.map { it.name.secrets }.distinct().singleOrNull()
            val name = if (best.chance >= EXACT) best.name.name else "?"
            val lines = (name + if (secrets != null && secrets > 0) " 0/$secrets" else "").split(" ")
            val scale = MapConfig.textScale.value.toFloat() * 0.6f
            val lineH = 9 * scale
            val totalH = lines.size * lineH
            for ((i, line) in lines.withIndex()) g.drawCenteredString(line, cx, cz - totalH / 2f + i * lineH, Color(180, 180, 180), scale, false)
        }

        /**
         * The guess tile: one type in its colour, or two or three side by side darkened, with the "?". The types are the
         * ones a dead end here can be: yellow, trap, puzzle, a rare room's brown on 5-puzzle maps; any at least 5% of the
         * dead end's chance, the likeliest three.
         */
        fun guessTile(x: Int, y: Int, probs: FloatArray, deadEnd: Float) {
            val types = intArrayOf(Cls.YELLOW, Cls.TRAP, Cls.PUZZLE, Cls.RARE).filter { probs[it] >= 0.05f * deadEnd }
            val keep = types.sortedByDescending { probs[it] }.take(3).toSet()
            val colors = types.filter { it in keep }.map { colorOf(it) }
            when (colors.size) {
                0 -> {}
                1 -> fill(x, y, x + room, y + room, colors[0])
                2 -> {
                    val half = room / 2
                    fill(x, y, x + half, y + room, colors[0].shade(0.5f))
                    fill(x + half, y, x + room, y + room, colors[1].shade(0.5f))
                }
                else -> {
                    val third = room / 3
                    fill(x, y, x + third, y + room, colors[0].shade(0.5f))
                    fill(x + third, y, x + third * 2, y + room, colors[1].shade(0.5f))
                    fill(x + third * 2, y, x + room, y + room, colors[2].shade(0.5f))
                }
            }
            icon(x, y)
        }

        fun icon(x: Int, y: Int) {
            val size = room - 4
            g.drawTexture(MapRenderer.checkmarkUnknown, x + 2, y + 2, size, size)
        }

        /** "2P T Y B" (what's more likely there than not, puzzles by their expected count) over "~6" rooms. */
        fun splitLabel(x: Int, y: Int, s: Split) {
            val parts = ArrayList<String>()
            val pz = s.expect[Cls.PUZZLE].roundToInt()
            if (pz > 0) parts += "&d${if (pz > 1) pz else ""}P"
            if (s.expect[Cls.TRAP] >= 0.5f) parts += "&6T"
            if (s.expect[Cls.YELLOW] >= 0.5f) parts += "&eY"
            if (s.expect[Cls.BLOOD] >= 0.5f) parts += "&cB"
            val top = parts.joinToString(" ").ifEmpty { "&7-" }
            small(top, x + room / 2, y + room * 3 / 16)
            small("&f~${s.rooms.roundToInt()}", x + room / 2, y + room * 8 / 16)
        }

        fun small(text: String, cx: Int, cy: Int) {
            val width = text.width().toFloat()
            val scale = minOf(0.45f, (room - 1f) / maxOf(1f, width))
            g.drawCenteredString(text, cx, cy - 9 * scale / 2f, Color.WHITE, scale, false)
        }
    }
}
