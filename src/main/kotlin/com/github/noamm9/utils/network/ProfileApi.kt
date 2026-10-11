package com.github.noamm9.utils.network

import com.github.noamm9.NoammAddons
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/// fork: this mod's dungeon stats lookup (NoammAPI), for the owner's Odin overlay to fall back on when Odin's own
/// profile lookup fails. Odin reaches it by reflection, so the class name, `stats`, its signature and the map keys must
/// not change without changing Odin's RequestUtils.kt overlay hunk (`larpStats`) and its callers too.
object ProfileApi {
    /**
     * [name]'s dungeon stats as plain values, so another mod can read them without this mod's classes; null when the
     * lookup fails. Keys: `secrets` and `bloodMobKills` (Int), `fastestSPlus` and `fastestSPlusMaster` (Map of floor
     * number to milliseconds), `magicalPower` (Int, absent when the inventory API is off or it cannot be worked out),
     * `pets` (List of "TYPE:TIER").
     */
    @JvmStatic
    fun stats(name: String): CompletableFuture<Map<String, Any>?> {
        val result = CompletableFuture<Map<String, Any>?>()
        NoammAddons.scope.launch {
            result.complete(runCatching {
                ProfileUtils.getProfile(name).getOrNull()?.let { p ->
                    buildMap {
                        put("secrets", p.dungeons.secrets)
                        put("bloodMobKills", p.bloodMobsKilled)
                        put("fastestSPlus", p.dungeons.catacombs.fastestTimeSPlus)
                        put("fastestSPlusMaster", p.dungeons.masterCatacombs.fastestTimeSPlus)
                        if (p.talismanBagData.isNotBlank()) runCatching { p.magicalPower }.getOrNull()?.let { put("magicalPower", it) }
                        put("pets", p.pets.map { "${it.type}:${it.tier}" })
                    }
                }
            }.getOrNull())
        }
        return result
    }
}
