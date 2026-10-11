package com.github.noamm9.utils.network

import com.github.noamm9.utils.network.data.DungeonStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/// fork: where a profile comes from when api.noamm.org cannot answer (its sign-in rate-limited, or the server down).
/// First Odin's public Hypixel proxy, the whole profile; then EliteBot's, which has the dungeon data and the pets but no
/// inventories, so no armour and no Magical Power (talismanBagData stays blank, which every MP reader already treats as
/// "inventory API off"). Both answer with Hypixel's own member fields, read here into a DungeonStats.
object ProfileFallback {
    private const val ODIN = "https://hypixel.odtheking.com/get/"
    private const val ELITE = "https://api.elitebot.dev/profile/"

    /// A uuid neither source could answer for, and until when it is not asked again: the Party Finder tooltip asks again
    /// every frame a lookup is not cached, which would otherwise be two downloads per frame per player.
    private const val RETRY_AFTER_MS = 60_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    suspend fun dungeonStats(uuid: String): Result<DungeonStats> {
        val id = uuid.replace("-", "").lowercase()
        if ((failedUntil[id] ?: 0L) > System.currentTimeMillis()) return Result.failure(IOException("no stats source answered recently"))
        val odin = runCatching { fromOdin(id) }
        if (odin.isSuccess) return odin
        val elite = runCatching { fromElite(id) }
        if (elite.isSuccess) return elite
        failedUntil[id] = System.currentTimeMillis() + RETRY_AFTER_MS
        return Result.failure(IOException("Odin: ${odin.exceptionOrNull()?.message}; EliteBot: ${elite.exceptionOrNull()?.message}"))
    }

    private suspend fun fetch(url: String): JsonObject {
        val res = WebUtils.get(url).getOrThrow()
        if (! res.status.isSuccess()) throw IOException("HTTP ${res.status.value}")
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun fromOdin(id: String): DungeonStats {
        val profiles = fetch(ODIN + id).arr("profiles") ?: throw IOException("no profiles")
        val profile = profiles.mapNotNull { it as? JsonObject }.firstOrNull { it.bool("selected") == true } ?: throw IOException("no selected profile")
        val member = profile.obj("members")?.obj(id) ?: throw IOException("not a member of the selected profile")
        return fromMember(member, member.obj("pets_data")?.arr("pets"))
    }

    private suspend fun fromElite(id: String): DungeonStats {
        val root = fetch("$ELITE$id/selected")
        val dungeons = root.obj("unparsed")?.obj("dungeons") ?: throw IOException("no dungeon data")
        return fromMember(buildJsonObject { put("dungeons", dungeons) }, root.arr("pets"))
    }

    private fun fromMember(m: JsonObject, pets: JsonArray?): DungeonStats {
        val d = m.obj("dungeons") ?: throw IOException("no dungeon data")
        val cata = d.obj("dungeon_types")?.obj("catacombs")
        val master = d.obj("dungeon_types")?.obj("master_catacombs")
        val kills = m.obj("player_stats")?.obj("kills")
        val inventory = m.obj("inventory")
        return DungeonStats(
            favoriteArrow = m.obj("item_data")?.str("favorite_arrow").orEmpty(),
            selectedPower = m.obj("accessory_bag_storage")?.str("selected_power").orEmpty(),
            abiphoneContacts = m.obj("nether_island_player_data")?.obj("abiphone")?.arr("active_contacts")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            consumedRiftPrism = m.obj("rift")?.obj("access")?.bool("consumed_prism") == true,
            // As Odin counts them: the Watcher's summoned undead, normal and master.
            bloodMobsKilled = ((kills?.num("watcher_summon_undead") ?: 0.0) + (kills?.num("master_watcher_summon_undead") ?: 0.0)).toInt(),
            dungeons = DungeonStats.DungeonData(
                totalRuns = runs(cata) + runs(master),
                secrets = d.num("secrets")?.toInt() ?: 0,
                playerClasses = d.obj("player_classes")?.entries
                    ?.mapNotNull { (name, c) -> (c as? JsonObject)?.num("experience")?.let { name to it } }?.toMap().orEmpty(),
                catacombsExperience = cata?.num("experience") ?: 0.0,
                catacombs = tier(cata),
                masterCatacombs = tier(master)
            ),
            pets = pets.orEmpty().mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                DungeonStats.PetSummary(o.str("type") ?: return@mapNotNull null, o.str("tier").orEmpty())
            },
            armorData = inventory?.obj("inv_armor")?.str("data").orEmpty(),
            talismanBagData = inventory?.obj("bag_contents")?.obj("talisman_bag")?.str("data").orEmpty()
        )
    }

    /// Hypixel keeps a "total" beside the per-floor counts; summed by hand where it does not.
    private fun runs(type: JsonObject?): Int {
        val c = type?.obj("tier_completions") ?: return 0
        return (c.num("total") ?: c.filterKeys { it != "total" }.values.sumOf { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }).toInt()
    }

    private fun tier(type: JsonObject?) = DungeonStats.TierData(
        tierCompletions = type?.obj("tier_completions")?.numbers()?.mapValues { it.value.toInt() }.orEmpty(),
        fastestTimeSPlus = type?.obj("fastest_time_s_plus")?.numbers()?.mapValues { it.value.toLong() }.orEmpty()
    )

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.numbers() = entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()
}
