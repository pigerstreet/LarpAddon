package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.mixin.IAbstractContainerScreen
import com.github.noamm9.ui.utils.componnents.UIButton
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import kotlinx.serialization.json.*
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import java.awt.Color
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

/// fork: the Better PF site's live Party Finder list (undonecoffee.com/betterpf/menu) as a screen of its own, opened by a
/// button beside the real Party Finder. The site's bot sits in the Party Finder and refreshes it; this reads the same
/// public viewer feed the page does (nothing about you is sent), and a click on a party clicks that party's head in the
/// real menu when it is on the page that is open. Nothing here runs unless the Party Finder is open.

/// The viewer feed. One WebSocket, opened when the Party Finder opens and dropped when it closes.
object PfMenuFeed {
    private const val URL = "wss://undonecoffee.com/betterpf/api/pfmenu"
    private const val PING_MS = 30_000L
    private const val RETRY_MS = 5_000L
    val FLOORS = listOf("F7", "M7")

    class Member(val name: String, val cls: String, val level: Int, val cata: Double?, val secrets: Int?, val pb: Long?)
    class Party(val leader: String, val note: String, val members: List<Member>, val floorName: String, val dungeon: String)
    class Listing(val at: Long, val parties: List<Party>)
    class Bot(val connected: Boolean, val state: String)

    @Volatile var listings: Map<String, Listing> = emptyMap()
        private set
    @Volatile var bot: Bot? = null
        private set
    @Volatile var connected = false
        private set
    /** The site's clock minus ours, from its `now`, so a listing's age is not off by the two clocks. */
    @Volatile private var skew = 0L

    private var ws: WebSocket? = null
    private var connecting = false
    private var wanted = false
    private var retryAt = 0L
    private var lastPing = 0L

    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /** Seconds since the site's bot last read [floor]'s menu, or null when it has not. */
    fun ageSeconds(floor: String): Long? = listings[floor]?.let { (System.currentTimeMillis() + skew - it.at).coerceAtLeast(0L) / 1000 }

    /** Makes sure the feed is up (and kept alive); cheap to call every frame. */
    @Synchronized fun ensure() {
        wanted = true
        val now = System.currentTimeMillis()
        val socket = ws
        if (socket != null) {
            if (connected && now - lastPing >= PING_MS) {
                lastPing = now
                socket.sendText("ping", true)
            }
            return
        }
        if (connecting || now < retryAt) return
        connecting = true
        retryAt = now + RETRY_MS
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(URL), Listener()).whenComplete { socket2, err ->
            synchronized(this) {
                connecting = false
                if (err != null) return@synchronized
                if (wanted) {
                    ws = socket2
                    lastPing = System.currentTimeMillis()
                }
                else socket2.abort()
            }
        }
    }

    @Synchronized fun close() {
        wanted = false
        ws?.abort()
        ws = null
        connected = false
        listings = emptyMap()
        bot = null
    }

    private class Listener: WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            connected = true
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val whole = text.toString()
                text.setLength(0)
                runCatching { handle(whole) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            dropped(webSocket)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) = dropped(webSocket)

        private fun dropped(webSocket: WebSocket) = synchronized(PfMenuFeed) {
            if (ws === webSocket) {
                ws = null
                connected = false
            }
        }
    }

    private fun handle(text: String) {
        if (text == "pong") return
        val msg = Json.parseToJsonElement(text).jsonObject
        msg.num("now")?.let { skew = it.toLong() - System.currentTimeMillis() }
        msg.obj("bot")?.let { bot = Bot(it.bool("connected") == true, it.str("state").orEmpty()) }
        when (msg.str("t")) {
            "all" -> {
                val all = msg.obj("listings") ?: return
                listings = FLOORS.mapNotNull { f -> all.obj(f)?.let(::listing)?.let { f to it } }.toMap()
            }
            "menu" -> {
                val floor = msg.str("floor")?.takeIf { it in FLOORS } ?: return
                val l = msg.obj("listing")?.let(::listing) ?: return
                listings = listings + (floor to l)
            }
        }
    }

    private fun listing(o: JsonObject): Listing? {
        val at = o.num("at")?.toLong() ?: return null
        val parties = o.obj("menu")?.arr("parties").orEmpty().mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            val leader = po.str("leader") ?: return@mapNotNull null
            val members = po.arr("members").orEmpty().mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val stats = mo.obj("stats")
                Member(
                    mo.str("name") ?: return@mapNotNull null, mo.str("cls").orEmpty(), mo.num("level")?.toInt() ?: 0,
                    stats?.num("cata"), stats?.num("secrets")?.toInt(), stats?.num("pb")?.toLong()
                )
            }
            Party(leader, po.str("note").orEmpty(), members, po.str("floor").orEmpty(), po.str("dungeon").orEmpty())
        }
        return Listing(at, parties)
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/// The "Better PF" button on the real Party Finder, to the right of its window.
object PfMenuButton {
    fun register(active: () -> Boolean) {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*> || ! active()) return@register
            if (screen.title.string != "Party Finder") return@register
            val base = screen as IAbstractContainerScreen
            Screens.getWidgets(screen).add(UIButton(base.leftPos + 176 + 6, base.topPos + 6, 72, 18, "§bBetter PF") {
                PfMenuScreen.open(screen)
            })
        }
    }
}

/// The screen. The real Party Finder stays the game's open container underneath: it is never closed from here, only
/// kept (the screen field is set directly, as AuctionPriceInput does for its sign), and Esc goes back to it.
class PfMenuScreen private constructor(private val parent: AbstractContainerScreen<*>): Screen(Component.literal("Better PF")) {
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val party: PfMenuFeed.Party)

    private var floor = "F7"
    private var first = 0
    private val hits = ArrayList<Hit>()
    private lateinit var tabF7: UIButton
    private lateinit var tabM7: UIButton

    /// The panel sits in the middle of the screen: a toolbar on top, then the site's grid of party cards.
    private class Layout(val left: Int, val top: Int, val panelW: Int, val panelH: Int, val cols: Int, val rows: Int, val gridLeft: Int)

    private fun layout(): Layout {
        val rows = ((height - 16 - TOOLBAR_H - PAD) / (CARD_H + GAP)).coerceIn(1, 4)
        val cols = ((width - 16 - 2 * PAD + GAP) / (CARD_W + GAP)).coerceIn(3, 6)
        val gridW = cols * CARD_W + (cols - 1) * GAP
        val panelW = (gridW + 2 * PAD).coerceAtLeast(350).coerceAtMost(width - 8)
        val panelH = TOOLBAR_H + rows * (CARD_H + GAP) + PAD
        return Layout((width - panelW) / 2, ((height - panelH) / 2).coerceAtLeast(4), panelW, panelH, cols, rows, (width - gridW) / 2)
    }

    override fun init() {
        super.init()
        val l = layout()
        val x0 = l.left + PAD
        tabF7 = addRenderableWidget(UIButton(x0 + 62, l.top + PAD, 40, 18, "F7") { floor = "F7"; first = 0 })
        tabM7 = addRenderableWidget(UIButton(x0 + 104, l.top + PAD, 40, 18, "M7") { floor = "M7"; first = 0 })
        addRenderableWidget(UIButton(l.left + l.panelW - PAD - 50, l.top + PAD, 50, 18, "Back") { back() })

        /// The site's two filters: "Need" lifts the parties missing that class and fades the rest, "Sort" orders them.
        var x = x0 + 28
        for ((cls, short) in SHORT) {
            addRenderableWidget(UIButton(x, l.top + 28, 32, 16, short, { if (wantClass == cls) CLASS_COLOR.getValue(cls) else Color(60, 60, 60) }) {
                wantClass = if (wantClass == cls) null else cls
                first = 0
            })
            x += 34
        }
        x += 32
        for ((key, label) in SORTS) {
            val w = if (key == "pb") 46 else 34
            addRenderableWidget(UIButton(x, l.top + 28, w, 16, label, { if (sortBy == key) ACTIVE else Color(60, 60, 60) }) {
                sortBy = key
                first = 0
            })
            x += w + 2
        }
    }

    override fun isPauseScreen() = false

    private fun back() {
        mc.screen = parent
    }

    override fun onClose() = back()

    /// Only reached when something other than [back] replaces this screen (the server closing the menu, say): the
    /// real Party Finder is gone with it, so its state goes too.
    override fun removed() {
        PfMenuFeed.close()
        PartyFinder.menuScreenLost()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        PfMenuFeed.ensure()
        tabF7.overrideColor = if (floor == "F7") ACTIVE else null
        tabM7.overrideColor = if (floor == "M7") ACTIVE else null

        val l = layout()
        val x0 = l.left + PAD
        g.drawRect(l.left, l.top, l.panelW, l.panelH, Color(0, 0, 0, 175))
        g.drawString("§fBetter §9PF", x0, l.top + PAD + 5, Color.WHITE)
        g.drawString("§7Need", x0, l.top + 32, Color.WHITE)
        g.drawString("§7Sort", x0 + 28 + 170 + 2, l.top + 32, Color.WHITE)

        val listing = PfMenuFeed.listings[floor]
        val bot = PfMenuFeed.bot
        val status = when {
            ! PfMenuFeed.connected -> "§7Connecting to the Better PF site..."
            bot != null && ! bot.connected -> "§cThe site's bot is offline: this list is not updating."
            listing == null -> "§7Waiting for the site's first read of $floor..."
            else -> "§a■ §7Bot online · $floor read ${PfMenuFeed.ageSeconds(floor)}s ago · §f${onFloor(listing.parties).size} §7parties"
        }
        g.drawString(status, x0, l.top + 50, Color.WHITE)
        g.drawString("§7$floor S+ ${bandKey()}", x0, l.top + 60, Color.WHITE)

        hits.clear()
        val parties = ordered(onFloor(listing?.parties.orEmpty()))
        val totalRows = (parties.size + l.cols - 1) / l.cols
        first = first.coerceIn(0, (totalRows - l.rows).coerceAtLeast(0))
        for (r in 0 until l.rows) for (c in 0 until l.cols) {
            val idx = (first + r) * l.cols + c
            if (idx >= parties.size) break
            val p = parties[idx]
            val cx = l.gridLeft + c * (CARD_W + GAP)
            val cy = l.top + TOOLBAR_H + r * (CARD_H + GAP)
            drawCard(g, p, cx, cy, mouseX in cx until cx + CARD_W && mouseY in cy until cy + CARD_H)
            hits += Hit(cx, cy, CARD_W, CARD_H, p)
        }
        if (parties.isEmpty() && listing != null) g.drawString("§7No parties on $floor right now.", x0, l.top + TOOLBAR_H + 4, Color.WHITE)

        super.extractRenderState(g, mouseX, mouseY, a)
    }

    /// One card, as on the site: the note, then a row per member (class-coloured name, Cata, S+ PB), then what is missing.
    private fun drawCard(g: GuiGraphicsExtractor, p: PfMenuFeed.Party, x: Int, y: Int, hovered: Boolean) {
        val missing = missingOf(p)
        val want = wantClass != null && wantClass in missing
        // Faded: a slow lead (a member with an S+ PB over the floor's limit or none), or not the class asked for.
        val faded = slowLead(p) || (wantClass != null && ! want)
        val border = when {
            want -> Color(85, 255, 255)
            faded -> Color(45, 45, 50)
            hovered -> Color(200, 200, 200)
            else -> Color(70, 80, 110)
        }
        g.drawRect(x, y, CARD_W, CARD_H, if (want) Color(18, 36, 60, 235) else Color(20, 22, 32, 220))
        g.drawRect(x, y, CARD_W, 1, border)
        g.drawRect(x, y + CARD_H - 1, CARD_W, 1, border)
        g.drawRect(x, y, 1, CARD_H, border)
        g.drawRect(x + CARD_W - 1, y, 1, CARD_H, border)

        fun line(text: String, lx: Int, ly: Int) =
            if (faded) g.drawString("§8" + text.removeFormatting(), lx, ly, Color.WHITE) else g.drawString(text, lx, ly, Color.WHITE)
        fun right(text: String, rx: Int, ly: Int) = line(text, rx - mc.font.width(text.removeFormatting()), ly)

        line("§f" + fit(p.note.ifEmpty { p.leader }, CARD_W - 8), x + 4, y + 3)
        val nameMax = CARD_W - 8 - 20 - 28 - 6
        for ((j, m) in p.members.withIndex()) {
            val ry = y + 15 + j * 10
            val lead = m.name.equals(p.leader, ignoreCase = true)
            val code = CLASS_CODE[m.cls.lowercase()] ?: "§f"
            line((if (lead) "§6♛" else "") + code + fit(m.name, nameMax - (if (lead) 8 else 0)), x + 4, ry)
            m.cata?.let { right("${cataColor(it)}${it.toInt()}", x + CARD_W - 4 - 28, ry) }
            m.pb?.let { right(if (it > 0) "${bandColor(it)}${formatPb(it)}" else "§8-", x + CARD_W - 4, ry) }
        }
        val openCount = (5 - p.members.size).coerceAtLeast(0)
        if (openCount > 0 && missing.isNotEmpty()) {
            line("§7+$openCount " + missing.joinToString(" ") { "${CLASS_CODE.getValue(it)}${SHORT.getValue(it)}" }, x + 4, y + 15 + p.members.size * 10)
        }
    }

    /// [text] cut with ".." so it is no wider than [maxW].
    private fun fit(text: String, maxW: Int): String {
        if (mc.font.width(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && mc.font.width("$s..") > maxW) s = s.dropLast(1)
        return "$s.."
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val mx = event.x()
        val my = event.y()
        val hit = hits.firstOrNull { mx >= it.x && mx < it.x + it.w && my >= it.y && my < it.y + it.h } ?: return false
        join(hit.party)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        first = (first - Math.signum(vertical).toInt()).coerceAtLeast(0)
        return true
    }

    /// Clicks the party's head in the real Party Finder, then shows that menu again so Hypixel's answer is seen.
    private fun join(party: PfMenuFeed.Party) {
        val player = mc.player ?: return
        val slot = parent.menu.slots.firstOrNull { s ->
            s.container !== player.inventory && s.item.`is`(Items.PLAYER_HEAD) &&
                s.item.hoverName.string.removeFormatting().trim().removeSuffix("'s Party").equals(party.leader, ignoreCase = true)
        }
        if (slot == null) {
            ChatUtils.modMessage("&cThat party isn't on the Party Finder page you have open: it may be on another page, or the site's list is a few seconds behind.")
            return
        }
        back()
        GuiUtils.clickSlot(slot.index, GuiUtils.ButtonType.LEFT)
    }

    private fun formatPb(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // The site's list logic (undonecoffee.com/betterpf), kept the same so the two read alike.
    /// Floor VII, master mode or not; a party whose floor the feed did not name is kept.
    private fun onFloor(list: List<PfMenuFeed.Party>) = list.filter { p ->
        (p.floorName.isBlank() && p.dungeon.isBlank()) ||
            (p.floorName.trim() == "Floor VII" && p.dungeon.contains("master", ignoreCase = true) == (floor == "M7"))
    }

    /// The classes a party still lacks; none when it is full.
    private fun missingOf(p: PfMenuFeed.Party): List<String> =
        if (p.members.size >= 5) emptyList() else CLASS_CODE.keys.filter { c -> p.members.none { it.cls.lowercase() == c } }

    /// A party's slowest S+ PB: a member with none counts as the slowest there is; members whose stats have not arrived
    /// or are under the Cata bar (no PB checked) are left out. 0: nobody's in yet.
    private fun slowest(p: PfMenuFeed.Party): Long {
        var worst = 0L
        for (m in p.members) m.pb?.let { worst = maxOf(worst, if (it == 0L) Long.MAX_VALUE else it) }
        return worst
    }

    private fun avgCata(p: PfMenuFeed.Party): Double {
        val known = p.members.mapNotNull { it.cata }
        return if (known.isEmpty()) 0.0 else known.sum() / known.size
    }

    /// Any member with an S+ PB over the floor's limit (as shown) or none at all.
    private fun slowLead(p: PfMenuFeed.Party): Boolean {
        val limit = GREY_OVER.getValue(floor)
        return p.members.any { m -> m.pb?.let { it == 0L || it / 1000 > limit } == true }
    }

    private fun ordered(list: List<PfMenuFeed.Party>): List<PfMenuFeed.Party> {
        fun slow(p: PfMenuFeed.Party) = slowest(p).let { if (it == 0L) Long.MAX_VALUE else it }
        return when (sortBy) {
            "cata" -> list.sortedWith(compareByDescending<PfMenuFeed.Party> { avgCata(it) }.thenBy { slow(it) })
            "open" -> list.sortedWith(compareBy<PfMenuFeed.Party> { it.members.size }.thenBy { slow(it) })
            else -> list.sortedWith(compareBy<PfMenuFeed.Party> { slow(it) }.thenByDescending { it.members.size })
        }
    }

    /// An S+ PB's colour: the floor's bands, then dark grey for slower.
    private fun bandColor(ms: Long): String {
        val s = (ms / 1000).toInt()
        return BANDS.getValue(floor).firstOrNull { s <= it.first }?.second ?: "§8"
    }

    private fun cataColor(c: Double) = if (c < 30) "§5" else if (c < 40) "§9" else if (c >= 50) "§d" else "§f"

    private fun bandKey(): String {
        val b = BANDS.getValue(floor)
        return b.joinToString(" ") { "${it.second}≤${it.first / 60}:${(it.first % 60).toString().padStart(2, '0')}" } + " §8slower"
    }

    companion object {
        private val ACTIVE = Color(85, 255, 85)

        // The panel's measurements, in scaled pixels.
        private const val PAD = 6
        private const val GAP = 4
        private const val TOOLBAR_H = 74
        private const val CARD_W = 128
        private const val CARD_H = 68

        // Odin's class colours, as the site has them.
        private val SHORT = linkedMapOf("archer" to "Arch", "berserk" to "Bers", "healer" to "Heal", "mage" to "Mage", "tank" to "Tank")
        private val SHORT_OF = SHORT
        private val CLASS_CODE = linkedMapOf("archer" to "§6", "berserk" to "§4", "healer" to "§d", "mage" to "§b", "tank" to "§2")
        private val CLASS_COLOR = mapOf(
            "archer" to Color(255, 170, 0), "berserk" to Color(170, 0, 0), "healer" to Color(255, 85, 255),
            "mage" to Color(85, 255, 255), "tank" to Color(0, 170, 0),
        )
        private val SORTS = linkedMapOf("pb" to "Fastest", "cata" to "Cata", "open" to "Open")

        /// S+ PB bands per floor, as (up to this many seconds, inclusive; colour): F7 dark green to 2:50, green to 3:20,
        /// yellow to 3:35, red to 3:50, dark red to 4:10; M7 to 3:40, 3:50, 4:10, 4:30, 4:50.
        private val BANDS = mapOf(
            "F7" to listOf(170 to "§2", 200 to "§a", 215 to "§e", 230 to "§c", 250 to "§4"),
            "M7" to listOf(220 to "§2", 230 to "§a", 250 to "§e", 270 to "§c", 290 to "§4"),
        )
        private val GREY_OVER = mapOf("F7" to 300, "M7" to 330)

        // Kept for the session, as the site keeps them: the class asked for, and the order.
        private var wantClass: String? = null
        private var sortBy = "pb"

        fun open(parent: AbstractContainerScreen<*>) {
            PfMenuFeed.ensure()
            mc.screen = PfMenuScreen(parent).apply { init(parent.width, parent.height) }
        }
    }
}
