package com.github.noamm9.features.impl.floor7.devices

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils

/// fork: tells the party how i4 went, only when you are Berserk (the class that does i4). Armed once Goldor has started
/// and you are standing on the i4 platform (pre4 or later). "i4 Completed" when your device completes; "i4 Failed due
/// to lag!" when you leave the platform or die first, or are not on the platform a second after Goldor starts.
/// Each is sent three times. Party chat accepts repeated lines (only guild chat refuses them, as skylite measured).
object I4PartyAlerts: Feature(
    "Berserk only. Sends \"i4 Completed\" or \"i4 Failed due to lag!\" to party chat 3 times when you finish i4, or leave the platform or die before finishing it, or are not on the i4 platform a second after Goldor starts.",
    name = "I4 Party Alerts",
    toggled = true
) {
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    // A completion that lands just after you leap off (the last arrow was still in flight) is still a completion.
    private const val FAIL_GRACE_MS = 1500L
    private const val PRESENCE_CHECK_MS = 1000L

    @Volatile private var goldorStarted = false
    @Volatile private var armed = false
    @Volatile private var reported = false
    @Volatile private var failedSent = false
    @Volatile private var failAt = 0L
    @Volatile private var goldorAt = 0L
    @Volatile private var presenceChecked = false

    override fun init() {
        register<ChatMessageEvent> {
            val msg = event.unformattedText
            if (msg == GOLDOR_START) {
                reset()
                // Everything below waits on goldorStarted, so a run on any other class sends nothing. A class not read
                // yet (no player entry, or Empty) still sends: a missed i4 alert costs more than a stray one.
                val clazz = DungeonListener.thePlayer?.clazz
                if (clazz != null && clazz != DungeonClass.Empty && clazz != DungeonClass.Berserk) return@register
                goldorStarted = true
                goldorAt = System.currentTimeMillis()
                return@register
            }
            if (! goldorStarted || ! msg.contains("completed a device!")) return@register
            if (I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) != mc.user.name) return@register
            // Said "Failed" already and the device completes after all (late, or you came back to the platform): tell the
            // party so. Recognised as the i4 by being armed or standing on the platform, as any other device is not.
            if (failedSent) {
                if (armed || onPlatform()) report("i4 Completed")
                return@register
            }
            if (! armed || reported) return@register
            report("i4 Completed")
        }

        register<DungeonEvent.PlayerDeathEvent> {
            if (armed && ! reported && failAt == 0L && event.name == mc.user.name) failAt = System.currentTimeMillis()
        }

        register<TickEvent.End> {
            if (! goldorStarted || reported || mc.player == null) return@register
            if (! armed) {
                // fork: LocationUtils reads Hypixel's packets and knows no phase in the P3 Sim, so the sim counts as phase 3
                if ((LocationUtils.F7Phase == 3 || ChatUtils.inP3Sim()) && I4Helper.isOnDev()) armed = true
                // A second after Goldor's opening line you should be on the platform; not there means no i4.
                else if (! presenceChecked && System.currentTimeMillis() - goldorAt >= PRESENCE_CHECK_MS) {
                    presenceChecked = true
                    if (! onPlatform()) report("i4 Failed due to lag!")
                }
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
        // A "Failed" is not final: a completion that comes after it is still reported (see the chat handler), once.
        failedSent = text.startsWith("i4 Failed")
        reported = true
        // fork: a quarter second apart, so a chat compactor does not fold the three into one line
        repeat(3) { i -> if (i == 0) ChatUtils.sendPartyMessage(text) else ThreadUtils.scheduledTask(i * 5) { ChatUtils.sendPartyMessage(text) } }
    }

    private fun reset() {
        goldorStarted = false
        armed = false
        reported = false
        failedSent = false
        failAt = 0L
        goldorAt = 0L
        presenceChecked = false
    }
}
