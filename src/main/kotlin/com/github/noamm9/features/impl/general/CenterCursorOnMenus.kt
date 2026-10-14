package com.github.noamm9.features.impl.general

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.TerminalSolver
import com.github.noamm9.features.impl.floor7.terminals.impl.Terminal
import com.github.noamm9.mixin.IServerboundChatCommandPacket
import com.github.noamm9.utils.location.LocationUtils
import gg.essential.universal.UResolution
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import org.lwjgl.glfw.GLFW

/// fork: the same centring TerminalSolver's "Center Cursor" does, for menus opened by command (and the spirit leap).
/// Without it NoFrills' No Cursor Reset puts the cursor back where it was in the chat box the command was typed in.
/// It happens the moment the server opens the window (the open-screen packet), not once every slot has arrived, so
/// the cursor is already in the middle by the time the menu is drawn.
object CenterCursorOnMenus: Feature("Moves your cursor to the middle of the screen when /stats, /equipment or /loadouts opens, and when the spirit leap menu or a terminal opens.", toggled = true) {
    private val commands = setOf("stats", "loadouts", "loadout", "equipment", "equip", "eq", "wardrobe", "sbmenu", "menu")

    /// Window titles (lower case, contained in the title) that centre the cursor whatever opened them.
    private val menuTitles = listOf("skyblock menu", "wardrobe", "loadout", "equipment")

    // When the command was sent, plus a window for the server to answer. 0 = nothing pending.
    @Volatile private var pendingUntil = 0L

    /// Set just before an open-screen packet is handled: a container screen was already up, so the packet replaces it.
    @Volatile private var replacingContainer = false

    /// When the cursor was last put in the middle here, so TerminalSolver's own (later) centring does not move it again.
    @Volatile var lastCenteredAt = 0L
        private set

    /// NoFrills' No Cursor Reset stores the cursor when a screen closes and puts it back when the next one opens, which
    /// is what pulled the cursor away from the middle for a frame. Seeding its stored position with the middle of the
    /// screen means the menu opens with the cursor already there (reflection: NoFrills is optional).
    private val nfX = runCatching { Class.forName("nofrills.features.tweaks.NoCursorReset").getField("cursorX") }.getOrNull()
    private val nfY = runCatching { Class.forName("nofrills.features.tweaks.NoCursorReset").getField("cursorY") }.getOrNull()

    private fun seedNoFrills() {
        runCatching {
            nfX?.setDouble(null, UResolution.windowWidth / 2.0)
            nfY?.setDouble(null, UResolution.windowHeight / 2.0)
        }
    }

    override fun init() {
        register<PacketEvent.Sent> {
            val packet = event.packet as? IServerboundChatCommandPacket ?: return@register
            if (packet.command.substringBefore(' ').lowercase() in commands) {
                pendingUntil = System.currentTimeMillis() + 3000
                seedNoFrills()
            }
        }

        // The chat screen closes after the command is sent and NoFrills stores the cursor then, so seed again each tick
        // until the menu arrives.
        register<TickEvent.End> {
            if (pendingUntil != 0L && System.currentTimeMillis() <= pendingUntil) seedNoFrills()
        }

        // Whether a container screen was up when the open-screen packet arrived (checked before the packet replaces it).
        register<MainThreadPacketReceivedEvent.Pre> {
            if (event.packet is ClientboundOpenScreenPacket) replacingContainer = mc.gui.screen() is AbstractContainerScreen<*>
        }

        // After the open-screen packet was handled on the main thread, i.e. after the screen exists and No Cursor
        // Reset ran for it: centre straight away.
        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            val title = packet.title.string
            val now = System.currentTimeMillis()
            // A menu that replaces a menu (equipping armor or equipment in /stats, a wardrobe slot, the SkyBlock menu's
            // buttons: the server re-sends the window each time) keeps the cursor where the click was.
            val replacing = replacingContainer
            replacingContainer = false
            val wanted = when {
                pendingUntil != 0L && now <= pendingUntil -> ! replacing
                LocationUtils.inDungeon && title.lowercase().let { it.contains("spirit leap") || it.contains("teleport to player") } -> true
                // Opened by an item or a click rather than a command: the SkyBlock menu, both wardrobes, loadouts, equipment.
                title.lowercase().let { t -> menuTitles.any { t.contains(it) } } -> ! replacing
                TerminalSolver.centerCursor.value && LocationUtils.F7Phase == 3 &&
                    with(TerminalSolver) { Terminal.fromTitle(title)?.enabled() == true } -> true
                else -> false
            }
            pendingUntil = 0L
            if (! wanted) return@register
            center(now)
        }
    }

    private fun center(now: Long) {
        lastCenteredAt = now
        GLFW.glfwSetCursorPos(mc.window.handle(), UResolution.windowWidth / 2.0, UResolution.windowHeight / 2.0)
    }
}
