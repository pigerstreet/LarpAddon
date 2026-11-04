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

    /// fork: Hypixel's tab list entries are not the players' own profiles (the id does not match), so tab lines are
    /// matched by the player name inside the displayed text instead.
    @Volatile private var namePrefixes = emptyMap<String, Component>()
    /// fork (perf): the UUID lookup above misses for tab entries, so decorateTab linearly scanned namePrefixes for
    /// every entry every frame. Cache the key that matched per display name ("" = no match) until the map is rebuilt.
    private val resolvedNameKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val realNames = java.util.concurrent.ConcurrentHashMap<UUID, String>()
    private val plainText = Regex(""""text":"([^"]*)"""")

    /// The Minecraft username of a badge holder, from the Mojang lookup Cosmetics already does.
    fun registerName(uuid: UUID, username: String) = registerNames(mapOf(uuid to username))

    /// fork (perf): [rebuildNames] walks every cosmetic person (493 of them) and runs a regex over their stored
    /// name, and it also clears the per-frame tab cache - so registering usernames one at a time was quadratic and
    /// left the tab list to rescan `namePrefixes` for every row on the frames that followed (2026-11-01). Batch
    /// the writes and rebuild once.
    fun registerNames(usernames: Map<UUID, String>) {
        if (usernames.isEmpty()) return
        realNames.putAll(usernames)
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
        resolvedNameKeys.clear()
        namePrefixes = out
    }

    // fork: keyed on every tab line ever drawn, so it grew all session; a refill is one scan per line
    private fun resolvedNameKey(text: String) = resolvedNameKeys.also { if (it.size > 1024) it.clear() }.getOrPut(text) {
        namePrefixes.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }?.key ?: ""
    }

    @JvmStatic
    fun decorateTab(name: Component, uuid: UUID): Component {
        if (! Cosmetics.enabled || ! Cosmetics.showBadges.value) return name
        val text = name.string
        val prefix = prefixes[uuid] ?: namePrefixes[resolvedNameKey(text)] ?: return name
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
        val prefix = prefixes[uuid] ?: return name
        return Component.empty().append(prefix).append(name)
    }
}