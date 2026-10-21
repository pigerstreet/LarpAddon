package com.github.noamm9.utils.dungeons.map.handlers

import com.github.noamm9.ClearInfoApi
import com.github.noamm9.NoammAddons
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.features.impl.dungeon.map.DungeonMap
import com.github.noamm9.features.impl.dungeon.map.MapConfig
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonPlayer
import com.github.noamm9.utils.dungeons.map.core.*
import com.github.noamm9.utils.equalsOneOf
import com.github.noamm9.utils.network.ProfileUtils
import kotlinx.coroutines.*
import net.minecraft.network.chat.*

object ClearInfoUpdater: ISelfInit {
    @Volatile private var runGeneration = 0
    private val componentSeparator = createComponent(" &f|&r ")

    override fun init() {
        // fork: prefer these fresher results, but invalidate a late response from the previous run. Reset outside
        // initStartSecrets so it happens with Player Clear Info off too, when only engineerClient can send.
        EventBus.register<DungeonEvent.RunStartedEvent> {
            runGeneration++
            ClearInfoApi.reset()
            initStartSecrets()
        }
        EventBus.register<DungeonEvent.PlayerDeathEvent> { updateDeaths(event.name, event.reason) }
        EventBus.register<DungeonEvent.RoomEvent.onStateChange> { checkSplits(event.room.data, event.oldState, event.newState, event.roomPlayers) }
        EventBus.register<DungeonEvent.RunEndedEvent> { sendClearInfoMessage() }
    }

    fun checkSplits(room: RoomData, oldState: RoomState, newState: RoomState, players: List<DungeonPlayer>) {
        if (! DungeonMap.enabled) return
        if (! MapConfig.printPlayersClearInfo.value) return
        if (players.isEmpty()) return
        if (room.type.equalsOneOf(RoomType.FAIRY, RoomType.ENTRANCE)) return
        if (! oldState.equalsOneOf(RoomState.UNDISCOVERED, RoomState.DISCOVERED, RoomState.UNOPENED)) return
        if (! newState.equalsOneOf(RoomState.CLEARED, RoomState.GREEN)) return

        if (players.size == 1) DungeonListener.dungeonTeammates.find { it.name == players[0].name }?.clearedRooms?.first?.add(room.name)
        else players.forEach { p -> DungeonListener.dungeonTeammates.find { it.name == p.name }?.clearedRooms?.second?.add(room.name) }
    }

    fun updateDeaths(player: String, reason: String) {
        if (! DungeonMap.enabled) return
        if (! MapConfig.printPlayersClearInfo.value) return
        DungeonListener.dungeonTeammates.find { it.name == player }?.deaths?.add(reason)
    }

    fun initStartSecrets() = NoammAddons.scope.launch(Dispatchers.IO) {
        if (! DungeonMap.enabled) return@launch
        if (! MapConfig.printPlayersClearInfo.value) return@launch
        coroutineScope {
            // fork: 400 ms apart; five lookups at once came back 429 for most of the team.
            val jobs = DungeonListener.dungeonTeammates.mapIndexed { i, teammate ->
                async {
                    delay(400L * i)
                    ProfileUtils.getSecrets(teammate.name).onSuccess {
                        teammate.secretsBeforeRun = it
                    }.onFailure {
                        teammate.secretsBeforeRun = 0
                        ChatUtils.modMessage("Failed to get secrets for &b${teammate.name}&r. &cError: ${it.message}")
                    }
                }
            }

            jobs.awaitAll()
        }
    }

    fun sendClearInfoMessage(): Job {
        // fork: read before the coroutine is scheduled, so a run that starts first cannot hand this one its generation.
        val generation = runGeneration
        return NoammAddons.scope.launch(Dispatchers.IO) { sendClearInfo(generation) }
    }

    private suspend fun CoroutineScope.sendClearInfo(generation: Int) {
        if (! DungeonMap.enabled) return
        if (! MapConfig.printPlayersClearInfo.value) return
        val msgList = DungeonListener.dungeonTeammates.mapIndexed { i, teammate ->
            async {
                // fork: a second and a half after the end (Hypixel's count lags the last secrets), 400 ms apart, a fresh
                // lookup and not the start's cached one. A player with no start total or a failed lookup is "N/A", not 0:
                // 0 is what a failed lookup used to print for the whole team.
                delay(1500L + 400L * i)
                val before = teammate.secretsBeforeRun
                val secretsAfterRun = if (before != 0L) ProfileUtils.getSecrets(teammate.name, fresh = true).getOrNull() else null
                val playerFormatted = "${teammate.clazz.code}${teammate.name}"
                val foundNum: Long? = secretsAfterRun?.let { (it - before).coerceAtLeast(0L) }
                val foundSecrets: String = foundNum?.toString() ?: "N/A"

                val baseComp = createComponent("$playerFormatted&f:&r ")
                val (solo, stacked) = teammate.clearedRooms
                val deaths = teammate.deaths

                val comps = listOfNotNull(
                    getRoomsClearedComponent(solo, stacked),
                    createComponent("&b$foundSecrets Secrets&r"),
                    if (deaths.isEmpty()) null else createComponent("&c${deaths.size} ${if (deaths.size > 1) "Deaths" else "Death"}", deaths.joinToString("\n")),
                )

                comps.forEachIndexed { i, c ->
                    baseComp.append(c)
                    if (i != comps.lastIndex) {
                        baseComp.append(componentSeparator)
                    }
                }

                // fork: the same numbers as one party line, "Name: 32 secrets, 5-7 rooms"
                val rooms = if (stacked.isEmpty()) "${solo.size}" else "${solo.size}-${solo.size + stacked.size}"
                Triple(baseComp, "${teammate.name}: $foundSecrets secrets, $rooms rooms", foundNum)
            }
        }

        val results = msgList.awaitAll()
        if (generation != runGeneration) return
        results.forEach { ChatUtils.chat(it.first) }
        // fork: Send To Party, 250 ms apart (the owner's ranked account has had 4 party lines land within one second).
        // Most secrets first, N/A last. The chat list above stays in team order. engineerClient waits five seconds
        // before its fallback, giving these fresher lookups priority.
        // A run with no teammates has no lines to send, so it must not claim: an empty claim would stop
        // engineerClient from posting a list it does have.
        // fork: the same goes for a claim this mod cannot follow through on. sendPartyMessage refuses
        // outside a real party (no second member) and outside the P3 sim, so claiming first would spend
        // engineerClient's one-shot slot on a send that never happens.
        if (MapConfig.sendClearInfoToParty.value && results.isNotEmpty() && ChatUtils.canSendPartyMessage() && ClearInfoApi.claim()) results.sortedByDescending { it.third ?: - 1L }.forEachIndexed { i, r ->
            NoammAddons.scope.launch {
                delay(250L * i)
                // claimed, so the whole list goes: a new run mid-list must not leave the party half a summary
                NoammAddons.mc.execute { ChatUtils.sendPartyMessage(r.second) }
            }
        }
    }

    private fun getRoomsClearedComponent(solo: Collection<String>, stacked: Collection<String>): MutableComponent {
        return if (solo.size + stacked.size == 0) createComponent("&e0 Rooms&r")
        else {
            val roomRange = if (stacked.isEmpty()) "${solo.size}" else "${solo.size}-${solo.size + stacked.size}"
            val tooltip = buildString {
                append(solo.joinToString("\n") { "$it &b(Solo)&r" })
                if (solo.isNotEmpty() && stacked.isNotEmpty()) append("\n")
                append(stacked.joinToString("\n") { "$it &d(stack)&r" })
            }

            createComponent("&e$roomRange Rooms&r", tooltip)
        }
    }

    private fun createComponent(text: String, hoverText: String? = null): MutableComponent {
        val comp = Component.literal(text.addColor())

        if (hoverText != null) {
            return comp.withStyle { s ->
                s.withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText.addColor())))
            }
        }
        return comp
    }
}
