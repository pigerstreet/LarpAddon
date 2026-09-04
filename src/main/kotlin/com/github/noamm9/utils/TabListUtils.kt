package com.github.noamm9.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.init.types.ISelfInit
import com.google.common.collect.ComparisonChain
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.level.GameType

object TabListUtils: ISelfInit {
    private var cachedLines: List<Pair<Component, PlayerInfo>> = emptyList()
    private var listDirty = true

    /// fork: this marked the list dirty for every `ClientboundPlayerInfoUpdatePacket`, and the server
    /// sends one holding nothing but a new ping for all ~80 players once a second. Each of those threw
    /// away the cache, so the next `getTabList` re-sorted every player through the comparator below and
    /// built a fresh display-name component for each - and `DungeonListener` reads the list on that same
    /// packet, so in a dungeon it also re-ran two regexes over all 80 lines.
    ///
    /// Only three of the eight actions can change what `fetchTabList` produces: the set of players comes
    /// from `onlinePlayers`, which `ADD_PLAYER` grows; the comparator reads `gameMode`, which
    /// `UPDATE_GAME_MODE` sets; and the line itself is the display name, which `UPDATE_DISPLAY_NAME`
    /// sets. Latency, hat, list order, listed and chat init touch none of them - `onlinePlayers` is the
    /// whole map rather than the listed subset, so `UPDATE_LISTED` cannot change it either.
    ///
    /// The remove packet is now handled too. Upstream never invalidated on it, so a player leaving left
    /// their stale entry in the cache until some unrelated update happened to clear it.
    ///
    /// One input is deliberately still not watched: team membership, which the comparator sorts on and
    /// which arrives on `ClientboundSetPlayerTeamPacket`. Upstream ignored it too, Hypixel sends those
    /// constantly, and every line here carries a tab list display name, so the team only ever affects
    /// sort order - not worth giving the saving straight back.
    override fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            when (val packet = event.packet) {
                is ClientboundPlayerInfoUpdatePacket -> {
                    // actions() is an EnumSet, so each of these is a bit test
                    val actions = packet.actions()
                    if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) ||
                        actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) ||
                        actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)
                    ) listDirty = true
                }

                is ClientboundPlayerInfoRemovePacket -> listDirty = true
            }
        }
    }

    fun getTabList(): List<Pair<Component, PlayerInfo>> {
        if (listDirty) {
            cachedLines = fetchTabList()
            listDirty = false
        }
        return cachedLines
    }

    private fun fetchTabList(): List<Pair<Component, PlayerInfo>> {
        val player = mc.player ?: return emptyList()
        val onlinePlayers = player.connection.onlinePlayers
        val sortedPlayers = onlinePlayers.sortedWith(PlayerComparator)
        val result = mutableListOf<Pair<Component, PlayerInfo>>()
        for (info in sortedPlayers) result.add(

            mc.gui.tabList.getNameForDisplay(info) to info)
        return if (result.size > 80) result.subList(0, 80) else result
    }

    private object PlayerComparator: Comparator<PlayerInfo> {
        override fun compare(o1: PlayerInfo, o2: PlayerInfo): Int {
            return ComparisonChain.start()
                .compareTrueFirst(o1.gameMode != GameType.SPECTATOR, o2.gameMode != GameType.SPECTATOR)
                .compare(o1.team?.name.orEmpty(), o2.team?.name.orEmpty())
                .compare(o1.profile.name, o2.profile.name)
                .result()
        }
    }
}