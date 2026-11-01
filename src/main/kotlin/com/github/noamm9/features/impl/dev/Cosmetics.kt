package com.github.noamm9.features.impl.dev

import com.github.noamm9.NoammAddons
import com.github.noamm9.config.types.ButtonSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.dev.cosmetics.CosmeticData
import com.github.noamm9.features.impl.dev.cosmetics.badges.BadgeManager
import com.github.noamm9.features.impl.dev.cosmetics.badges.BadgeText
import com.github.noamm9.features.impl.dev.text.TextReplacer
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.utils.*
import com.github.noamm9.utils.MathUtils.vec
import com.github.noamm9.utils.network.*
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.Avatar
import java.util.*
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

object Cosmetics: Feature(toggled = true) {
    @JvmStatic val customNames by ToggleSetting("Show Custom Names", true)
    @JvmStatic val showBadges by ToggleSetting("Show Badges", true)
    @JvmStatic val loreNames by ToggleSetting("Show Name in Lore", true).showIf { customNames.value }

    val customSizes by ToggleSetting("Show Custom Sizes", true)
    val forceNormalOtherPlayerSizes by ToggleSetting("Force Normal Other Player Sizes", true)
        .withDescription("Ignore cosmetic size changes on other players. Their name tags, badges, and halo offsets are unchanged.")
    val showHalo by ToggleSetting("Show Halos", true)
    val showDragonWings by ToggleSetting("Show Dragon Wings", true)
    val reload by ButtonSetting("Reload Cosmetics") {
        if (System.currentTimeMillis() - lastReload >= 15_000) init()
        /// fork: the cooldown one line up is 15 seconds, this counted down from 150 - the button told
        /// you to wait another two and a half minutes when it was about to let you through. `formatTime`
        /// is also empty under a second, which left "wait another  before reloading" on the last one.
        else NotificationManager.push("Cosmetics", "Please wait another ${NumbersUtils.formatTime(15_000 - (System.currentTimeMillis() - lastReload)).ifEmpty { "1s" }} before reloading again.")
    }

    private lateinit var cosmeticPeople: Map<UUID, CosmeticData>
    private var lastReload = System.currentTimeMillis()
    /// The uuids that have a custom name on the site, and the Minecraft name of each one we know about.
    private var namedUuids: List<UUID> = emptyList()
    private val names = ConcurrentHashMap<UUID, String>()
    private var watchingTab = false
    private var tabTicks = 0
    private var pendingPublish = false
    private var lastPublish = 0L
    private var hooked = false
    /// fork: the name lookup is a network call and can be down while the client runs; retried with backoff.
    private var lastBatchAttempt = System.currentTimeMillis()
    private var batchBackoffMs = 60_000L
    private var batchRunning = false

    override fun init() {
        scope.launch(Dispatchers.IO) {
            lastReload = System.currentTimeMillis()
            NoammAddons.logger.info("fetching cosmeticPeople")

            launch { BadgeManager.load() }
            WebUtils.getAs<Map<String, CosmeticData>>("${NoammAPI.URL}/cosmeticPeople.json").onSuccess { data ->
                TextReplacer.ready = false
                cosmeticPeople = data.mapKeys { UUID.fromString(it.key) }
                BadgeText.init(cosmeticPeople)

                val uuids = cosmeticPeople.filter { it.value.hasCustomName }.map { it.key }
                namedUuids = uuids
                names.clear()
                /// fork: a failed name lookup must not take every custom name with it - getOrThrow threw out of
                /// this block, so TextReplacer.init below never ran and every name vanished while the lookup
                /// service was down (2026-11-01: mowojang.matdoes.dev answered 502, the cache still had most).
                val mojangData = ProfileUtils.getMojangBatched(uuids).getOrNull().orEmpty()
                for (mojang in mojangData) names[Uuid.parseHex(mojang.uuid.remove("-")).toJavaUuid()] = mojang.name

                /// fork: whoever the client already has a profile for needs no lookup at all, and those are the
                /// players whose names are actually drawn. Filled from the tab list on the client thread - at a cold
                /// start there is no connection yet, and 261 of the site's 464 custom names have no cached UUID
                /// (2026-11-01). The tick below keeps filling as more players appear.
                NoammAddons.mc.execute {
                    fillFromTabList()
                    publishNames()
                }
                NoammAddons.logger.info("mojangData: ${mojangData.size}, uuids: ${uuids.size}")
            }.onFailure { cause ->
                NoammAddons.logger.error("Failed to load cosmetic people", cause)
                ChatUtils.modMessage("&cFailed to load cosmetic people: ${cause.message}")
            }.onSuccess {
                NoammAddons.logger.info("loaded all ${cosmeticPeople.size} cosmetic people")
            }
        }

        // A player who was not in the tab list when the site's list arrived still has to get their name, so keep
        // filling while anyone is missing. Every 20 ticks, and a publish is held to once every 3 s: republishing
        // rebuilds the replacer and the badge name map, and doing that per player is a frame-time cost nobody needs
        // (2026-11-01, "make sure these changes don't cause stutters"). Registered once: "Reload Cosmetics" calls
        // init() again, and a second set of listeners would double every scan and every publish.
        if (! hooked) {
            hooked = true
            ClientPlayConnectionEvents.JOIN.register { _, _, _ -> watchingTab = true; tabTicks = 0 }
            ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> watchingTab = false }
            ClientTickEvents.END_CLIENT_TICK.register { _ ->
                if (! watchingTab) return@register
                if (++tabTicks < 20) return@register
                tabTicks = 0
                if (names.size >= namedUuids.size) return@register
                if (fillFromTabList()) pendingPublish = true
                val now = System.currentTimeMillis()
                if (pendingPublish && now - lastPublish >= 3000) {
                    pendingPublish = false
                    publishNames()
                }
                /// fork: the batch lookup happens once at init, so a service outage while the client runs left
                /// the names missing until a restart ("custom names active: 8 of 464", 2026-11-01). Retry it with
                /// a backoff (a minute, doubling to ten while it answers nothing), so the names return by
                /// themselves when the service does.
                if (! batchRunning && names.size < namedUuids.size && now - lastBatchAttempt >= batchBackoffMs) {
                    batchRunning = true
                    lastBatchAttempt = now
                    val uuids = namedUuids
                    scope.launch(Dispatchers.IO) {
                        val data = ProfileUtils.getMojangBatched(uuids).getOrNull().orEmpty()
                        NoammAddons.mc.execute {
                            batchRunning = false
                            /// fork: `data` always holds the cached hits, so "non-empty" meant nothing: one name
                            /// that never resolves refetched and republished every minute. Only a new name resets it.
                            val before = names.size
                            for (mojang in data) names[Uuid.parseHex(mojang.uuid.remove("-")).toJavaUuid()] = mojang.name
                            val learned = names.size > before
                            batchBackoffMs = if (learned) 60_000L else (batchBackoffMs * 2).coerceAtMost(600_000L)
                            if (learned || fillFromTabList()) publishNames()
                        }
                    }
                }
            }
        }
    }

    /// Adds every player in the tab list whose name is not known yet. Client thread only.
    private fun fillFromTabList(): Boolean {
        val connection = NoammAddons.mc.connection ?: return false
        var added = false
        for (uuid in namedUuids) {
            if (names.containsKey(uuid)) continue
            val name = connection.getPlayerInfo(uuid)?.profile?.name ?: continue
            names[uuid] = name
            added = true
        }
        return added
    }

    /// Rebuilds the replacer from every name known so far, and says how many that is. Client thread only.
    private fun publishNames() {
        if (! ::cosmeticPeople.isInitialized) return
        lastPublish = System.currentTimeMillis()
        val custom = HashMap<String, String>(names.size * 2)
        val badgeNames = HashMap<UUID, String>(names.size * 2)
        for ((uuid, mcName) in names) {
            val data = cosmeticPeople[uuid] ?: continue
            if (! data.hasCustomName) continue
            custom[mcName] = data.name
            badgeNames[uuid] = mcName
        }
        /// fork (perf): one rebuild for the batch, not one per name - see BadgeText.registerNames.
        BadgeText.registerNames(badgeNames)
        TextReplacer.init(custom)
        /// fork: the one line that says whether a missing name is "no mapping" or "mapping but not drawn".
        NoammAddons.logger.info("custom names active: ${custom.size} of ${namedUuids.size} players with one")
    }

    @JvmStatic
    fun extractRenderStateHook(avatar: Avatar, state: AvatarRenderState) {
        if (! enabled) return
        if (avatar !is AbstractClientPlayer) return
        state.setData(GAME_PROFILE_KEY, avatar.gameProfile)
        state.setData(SNEAKING_KEY, avatar.isCrouching)
    }

    @JvmStatic
    fun scaleHook(state: AvatarRenderState, poseStack: PoseStack) {
        val gameProfile = state.getData(GAME_PROFILE_KEY) ?: return
        val data = cosmeticDataFor(gameProfile.id) ?: return
        val forceNormalSize = forceNormalOtherPlayerSizes.value && gameProfile.id != NoammAddons.mc.player?.gameProfile?.id

        if (!forceNormalSize && customSizes.value && data.hasCustomSize) {
            if (data.sizeY < 0) poseStack.translate(0f, data.sizeY * 2f, 0f)
            poseStack.scale(data.sizeX, data.sizeY, data.sizeZ)
        }

        state.nameTagAttachment?.let { pos ->
            var scaleY = 1f
            var offset = 0f

            if (!forceNormalSize && customSizes.value && data.hasCustomSize) scaleY = abs(data.sizeY)
            if (showHalo.value && data.hasHalo) offset += 0.15f

            state.nameTagAttachment = vec(pos.x, (pos.y + 0.15) * scaleY + offset, pos.z)
        }
    }

    fun cosmeticDataFor(uuid: UUID) = if (::cosmeticPeople.isInitialized) cosmeticPeople[uuid] else null
    val GAME_PROFILE_KEY = RenderStateDataKey.create<GameProfile> { "${NoammAddons.MOD_ID}:game_profile" }
    val SNEAKING_KEY = RenderStateDataKey.create<Boolean> { "${NoammAddons.MOD_ID}:sneaking" }
}
