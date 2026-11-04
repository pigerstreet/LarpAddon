package com.github.noamm9.features.impl.floor7.terminals

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.floor

/**
 * fork: osu!'s own skin textures (see the CREDITS file in the same asset directory) drawn over the terminal solver
 * instead of hand-built rings. Everything here is draw-only: it reads no solver state and writes none.
 *
 * Skins live in `assets/noammaddons/textures/gui/osu/<skin>/`; a file a skin does not ship falls back to `default/`.
 * The quads use an explicit linear sampler, because the GUI pipeline samples a plain texture nearest-neighbour and a
 * 256px osu circle drawn into a 16px slot would alias badly. Colour is per-vertex, so the caller's alpha fades work.
 */
object OsuSkin {
    const val DEFAULT = "default"

    /** Dropdown labels; index is what the setting stores, and the asset directory is the lowercase name. */
    val names = listOf("Default", "Rafis")

    private const val DIR = "textures/gui/osu"

    private val linear by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
    private val paths = HashMap<String, Identifier>()

    private fun skinDir(skin: Int) = names.getOrElse(skin) { names.first() }.lowercase()

    private fun path(skin: String, name: String) = Identifier.fromNamespaceAndPath(NoammAddons.MOD_ID, "$DIR/$skin/$name.png")

    /** The skin's own file when it ships one, else the default's. Cached: the answer cannot change while the game runs. */
    private fun texture(skin: Int, name: String): Identifier {
        val dir = skinDir(skin)
        return paths.getOrPut("$dir/$name") {
            val specific = path(dir, name)
            if (dir == DEFAULT || mc.resourceManager.getResourceStack(specific).isEmpty()) path(DEFAULT, name) else specific
        }
    }

    private fun view(texture: Identifier): GpuTextureView? = mc.textureManager.getTexture(texture).getTextureView()

    /** The whole texture stretched into a [width] x [height] box centred on [cx],[cy], tinted by [color] (alpha honoured). */
    fun GuiGraphicsExtractor.osuSprite(texture: Identifier, cx: Float, cy: Float, width: Float, height: Float, color: Color) {
        if (width <= 0f || height <= 0f || color.alpha <= 0) return
        val textureView = view(texture) ?: return
        val rgb = color.rgb
        val pose = Matrix3x2f(pose())
        val x0 = cx - width / 2f
        val y0 = cy - height / 2f
        val x1 = x0 + width
        val y1 = y0 + height

        guiRenderState.addGuiElement(object: GuiElementRenderState {
            override fun buildVertices(buffer: VertexConsumer) {
                buffer.addVertexWith2DPose(pose, x0, y0).setUv(0f, 0f).setColor(rgb)
                buffer.addVertexWith2DPose(pose, x0, y1).setUv(0f, 1f).setColor(rgb)
                buffer.addVertexWith2DPose(pose, x1, y1).setUv(1f, 1f).setColor(rgb)
                buffer.addVertexWith2DPose(pose, x1, y0).setUv(1f, 0f).setColor(rgb)
            }

            override fun pipeline() = RenderPipelines.GUI_TEXTURED
            override fun textureSetup() = TextureSetup.singleTexture(textureView, linear)
            override fun scissorArea() = null
            override fun bounds() = ScreenRectangle(
                floor(x0).toInt() - 1, floor(y0).toInt() - 1,
                ceil(width).toInt() + 2, ceil(height).toInt() + 2
            ).transformMaxBounds(pose)
        })
    }

    /** A hit circle: the tinted disc plus the skin's white rim overlay on top. */
    fun GuiGraphicsExtractor.osuHitCircle(skin: Int, cx: Float, cy: Float, r: Float, fill: Color) {
        osuSprite(texture(skin, "hitcircle"), cx, cy, r * 2f, r * 2f, fill)
        osuSprite(texture(skin, "hitcircleoverlay"), cx, cy, r * 2f, r * 2f, Color.WHITE)
    }

    /** The skin's approach ring at radius [r]; it closes in as the value shrinks. */
    fun GuiGraphicsExtractor.osuApproach(skin: Int, cx: Float, cy: Float, r: Float, color: Color) {
        osuSprite(texture(skin, "approachcircle"), cx, cy, r * 2f, r * 2f, color)
    }

    /** The hit flash osu! plays on the circle when it is clicked. */
    fun GuiGraphicsExtractor.osuLighting(skin: Int, cx: Float, cy: Float, r: Float, alpha: Int) {
        osuSprite(texture(skin, "lighting"), cx, cy, r * 4f, r * 4f, Color.WHITE.withAlpha(alpha))
    }

    /** The "300" judgement, sized by its height; its own aspect is kept. A skin that ships a 1x1 file hides it, as osu does. */
    fun GuiGraphicsExtractor.osuJudgement(skin: Int, cx: Float, cy: Float, height: Float, alpha: Int) {
        val texture = texture(skin, "hit300")
        val textureView = view(texture)
        val ratio = if (textureView == null || textureView.getHeight(0) <= 0) 1f
        else textureView.getWidth(0).toFloat() / textureView.getHeight(0)
        osuSprite(texture, cx, cy, height * ratio, height, Color.WHITE.withAlpha(alpha))
    }

    /** osu!'s cursor, [size] across its full texture (the visible circle is a fraction of that, per skin). */
    fun GuiGraphicsExtractor.osuCursor(skin: Int, x: Float, y: Float, size: Float = 17f) {
        osuSprite(texture(skin, "cursor"), x, y, size, size, Color.WHITE.withAlpha(235))
    }

    /** One stamp of osu!'s cursor trail. */
    fun GuiGraphicsExtractor.osuTrail(skin: Int, x: Float, y: Float, size: Float, alpha: Int) {
        osuSprite(texture(skin, "cursortrail"), x, y, size, size, Color.WHITE.withAlpha(alpha))
    }
}
