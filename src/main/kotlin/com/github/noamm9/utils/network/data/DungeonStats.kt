package com.github.noamm9.utils.network.data

import com.github.noamm9.utils.network.ApiUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DungeonStats(
    @SerialName("favorite_arrow") val favoriteArrow: String,
    @SerialName("selected_power") val selectedPower: String,
    @SerialName("abiphone_contacts") val abiphoneContacts: List<String>,
    @SerialName("consumed_rift_prism") val consumedRiftPrism: Boolean,
    @SerialName("blood_mobs_killed") val bloodMobsKilled: Int,
    val dungeons: DungeonData,
    @SerialName("active_pet") val activePet: PetSummary? = null,
    val pets: List<PetSummary>,
    @SerialName("armor_data") val armorData: String,
    @SerialName("talisman_bag_data") val talismanBagData: String,
    /// fork: Magical Power as a fallback source reports it, when it has no talisman bag to work it out from (devonian's
    /// API). Never in the NoammAddons API's answer.
    @kotlinx.serialization.Transient val reportedMagicalPower: Int? = null,
    /// fork: false when the profile came from a source that never has inventories (EliteBot, devonian), so blank bag and
    /// armour data there means unknown, not the player's inventory API off.
    @kotlinx.serialization.Transient val inventoryKnown: Boolean = true
) {
    val armorInv by lazy { ApiUtils.decodeBase64ItemList(armorData) }
    val magicalPower by lazy { reportedMagicalPower ?: ApiUtils.getMagicalPower(ApiUtils.decodeBase64ItemList(talismanBagData), this) }
    /// fork: whether [magicalPower] is known: the bag's data (inventory API on), or a source that reported it.
    val hasMagicalPower: Boolean get() = reportedMagicalPower != null || talismanBagData.isNotBlank()
    val cataLevel by lazy { ApiUtils.getCatacombsLevel(dungeons.catacombsExperience) }
    val classAverage by lazy { dungeons.playerClasses.values.map(ApiUtils::getCatacombsLevel).average() }
    val secretAverage by lazy { dungeons.secrets.toDouble() / dungeons.totalRuns.toDouble() }

    @Serializable
    data class DungeonData(
        @SerialName("total_runs") val totalRuns: Int,
        val secrets: Int,
        @SerialName("player_classes") val playerClasses: Map<String, Double>,
        @SerialName("catacombs_experience") val catacombsExperience: Double,
        val catacombs: TierData,
        @SerialName("master_catacombs") val masterCatacombs: TierData
    )

    @Serializable
    data class TierData(
        @SerialName("tier_completions") val tierCompletions: Map<String, Int>,
        @SerialName("fastest_time_s_plus") val fastestTimeSPlus: Map<String, Long>
    )

    @Serializable
    data class PetSummary(val type: String, val tier: String)
}