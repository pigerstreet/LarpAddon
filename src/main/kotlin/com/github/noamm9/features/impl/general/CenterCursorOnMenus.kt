package com.github.noamm9.features.impl.general

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.ContainerFullyOpenedEvent
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.mixin.IServerboundChatCommandPacket
import gg.essential.universal.UResolution
import org.lwjgl.glfw.GLFW

/// fork: the same centring TerminalSolver's "Center Cursor" does, for menus opened by command. Without it NoFrills'
/// No Cursor Reset puts the cursor back where it was in the chat box the command was typed in.
object CenterCursorOnMenus: Feature("Moves your cursor to the middle of the screen when /stats or /loadouts opens.", toggled = true) {
    private val commands = setOf("stats", "loadouts", "loadout")

    // When the command was sent, plus a window for the server to answer. 0 = nothing pending.
    @Volatile private var pendingUntil = 0L

    override fun init() {
        register<PacketEvent.Sent> {
            val packet = event.packet as? IServerboundChatCommandPacket ?: return@register
            if (packet.command.substringBefore(' ').lowercase() in commands) pendingUntil = System.currentTimeMillis() + 3000
        }

        // Fires once every slot has arrived, i.e. after the screen opened and No Cursor Reset ran.
        register<ContainerFullyOpenedEvent> {
            if (pendingUntil == 0L) return@register
            val inTime = System.currentTimeMillis() <= pendingUntil
            pendingUntil = 0L
            if (inTime) mc.execute {
                GLFW.glfwSetCursorPos(mc.window.handle(), UResolution.windowWidth / 2.0, UResolution.windowHeight / 2.0)
            }
        }
    }
}
