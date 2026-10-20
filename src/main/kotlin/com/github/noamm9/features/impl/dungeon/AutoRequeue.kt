package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.general.PartyHelper
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.PartyUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonUtils
import com.github.noamm9.utils.location.LocationUtils

object AutoRequeue: Feature() {
    private val requeueCmd by DropdownSetting("Requeue Command", 0, listOf("/joininstance", "/instancerequeue"))
    private val checkParty by ToggleSetting("Check Party", true).withDescription("Should the auto check the party state before running the command.")
    private val minPartySize by SliderSetting("Minimum Party Size", 5L, 1L, 5L, 1L).withDescription("How many players the party needs before the auto runs its command.")
    private val delay by SliderSetting("Delay", 5L, 1L, 10L, 1L).withDescription("Delay in Seconds.")
    private val feedback by ToggleSetting("Feedback", true).withDescription("Print feedback messages from auto in chat.")

    private const val prefix = "&bAutoRequeue &f>"
    private val masterMode get() = if (LocationUtils.isMasterMode) "MASTER_" else ""
    private val floor get() = DungeonUtils.FLOOR_NAMES[LocationUtils.dungeonFloorNumber ?: 0]

    private fun feedBackMessage(msg: String) {
        if (! feedback.value) return
        ThreadUtils.setTimeout(1000) { ChatUtils.modMessage("$prefix $msg") }
    }

    override fun init() {
        register<DungeonEvent.RunEndedEvent>(priority = EventPriority.HIGHEST) {
            if (checkParty.value) {
                if (! PartyUtils.isInParty) return@register feedBackMessage("Not in a party!")
                // fork: the five who played this run still being in the party, not the size of the chat-built member list
                // (it keeps anyone whose "left" line was missed, so it read 6+ and printed "not enough").
                // fork: the threshold is a setting now, and "fewer than the minimum" is the test - `!=` used to refuse a
                // party of more than five, which is the one case this check must never block.
                val need = minPartySize.value.toInt()
                val team = DungeonListener.dungeonTeammates.map { it.name }
                if (team.size >= need) {
                    val here = team.count { it in PartyUtils.members }
                    if (here < need) return@register feedBackMessage("Not enough players in party! ($here of ${team.size} from the run are still in it)")
                }
                else if (PartyUtils.members.size < need) return@register feedBackMessage("Not enough players in party! (${PartyUtils.members.size} known)")
            }
            if (PartyHelper.downtimeList.isNotEmpty()) return@register feedBackMessage("There are players in downtime!")

            ThreadUtils.scheduledTask(delay.value * 20) {
                if (checkParty.value && ! PartyUtils.isLeader()) return@scheduledTask feedBackMessage("You are not the party leader!")
                if (requeueCmd.value == 0) {
                    // fork: the entrance has no _FLOOR segment in its instance id (`CATACOMBS_ENTRANCE`), so the
                    // fixed "CATACOMBS_FLOOR_" prefix sent an id Hypixel does not know and the requeue did nothing.
                    // Same shape as PartyHelper's own joininstance call.
                    val floorIdx = LocationUtils.dungeonFloorNumber ?: 0
                    ChatUtils.sendMessage("/joininstance ${masterMode}CATACOMBS_${if (floorIdx > 0) "FLOOR_" else ""}$floor")
                }
                else ChatUtils.sendMessage("/instancerequeue")
            }
        }
    }
}