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
    class Party(val leader: String, val note: String, val members: List<Member>)
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
            Party(leader, po.str("note").orEmpty(), members)
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

    override fun init() {
        super.init()
        val cx = width / 2
        tabF7 = addRenderableWidget(UIButton(cx - 100, 8, 38, 18, "F7") { floor = "F7"; first = 0 })
        tabM7 = addRenderableWidget(UIButton(cx - 58, 8, 38, 18, "M7") { floor = "M7"; first = 0 })
        addRenderableWidget(UIButton(cx + 20, 8, 60, 18, "Back") { back() })
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

        val panelW = 320.coerceAtMost(width - 16)
        val left = (width - panelW) / 2
        val top = 48
        val bottom = height - 10
        g.drawRect(left - 6, 30, panelW + 12, bottom - 30 + 6, Color(0, 0, 0, 170))

        val listing = PfMenuFeed.listings[floor]
        val bot = PfMenuFeed.bot
        val status = when {
            ! PfMenuFeed.connected -> "§7Connecting to the Better PF site..."
            bot != null && ! bot.connected -> "§cThe site's bot is offline: this list is not updating."
            listing == null -> "§7Waiting for the site's first read of $floor..."
            else -> "§aLive §7· read ${PfMenuFeed.ageSeconds(floor)}s ago · §f${listing.parties.size} §7parties on $floor"
        }
        g.drawString(status, left, 34, Color.WHITE)

        hits.clear()
        val parties = listing?.parties.orEmpty()
        first = first.coerceIn(0, (parties.size - 1).coerceAtLeast(0))
        var y = top
        for (i in first until parties.size) {
            val p = parties[i]
            val h = 13 + p.members.size * 10 + 4
            if (y + h > bottom) break
            val hovered = mouseX in left until left + panelW && mouseY in y until y + h
            g.drawRect(left, y, panelW, h, if (hovered) Color(60, 60, 60, 230) else Color(28, 28, 28, 215))
            val head = "§e${p.leader}" + if (p.note.isNotEmpty()) " §7- §f${p.note}" else ""
            g.drawString(head, left + 4, y + 3, Color.WHITE)
            val open = "§a${(5 - p.members.size).coerceAtLeast(0)} open"
            g.drawString(open, left + panelW - 4 - mc.font.width(open.removeFormatting()), y + 3, Color.WHITE)
            for ((j, m) in p.members.withIndex()) {
                val cata = m.cata?.let { "§b${it.toInt()}" }.orEmpty()
                val secrets = m.secrets?.let { " §8[§a$it§8]" }.orEmpty()
                val pb = m.pb?.let { if (it > 0) " §9${formatPb(it)}" else " §cno S+" }.orEmpty()
                g.drawString("§b${m.name}§7: §e${m.cls} §6${m.level}  $cata$secrets$pb", left + 8, y + 14 + j * 10, Color.WHITE)
            }
            hits += Hit(left, y, panelW, h, p)
            y += h + 3
        }
        if (parties.isEmpty() && listing != null) g.drawString("§7No parties on $floor right now.", left, top, Color.WHITE)

        super.extractRenderState(g, mouseX, mouseY, a)
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

    companion object {
        private val ACTIVE = Color(85, 255, 85)

        fun open(parent: AbstractContainerScreen<*>) {
            PfMenuFeed.ensure()
            mc.screen = PfMenuScreen(parent).apply { init(parent.width, parent.height) }
        }
    }
}
