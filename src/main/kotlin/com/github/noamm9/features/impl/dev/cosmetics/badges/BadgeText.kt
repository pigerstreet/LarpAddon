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
    private val realNames = java.util.concurrent.ConcurrentHashMap<UUID, String>()
    private val plainText = Regex(""""text":"([^"]*)"""")

    /// The Minecraft username of a badge holder, from the Mojang lookup Cosmetics already does.
    fun registerName(uuid: UUID, username: String) {
        realNames[uuid] = username
        rebuildNames()
    }

    private fun rebuildNames() {
        val out = HashMap<String, Component>()
        lastPeople.forEach { (uuid, data) ->
            val prefix = prefixes[uuid] ?: return@forEach
            realNames[uuid]?.takeIf { it.length >= 3 }?.let { out[it] = prefix }
            val plain = plainText.findAll(data.name).joinToString("") { it.groupValues[1] }.trim()
            if (plain.length >= 3) out[plain] = prefix
        }
        namePrefixes = out
    }

    @JvmStatic
    fun decorateTab(name: Component, uuid: UUID): Component {
        if (! Cosmetics.enabled || ! Cosmetics.showBadges.value) return name
        val text = name.string
        val own = ownPrefix()
        val prefix = (if (uuid == mc.user.profileId) own else null)
            ?: prefixes[uuid]
            ?: own?.takeIf { text.contains(mc.user.name) }
            ?: namePrefixes.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }?.value
            ?: return name
        // badges go after the name in tab; the prefix already ends in a space, so a leading one separates it
        return Component.empty().append(name).append(Component.literal(" ")).append(prefix)
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
        rebuildNames()
    }

    @JvmStatic
    fun decorate(name: Component, uuid: UUID): Component {
        if (! Cosmetics.enabled || ! Cosmetics.showBadges.value) return name
        val own = if (uuid == mc.user.profileId) ownPrefix() else null
        val prefix = own ?: prefixes[uuid] ?: return name
        return Component.empty().append(prefix).append(name)
    }
}