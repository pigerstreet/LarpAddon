package com.github.noamm9.init

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.init.types.ICustomMenu
import com.github.noamm9.utils.catch
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.entity.Entity
import java.lang.reflect.Method

object ModCompatibility {
    val customMenus = mutableListOf<ICustomMenu>()

    @JvmStatic fun isModLoaded(modid: String) = FabricLoader.getInstance().isModLoaded(modid)
    @JvmStatic fun isCustomMenuActive() = customMenus.any(ICustomMenu::isActive)

    fun disableBlockstateCulling() = catch {
        if (! isModLoaded("moreculling")) return@catch
        val main = Class.forName("ca.fxco.moreculling.MoreCulling")
        val config = main.getDeclaredField("CONFIG").get(null)

        val blockStateCulling = config?.javaClass?.getDeclaredField("useBlockStateCulling")
        blockStateCulling?.isAccessible = true
        blockStateCulling?.setBoolean(config, false)
        mc.levelRenderer.allChanged()
    }

    /// fork: EntityCulling mixes its public `Cullable` interface into every entity, and `setTimeout()` on it
    /// marks the entity force-visible for a second - checked before EntityCulling decides to cull it. Glow and
    /// Box3D both depend on an entity actually being extracted for drawing, so highlights that must show
    /// through walls use this to keep their targets drawn. Looked up reflectively, so without EntityCulling
    /// `canKeepVisible` is false and `keepVisible` does nothing.
    private val cullableSetTimeout: Method? by lazy {
        runCatching<Method?> {
            if (! isModLoaded("entityculling")) return@runCatching null
            Class.forName("dev.tr7zw.entityculling.versionless.access.Cullable").getMethod("setTimeout")
        }.getOrNull()
    }

    val canKeepVisible get() = cullableSetTimeout != null

    fun keepVisible(entity: Entity) {
        val method = cullableSetTimeout ?: return
        if (method.declaringClass.isInstance(entity)) catch { method.invoke(entity) }
    }

    const val bobby_chunk = "de.johni0702.minecraft.bobby.FakeChunk"
    val bobbyManagesWorlds by lazy {
        runCatching {
            if (! isModLoaded("bobby")) return@runCatching false
            val bobby = Class.forName("de.johni0702.minecraft.bobby.Bobby").getMethod("getInstance").invoke(null)
            val config = bobby.javaClass.getMethod("getConfig").invoke(bobby)
            config.javaClass.getMethod("isDynamicMultiWorld").invoke(config) as Boolean
        }.getOrDefault(false)
    }
}