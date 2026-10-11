package com.github.noamm9.features.impl.floor7.devices

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils

/// fork: tells the party how i4 went, only when you are Berserk (the class that does i4). Armed once Goldor has started
/// and you are standing on the i4 platform (pre4 or later). "i4 Completed" when your device completes; "i4 Failed due
/// to lag!" when you leave the platform or die first, or are not on the platform at all when Goldor starts.
/// Each is sent three times. Party chat accepts repeated lines (only guild chat refuses them, as skylite measured).
object I4PartyAlerts: Feature(
    "Berserk only. Sends \"i4 Completed\" or \"i4 Failed due to lag!\" to party chat 3 times when you finish i4, or leave the platform or die before finishing it, or are not on the i4 platform when Goldor starts.",
    name = "I4 Party Alerts",
    toggled = true
) {
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    // A completion that lands just after you leap off (the last arrow was still in flight) is still a completion.
    private const val FAIL_GRACE_MS = 1500L

    @Volatile private var goldorStarted = false
    @Volatile private var armed = false
    @Volatile private var reported = false
    @Volatile private var failAt = 0L

    override fun init() {
        register<ChatMessageEvent> {
            val msg = event.unformattedText
            if (msg == GOLDOR_START) {
                reset()
                // Everything below waits on goldorStarted, so a run on any other class sends nothing.
                if (DungeonListener.thePlayer?.clazz != DungeonClass.Berserk) return@register
                goldorStarted = true
                if (! onPlatform()) report("i4 Failed due to lag!")
                return@register
            }
            if (! armed || reported || ! msg.contains("completed a device!")) return@register
            if (I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) != mc.user.name) return@register
            report("i4 Completed")
        }

        register<DungeonEvent.PlayerDeathEvent> {
            if (armed && ! reported && failAt == 0L && event.name == mc.user.name) failAt = System.currentTimeMillis()
        }

        register<TickEvent.End> {
            if (! goldorStarted || reported || mc.player == null) return@register
            if (! armed) {
                if (LocationUtils.F7Phase == 3 && I4Helper.isOnDev()) armed = true
                return@register
            }
            if (failAt == 0L) {
                if (! onPlatform()) failAt = System.currentTimeMillis()
            }
            else if (System.currentTimeMillis() - failAt >= FAIL_GRACE_MS) report("i4 Failed due to lag!")
        }

        register<WorldChangeEvent> { reset() }
    }

    // Looser than I4Helper.isOnDev, which needs you standing still at y 127, so a jump on the platform is not a leave.
    private fun onPlatform(): Boolean {
        val p = mc.player ?: return false
        return p.x in 61.5 .. 65.5 && p.z in 33.5 .. 37.5 && p.y in 126.5 .. 130.5
    }

    private fun report(text: String) {
        reported = true
        repeat(3) { ChatUtils.sendPartyMessage(text) }
    }

    private fun reset() {
        goldorStarted = false
        armed = false
        reported = false
        failAt = 0L
    }
}
