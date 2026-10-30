@file:Suppress("DEPRECATION")

package com.github.noamm9.utils.network.cache

import com.github.noamm9.utils.network.abstracts.CacheResult
import com.github.noamm9.utils.network.abstracts.CachedEntry
import com.github.noamm9.utils.network.abstracts.NetworkCache
import com.github.noamm9.utils.network.data.DungeonStats
import com.google.common.cache.CacheBuilder
import java.util.concurrent.*

object ProfileCache: NetworkCache<String, DungeonStats> {
    private val storage = CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.HOURS).build<String, CachedEntry<DungeonStats>>()

    /// fork: the stats APIs rate-limit (429/503) in bursts, and a failure used to live as long as a success -
    /// the one-hour expireAfterAccess left "(Failed)" next to a player for an hour and never retried. A failed
    /// lookup now stops counting after 90 s, so the next Party Finder render asks again.
    private const val FAILED_TTL_MS = 90_000L

    override fun addToCache(key: String, value: DungeonStats) = storage.put(key.lowercase(), CachedEntry(value))
    override fun addFailedToCache(key: String) = storage.put(key.lowercase(), CachedEntry(null))

    override fun get(key: String): CacheResult<DungeonStats> {
        val k = key.lowercase()
        val entry = storage.getIfPresent(k) ?: return CacheResult.NotFound
        if (entry.value == null) {
            if (System.currentTimeMillis() - entry.timestamp < FAILED_TTL_MS) return CacheResult.Failed
            storage.invalidate(k)
            return CacheResult.NotFound
        }
        return CacheResult.Success(entry.value)
    }
}