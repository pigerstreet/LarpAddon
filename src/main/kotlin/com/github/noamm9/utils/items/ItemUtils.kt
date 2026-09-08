package com.github.noamm9.utils.items

import com.github.noamm9.utils.*
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.NumbersUtils.romanToDecimal
import com.github.noamm9.utils.items.ItemRarity.Companion.PET_PATTERN
import com.github.noamm9.utils.items.ItemRarity.Companion.RARITY_PATTERN
import com.github.noamm9.utils.items.ItemRarity.Companion.rarityCache
import com.github.noamm9.utils.network.data.DungeonStats
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import kotlin.jvm.optionals.getOrNull

object ItemUtils {
    val ItemStack.customData get() = getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    val ItemStack.lore get() = getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines().map { it.formattedText }
    val ItemStack.itemUUID get() = customData.getString("uuid").getOrNull() ?: ""
    val ItemStack.skyblockId: String
        get() {
            if (isEmpty) return ""

            /// fork: upstream hoisted this display-name read back to the top of the getter, and once
            /// the shard block below is folded it has no reader left — the ENCHANTED_BOOK branch
            /// builds its own from the enchant line, and the shard branch reads it lazily, only for
            /// the two ids that can reach it. Left here it would build and flatten a display-name
            /// component for every item that HAS an id, which is the exact cost the note on that
            /// branch is about. Removed rather than shadowed.
            val customData = customData
            var sbItemID: String? = null

            if (customData.contains("id")) sbItemID = customData.getString("id").getOrNull()?.replace(":", "-")

            if (sbItemID == "PET") {
                val petInfoRaw = customData.getString("petInfo").getOrNull()?.takeIf { it.isNotEmpty() } ?: return sbItemID
                val petInfo = JsonUtils.json.decodeFromString<DungeonStats.PetSummary>(petInfoRaw)
                return "PET-${petInfo.type}-${petInfo.tier}"
            }

            if (sbItemID == "ENCHANTED_BOOK") {
                customData.getCompound("enchantments").getOrNull()?.let { enchantments ->
                    val enchantId = enchantments.keySet().singleOrNull()
                    val level = enchantId?.let { enchantments.getIntOr(it, 0) } ?: 0
                    if (enchantId != null && level > 0) return "ENCHANTMENT_${enchantId.uppercase()}_$level"
                }

                val lore = lore
                val bookName = lore[0].takeIf { it != "§8Combinable in Anvil" } ?: lore[2]
                val enchantName = bookName.substringBeforeLast(" ")
                val levelStr = bookName.substringAfterLast(" ").removeFormatting()

                val name = enchantName.removeFormatting().uppercase().replace(" ", "_")
                val level = levelStr.toIntOrNull() ?: levelStr.romanToDecimal()
                val isUltimate = enchantName.startsWithOneOf("§9§d§l", "§d§l", "§7§l") && ! name.contains("ULTIMATE_")

                return "ENCHANTMENT_${if (isUltimate) "ULTIMATE_" else ""}${name}_$level"
            }

            if (sbItemID.equalsOneOf("RUNE", "UNIQUE_RUNE")) {
                val runes = customData.getCompound("runes").getOrNull() ?: return ""
                val runeId = runes.keySet().singleOrNull() ?: return ""
                val level = runes.getIntOr(runeId, 0)
                if (level <= 0) return ""
                return "RUNE-${runeId.uppercase()}-$level"
            }

            if (sbItemID == "POTION") {
                val potion = customData.getString("potion").getOrNull()?.takeIf(String::isNotEmpty) ?: return ""
                val level = customData.getIntOr("potion_level", 0)
                if (level <= 0) return ""

                return "POTION-${potion.uppercase()}-$level${if (customData.getBooleanOr("enhanced", false)) "-ENHANCED" else ""}"
            }

            if (sbItemID == "ATTRIBUTE_SHARD" || sbItemID == null) {
                /// fork: this read sat at the top of the getter, so every item built a display-name
                /// component and flattened it - and threw the result away. It is only ever read here, on
                /// the branch for an item carrying no id in its nbt at all, and everything Hypixel hands
                /// out carries one. `skyblockId` is asked per hovered tooltip frame and per slot by
                /// several features, and it already deep copies the tag, so this was the other half of
                /// the cost for the items that never reach this branch.
                ///
                /// fork: upstream has now converged on this — it classifies `ATTRIBUTE_SHARD` on the id
                /// alone, falls back to the name or the last lore line only when there is no id at all,
                /// and its `isShard` carries the same lore test this fork added. What upstream has not
                /// taken is the placement: it reads `name` and builds the whole lore list at the top of
                /// the getter, for every item including ones that carry an id. Both halves are kept
                /// here — upstream's predicate, called from inside the branch — because the `||`
                /// short-circuits, so an `ATTRIBUTE_SHARD` never pays for either read, and a null id
                /// pays only for the name and the lore it actually needs.
                val name = hoverName.unformattedText

                if (sbItemID == "ATTRIBUTE_SHARD" || isShard(name, lore)) return getShardIdFromName(name)
            }

            return sbItemID.orEmpty()
        }

    fun isShard(displayName: String, lore: List<String>) = " Shard " in displayName || displayName.endsWith(" Shard") || lore.lastOrNull()?.removeFormatting()?.substringBefore('(')?.trimEnd()?.endsWith(" SHARD") == true
    fun getShardIdFromName(displayName: String): String {
        val name = displayName.removeFormatting().uppercase().remove(shardCountSuffix).removeSuffix(" SHARD").replace(" ", "_")
        return shardIdOverrides[name] ?: "SHARD_$name"
    }

    fun getSkullTexture(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val properties = profile.partialProfile().properties
        return properties["textures"].firstOrNull()?.value
    }

    fun ItemStack.hasGlint() = get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true

    fun getRarity(item: ItemStack?): ItemRarity {
        item ?: return ItemRarity.NONE
        if (item.isEmpty) return ItemRarity.NONE
        rarityCache[item]?.let { return it }

        val rarity = run {
            val lore = item.lore.takeUnless(List<*>::isEmpty) ?: return@run ItemRarity.NONE

            for (i in lore.indices) {
                val line = lore[lore.lastIndex - i]
                val rarityName = RARITY_PATTERN.find(line)?.groups?.get("rarity")?.value?.removeFormatting()?.substringAfter("SHINY ")
                ItemRarity.entries.find { it.loreName == rarityName }?.let { return@run it }
            }

            PET_PATTERN.find(item.hoverName.formattedText)?.groupValues?.getOrNull(1)?.let(ItemRarity::byBaseColor) ?: ItemRarity.NONE
        }

        rarityCache[item] = rarity
        return rarity
    }

    private val shardCountSuffix = Regex(" X\\d+$")
    private val shardIdOverrides = mapOf(
        "BOGGED" to "SHARD_SEA_ARCHER",
        "LOTUSFISH" to "SHARD_LOTUS_FISH",
        "INKLING" to "SHARD_NIGHT_SQUID",
        "LOCH_EMPEROR" to "SHARD_SEA_EMPEROR",
        "INFERNO_DEMONLORD" to "SHARD_BURNINGSOUL",
        "END_STONE_PROTECTOR" to "SHARD_ENDSTONE_PROTECTOR",
        "CINDERBAT" to "SHARD_CINDER_BAT",
        "BEETLE" to "SHARD_CROPEETLE",
        "ABYSSAL_LANTERNFISH" to "SHARD_ABYSSAL_LANTERN",
        "SEASHINE" to "SHARD_SEA_SHINE",
        "WITHER_SPECTRE" to "SHARD_WITHER_SPECTER",
        "FIELD_MOUSE" to "SHARD_PEST",
        "ZEALOT_BRUISER" to "SHARD_BRUISER",
        "STRIDERSURFER" to "SHARD_STRIDER_SURFER",
        "EARTHWORM" to "SHARD_TERMITE",
        "FLIPFLOPPER" to "SHARD_FLIP_FLOPPER"
    )
}