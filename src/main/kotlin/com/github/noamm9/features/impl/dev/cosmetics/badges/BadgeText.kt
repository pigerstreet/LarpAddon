package com.github.noamm9.features.impl.dev.cosmetics.badges

import com.github.noamm9.NoammAddons.MOD_ID
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.features.impl.dev.Cosmetics
import com.github.noamm9.features.impl.dev.cosmetics.CosmeticData
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import java.util.*

object BadgeText {
    private val font = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "badges"))
    @Volatile private var prefixes = emptyMap<UUID, Component>()
    @Volatile private var lastPeople = emptyMap<UUID, CosmeticData>()

    fun init(people: Map<UUID, CosmeticData>) {
        lastPeople = people
        rebuild()
    }

    /// fork: your own name gets the local badges chosen in Cosmetics, built once per setting state.
    private var ownKey = - 1
    private var ownPrefix: Component? = null

    private fun ownPrefix(): Component? {
        val ids = listOfNotNull(
            "SUS".takeIf { Cosmetics.ownBadgeSus.value },
            "DEV".takeIf { Cosmetics.ownBadgeDev.value },
            "CHAIR".takeIf { Cosmetics.ownBadgeChair.value }
        )
        val glyphs = ids.mapNotNull { BadgeManager.charOf(it) }
        val key = glyphs.fold(glyphs.size) { acc, c -> acc * 31 + c.code }
        if (key != ownKey) {
            ownKey = key
            ownPrefix = if (glyphs.isEmpty()) null else buildPrefix(glyphs)
        }
        return ownPrefix
    }

    private fun buildPrefix(glyphs: List<Char>): Component {
        val prefix = Component.empty()
        glyphs.forEachIndexed { index, glyph ->
            if (index > 0) prefix.append(Component.literal(BadgeManager.SEPARATOR.toString()).withStyle { it.withFont(font) })
            prefix.append(Component.literal(glyph.toString()).withStyle {
                it.withFont(font).withColor(0xFFFFFF).withBold(false).withItalic(false).withShadowColor(0)
            })
        }
        prefix.append(Component.literal(" "))
        return prefix
    }

    /// fork: Hypixel's tab list entries are not the players' own profiles (the id does not match), so tab lines are
    /// matched by the player name inside the displayed text instead.
    @Volatile private var namePrefixes = emptyMap<String, Component>()

    @JvmStatic
    fun decorateTab(name: Component, uuid: UUID): Component {
        if (! Cosmetics.enabled || ! Cosmetics.showBadges.value) return name
        val byId = decorate(name, uuid)
        if (byId !== name) return byId
        val text = name.string
        val own = ownPrefix()
        if (own != null && text.contains(mc.user.name)) return Component.empty().append(own).append(name)
        for (token in text.split(' ', '[', ']', ':', '(', ')')) {
            val prefix = namePrefixes[token.lowercase()] ?: continue
            return Component.empty().append(prefix).append(name)
        }
        return name
    }

    fun rebuild() {
        prefixes = lastPeople.mapNotNull { (uuid, data) ->
            val glyphs = data.badges.mapNotNull { BadgeManager.charOf(it) }.ifEmpty { return@mapNotNull null }

            val prefix = Component.empty()
            glyphs.forEachIndexed { index, glyph ->
                if (index > 0) prefix.append(Component.literal(BadgeManager.SEPARATOR.toString()).withStyle { it.withFont(font) })
                prefix.append(Component.literal(glyph.toString()).withStyle {
                    it.withFont(font).withColor(0xFFFFFF).withBold(false).withItalic(false).withShadowColor(0)
                })
            }
            prefix.append(Component.literal(" "))

            uuid to prefix
        }.toMap()
        namePrefixes = HashMap<String, Component>().also { out ->
            lastPeople.forEach { (uuid, data) ->
                val prefix = prefixes[uuid] ?: return@forEach
                if (data.name.isNotBlank()) out[data.name.lowercase()] = prefix
            }
        }
    }

    @JvmStatic
    fun decorate(name: Component, uuid: UUID): Component {
        if (! Cosmetics.enabled || ! Cosmetics.showBadges.value) return name
        val own = if (uuid == mc.user.profileId) ownPrefix() else null
        val prefix = own ?: prefixes[uuid] ?: return name
        return Component.empty().append(prefix).append(name)
    }
}