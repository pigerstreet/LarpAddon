package com.github.noamm9.utils.network

import com.github.noamm9.utils.network.data.DungeonStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/// fork: where a profile comes from when api.noamm.org cannot answer (its sign-in rate-limited, or the server down), in
/// order:
///  - Odin's public Hypixel proxy: the whole profile.
///  - devonian's dungeon API: dungeon stats, Magical Power as a number and the dungeon pets; no armour, no per-floor
///    completions (total runs worked out from its secret average). It answers only devonian's own User-Agent.
///  - EliteBot, at either of its domains: Hypixel's dungeon data and every pet; no inventories, so no Magical Power.
/// Soopy's API is left out on purpose: it has no secrets total, which would read as 0 and auto-kick on it.
object ProfileFallback {
    private const val ODIN = "https://hypixel.odtheking.com/get/"
    private const val DEVONIAN = "https://api.docilelm.top/v2/dungeons/"
    private const val DEVONIAN_AGENT = "Mozilla/5.0 (Devonian)"
    private val ELITE = listOf("https://api.elitebot.dev/profile/", "https://api.eliteskyblock.com/profile/")

    /// devonian answers a player it has not fetched lately with "Rate Limited" and no data, and has them a few seconds
    /// later (its own mod asks again every 5 s).
    private const val DEVONIAN_RETRY_MS = 6_000L

    /// A uuid no source could answer for, and until when it is not asked again: the Party Finder tooltip asks again
    /// every frame a lookup is not cached, which would otherwise be several downloads per frame per player.
    private const val RETRY_AFTER_MS = 60_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    suspend fun dungeonStats(uuid: String, name: String): Result<DungeonStats> {
        val id = uuid.replace("-", "").lowercase()
        if ((failedUntil[id] ?: 0L) > System.currentTimeMillis()) return Result.failure(IOException("no stats source answered recently"))
        val errors = mutableListOf<String>()
        suspend fun attempt(source: String, block: suspend () -> DungeonStats): DungeonStats? =
            runCatching { block() }.onFailure { errors += "$source: ${it.message}" }.getOrNull()

        attempt("Odin") { fromOdin(id) }?.let { return Result.success(it) }
        attempt("devonian") { fromDevonian(name) }?.let { return Result.success(it) }
        for (base in ELITE) attempt(base.substringAfter("://").substringBefore('/')) { fromElite(base, id) }?.let { return Result.success(it) }
        failedUntil[id] = System.currentTimeMillis() + RETRY_AFTER_MS
        return Result.failure(IOException(errors.joinToString("; ")))
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

    private suspend fun fromElite(base: String, id: String): DungeonStats {
        val root = fetch("$base$id/selected")
        val dungeons = root.obj("unparsed")?.obj("dungeons") ?: throw IOException("no dungeon data")
        return fromMember(buildJsonObject { put("dungeons", dungeons) }, root.arr("pets")).copy(inventoryKnown = false)
    }

    private suspend fun fromDevonian(name: String): DungeonStats {
        var entry = devonianEntry(name)
        if (entry?.bool("success") != true) {
            delay(DEVONIAN_RETRY_MS)
            entry = devonianEntry(name)
        }
        val d = entry?.takeIf { it.bool("success") == true }?.obj("data")?.takeIf { it.num("cataXP") != null }
            ?: throw IOException(entry?.str("status") ?: "no answer")
        val secrets = d.num("secrets")?.toInt() ?: 0
        val average = d.num("averageSecrets") ?: 0.0
        fun sPlus(key: String) = d.obj(key)?.obj("s_plus")?.entries.orEmpty().mapNotNull { (k, v) ->
            val floor = k.removePrefix("floor_").removeSuffix("_ms").takeIf { k.endsWith("_ms") && it.all(Char::isDigit) }
            floor?.let { f -> (v as? JsonPrimitive)?.doubleOrNull?.let { f to it.toLong() } }
        }.toMap()
        val pets = buildList {
            d.obj("spirit")?.str("tier")?.let { add(DungeonStats.PetSummary("SPIRIT", it)) }
            d.arr("enderDragon")?.forEach { p -> (p as? JsonObject)?.str("tier")?.let { add(DungeonStats.PetSummary("ENDER_DRAGON", it)) } }
            // Golden dragons only come legendary; devonian sends their held item alone.
            d.arr("goldenDragon")?.forEach { _ -> add(DungeonStats.PetSummary("GOLDEN_DRAGON", "LEGENDARY")) }
        }
        return DungeonStats(
            favoriteArrow = "", selectedPower = "", abiphoneContacts = emptyList(), consumedRiftPrism = false,
            bloodMobsKilled = 0,
            dungeons = DungeonStats.DungeonData(
                totalRuns = if (average > 0) Math.round(secrets / average).toInt() else 0,
                secrets = secrets,
                playerClasses = d.obj("roles")?.entries.orEmpty()
                    .mapNotNull { (cls, r) -> (r as? JsonObject)?.num("xp")?.let { cls to it } }.toMap(),
                catacombsExperience = d.num("cataXP") ?: 0.0,
                catacombs = DungeonStats.TierData(emptyMap(), sPlus("personal_best_normal")),
                masterCatacombs = DungeonStats.TierData(emptyMap(), sPlus("personal_best_master"))
            ),
            pets = pets,
            armorData = "", talismanBagData = "",
            reportedMagicalPower = d.num("magical_power")?.toInt()?.takeIf { it > 0 },
            inventoryKnown = false
        )
    }

    /// devonian's answer for one player: its result map is keyed by the name as sent, so the only entry is taken.
    private suspend fun devonianEntry(name: String): JsonObject? = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder(URI.create(DEVONIAN + name.lowercase())).timeout(Duration.ofSeconds(15))
            .header("User-Agent", DEVONIAN_AGENT).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) throw IOException("HTTP ${res.statusCode()}")
        Json.parseToJsonElement(res.body()).jsonObject.obj("result")?.values?.firstOrNull() as? JsonObject
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
            // A pet without a tier is left out: the stats printout looks its rarity up by name.
            pets = pets.orEmpty().mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                DungeonStats.PetSummary(o.str("type") ?: return@mapNotNull null, o.str("tier")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null)
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
