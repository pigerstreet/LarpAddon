package com.github.noamm9

import com.github.noamm9.config.PogObject
import com.github.noamm9.init.ClassGraphInitializer
import com.github.noamm9.utils.network.ApiAuth
import com.github.noamm9.utils.render.ItemRenderer
import gg.essential.universal.UMinecraft
import kotlinx.coroutines.*
import me.owdding.dfu.item.MeowddingItemDfu
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object NoammAddons: ClientModInitializer {
    const val MOD_ID = "@MOD_ID@"
    const val MOD_NAME = "@MOD_NAME@"
    const val MOD_VERSION = "@MOD_VERSION@"
    /// fork: blank, so nothing the mod prints announces itself - including any new upstream use.
    /// Kept as a Component rather than deleted so upstream's `PREFIX.copy().append(..)` call sites
    /// still compile untouched if one of them comes back in a sync.
    val PREFIX: Component = Component.empty()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName(MOD_NAME))

    @JvmField val logger = LoggerFactory.getLogger(MOD_NAME)
    @JvmField val mc = UMinecraft.getMinecraft()
    @JvmField var isLoaded = false

    @JvmField
    var isCheat = run {
        //#if CHEAT
        true
        //#else
        //$false
        //#endif
    }

    val cacheData = PogObject("cacheData", mutableMapOf<String, Any>())

    /// fork: `debugFlags.contains` below adds to this on every call, and callers reach it from more than
    /// one thread - `Event.isCanceled` consults it on every cancellation, and the autoclicker, chat
    /// helpers and puzzle solvers all read flags from coroutines on `Dispatchers.Default`. Concurrent
    /// `add` on a plain `LinkedHashSet` can corrupt its table and leave a later read spinning. The one
    /// reader is `/na debug`'s tab completion, which iterates it, so this wants a set that is safe to
    /// walk while another thread is adding rather than a synchronized wrapper (whose iterator still
    /// throws). The only thing given up is insertion order in the completion list, which was the order
    /// flags happened to first be asked about.
    val availableDebugFlags: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val debugFlags = object: LinkedHashSet<String>() {
        override fun contains(o: String): Boolean {
            availableDebugFlags.add(o)
            return super.contains(o)
        }
    }

    override fun onInitializeClient() {
        PictureInPictureRendererRegistry.register { ItemRenderer(it.bufferSource()) }
        MeowddingItemDfu.load()

        ClassGraphInitializer().initAll()
        ApiAuth.init()

        isLoaded = true
    }
}