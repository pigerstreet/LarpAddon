package com.github.noamm9.features.impl.floor7.terminals

import com.github.noamm9.NoammAddons
import com.github.noamm9.commands.CommandBuilder
import com.github.noamm9.commands.NaCommand
import com.github.noamm9.config.types.*
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.impl.*
import com.github.noamm9.features.impl.floor7.terminals.sim.*
import com.github.noamm9.init.types.ICommandProvider
import com.github.noamm9.init.types.ICustomMenu
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.*
import com.github.noamm9.utils.ColorUtils.isVisable
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawFloatingRect
import com.github.noamm9.utils.render.Render2D.drawCapsule
import com.github.noamm9.utils.render.Render2D.drawRainbowBorder
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawRing
import com.github.noamm9.utils.render.Render2D.drawString
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import gg.essential.universal.UGraphics
import gg.essential.universal.UResolution
import gg.essential.universal.USound
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.abs

object TerminalSolver: Feature("Renders solutions for Floor 7 terminals."), ICustomMenu, ICommandProvider {
    val fcDelay by SliderSetting("FC delay, (In server ticks)", 7, 0, 8, 1).hideIf { true }

    private val redGreenTerm by ToggleSetting("Red-Green", true).section("Toggles")
    private val colorsTerm by ToggleSetting("Colors", true)
    private val startsWithTerm by ToggleSetting("Start-With", true)
    private val rubixTerm by ToggleSetting("Rubix", true)
    private val numbersTerm by ToggleSetting("Numbers", true)
    private val melodyTerm by ToggleSetting("Melody", true)
    private val _simbtn by ButtonSetting("Terminal Simulator") {
        mc.execute { mc.gui.screen()?.onClose() }
        GuiUtils.setScreen(TerminalSimSelectorScreen())
    }.withDescription("Opens the Terminal Simulator. can also be open via /na termsim")
    val simAutoNext by ToggleSetting("Simulator Auto Next").hidden()

    private val soundsEnabled by ToggleSetting("Terminal Sounds", true).section("Sounds")
    private val clickSound = createSoundSettings("Click Sound", SoundEvents.NOTE_BLOCK_PLING.value()) { soundsEnabled.value }
    /// fork: osu!'s classic normal-hitnormal (ppy/osu-resources, CC BY-NC 4.0), local playback only like the Click Sound
    private val osuHitsound by ToggleSetting("Osu! Hitsound", true).withDescription("With Slot Style Osu!, play osu!'s hit sound instead of the Click Sound.").showIf { soundsEnabled.value }
    private val osuHitsoundVolume by SliderSetting("Osu! Hitsound Volume", 0.6f, 0f, 1f, 0.05f).showIf { soundsEnabled.value && osuHitsound.value }
    private val osuHitSoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(NoammAddons.MOD_ID, "osu_hit"))

    private val scale by SliderSetting("Custom Menu's Scale", 1f, 0.1f, 2f, 0.01f).section("Settings - UI")
    /// fork: puts the cursor in the middle of the screen whenever a terminal opens.
    val centerCursor by ToggleSetting("Center Cursor", true).withDescription("Moves your cursor to the middle of the screen when a terminal opens.")
    private val slotGap by SliderSetting("Slot Gap", 0f, 0f, 8f, 0.5f, "px")
    private val padding by SliderSetting("Padding", 2f, 0f, 12f, 0.5f, "px")
    private val slotStyle by DropdownSetting("Slot Style", 0, listOf("Rect", "Bordered-Rect", "Button", "Osu!"))
    val breakTimeout by SliderSetting("Break Timeout", 500, 300, 800, 50, "ms").withDescription("How long should the solver wait for a click to be confirmed by hypixel")
    private val backgroundColor by ColorSetting("Background Color", Color(0, 0, 0, 100))
    private val borderColor by ColorSetting("Border Color", Color(255, 255, 255))
    private val rainbowBorder by ToggleSetting("Rainbow Border").withDescription("Animate a rainbow gradient around the terminal menu. Opacity follows Border Color.")
    private val rainbowBorderSpeed by SliderSetting("Rainbow Border Speed", 1.0, 0.1, 3.0, 0.1).showIf { rainbowBorder.value }
    private val titleColor by ColorSetting("Title Text Color", Color.WHITE)
    private val overlayTextColor by ColorSetting("Overlay Text Color", Color.WHITE)
    private val solutionColor by ColorSetting("Solution Color", Color(0, 255, 0, 130))

    val positiveColor by ColorSetting("Rubix: Positive (+)", Color(0, 114, 255, 130)).section("Rubix").showIf { rubixTerm.value }
    val negativeColor by ColorSetting("Rubix: Negative (-)", Color(205, 0, 0, 130)).showIf { rubixTerm.value }

    val showNumbers by ToggleSetting("Show Numbers").section("Numbers").showIf { numbersTerm.value }
    val firstColor by ColorSetting("1st Click Color", Color(0, 255, 0, 130)).showIf { numbersTerm.value }
    val secondColor by ColorSetting("2nd Click Color", Color(255, 255, 120, 130)).showIf { numbersTerm.value }
    val thirdColor by ColorSetting("3rd Click Color", Color(200, 0, 0, 130)).showIf { numbersTerm.value }

    val columnColor by ColorSetting("Melody: Column", Color(255, 0, 255, 130)).section("Melody").showIf { melodyTerm.value }
    val indicatorColor by ColorSetting("Melody: Indicator", Color(255, 116, 0, 130)).showIf { melodyTerm.value }
    val wrongColor by ColorSetting("Melody: Wrong", Color(255, 0, 0, 130)).showIf { melodyTerm.value }

    private val p3sim get() = mc.connection?.serverData?.ip?.contains("p3sim", ignoreCase = true) == true
    private var lastDropTime = System.currentTimeMillis()

    private var cachedMinCol: Int? = null
    private var cachedMinRow: Int? = null
    private var hoveredSlot: Int? = null

    /// fork: "Osu!" slot style. Draw-only state in fixed arrays so a frame allocates nothing; it never feeds hoveredSlot or a click.
    private const val OSU_HIT_MS = 250L
    private const val OSU_TRAIL = 8
    private val osuHitColor = Color(102, 204, 255)
    private val osuCursorColor = Color(255, 210, 120, 230)
    private val osuShadow = Color(0, 0, 0, 90)
    private val osuWas = BooleanArray(54)
    private val osuIs = BooleanArray(54)
    private val osuHitAt = LongArray(54)
    private val osuTrail = FloatArray(OSU_TRAIL * 2)
    private var osuTrailHead = 0
    private var osuTrailSize = 0

    override fun init() {
        register<TerminalEvent.Open> {
            cachedMinCol = null
            cachedMinRow = null
            hoveredSlot = null
            resetOsu()

            // (CenterCursorOnMenus already did it when the window opened; this is for it being off)
            val centeredAlready = System.currentTimeMillis() - com.github.noamm9.features.impl.general.CenterCursorOnMenus.lastCenteredAt < 2000
            if (centerCursor.value && ! centeredAlready && event.handler.enabled()) mc.execute {
                GLFW.glfwSetCursorPos(mc.window.handle(), UResolution.windowWidth / 2.0, UResolution.windowHeight / 2.0)
            }
        }

        register<ScreenEvent.PreRender> {
            if (! TerminalListener.inTerm) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            event.isCanceled = true

            Resolution.push(event.context)
            // fork: a throw while drawing (an unstable solution list, a draw call) must not leave Resolution and the pose pushed
            var posePushed = false
            try {

            val uiScale = 3f * scale.value
            val screenWidth = Resolution.width / uiScale
            val screenHeight = Resolution.height / uiScale
            val (gridWidth, gridHeight) = handler.gridSize
            val gap = slotGap.value
            val pad = padding.value
            val slotSize = 16f

            val width = gridWidth * slotSize + (gridWidth - 1) * gap
            val height = gridHeight * slotSize + (gridHeight - 1) * gap
            val offsetX = screenWidth / 2f - width / 2f
            val offsetY = screenHeight / 2f - height / 2f
            val mx = (Resolution.getMouseX() / uiScale) - offsetX
            val my = (Resolution.getMouseY() / uiScale) - offsetY

            event.context.pose().pushMatrix()
            posePushed = true
            event.context.pose().scale(uiScale, uiScale)
            event.context.pose().translate(offsetX, offsetY)

            if (titleColor.value.isVisable()) event.context.drawCenteredString(handler.displayName, width / 2f, - 15f - pad, color = titleColor.value, scale = 1.2f)
            if (backgroundColor.value.isVisable()) event.context.drawRect(- pad, - pad, width + pad * 2, height + pad * 2, backgroundColor.value)
            // fork: Rainbow Border was read only by renderTerminal (the sim), so real terminals never showed it
            if (borderColor.value.isVisable()) {
                if (rainbowBorder.value) event.context.drawRainbowBorder(- pad, - pad, width + pad * 2, height + pad * 2, rainbowBorderSpeed.value, borderColor.value.alpha)
                else event.context.drawBorder(- pad, - pad, width + pad * 2, height + pad * 2, borderColor.value)
            }

            val minCol = cachedMinCol ?: if (handler.solution.isEmpty()) 0 else handler.solution.minOf { it.slotId % 9 }.also { cachedMinCol = it }
            val minRow = cachedMinRow ?: if (handler.solution.isEmpty()) 0 else handler.solution.minOf { it.slotId / 9 }.also { cachedMinRow = it }

            fun slotPos(slot: Int): Pair<Float, Float> {
                val col = slot % 9 - minCol
                val row = slot / 9 - minRow
                val x = col * (slotSize + gap)
                val y = row * (slotSize + gap)
                return x to y
            }

            hoveredSlot = run {
                // fork: was `mx > width && my > height`, which only rejected a cursor past BOTH
                // edges — clicking outside one edge still computed a slot index.
                if (mx < 0 || my < 0 || mx > width || my > height) return@run null

                val cell = slotSize + gap
                val col = (mx / cell).toInt() + minCol
                val row = (my / cell).toInt() + minRow

                return@run row * 9 + col
            }

            handler.solution.forEachIndexed { index, click ->
                val slot = click.slotId
                val (slotX, slotY) = slotPos(slot)

                handler.renderSlot(event.context, slotX, slotY, index, click, solutionColor.value)

                val item = TerminalListener.currentItems[slot]
                if (item != null && "terminal" in NoammAddons.debugFlags) {
                    event.context.item(item, slotX.toInt(), slotY.toInt())
                    event.context.itemDecorations(mc.font, item, slotX.toInt(), slotY.toInt())
                }
            }

            handler.render(event.context, solutionColor.value, ::slotPos, mx, my, ::hoveredSlot)
            drawOsuOverlay(event.context, handler, ::slotPos, mx, my, height, pad)

            } finally {
                if (posePushed) event.context.pose().popMatrix()
                Resolution.pop(event.context)
            }
        }

        register<ContainerEvent.MouseClick> {
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            event.isCanceled = true
            handler.click()
        }

        register<ContainerEvent.Keyboard> {
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            if (event.key.equalsOneOf(KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value, InputConstants.KEY_ESCAPE)) return@register
            event.isCanceled = true
            if (event.key != KeyMappingHelper.getBoundKeyOf(mc.options.keyDrop).value) return@register
            if (System.currentTimeMillis() - lastDropTime < 50) return@register
            lastDropTime = System.currentTimeMillis()

            handler.click()
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! soundsEnabled.value) return@register
            if (! TerminalListener.inTerm) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (p3sim) {
                if (packet.sound.value() != SoundEvents.EXPERIENCE_ORB_PICKUP) return@register
                if (packet.volume != 1f || packet.pitch != 1f) return@register
            }
            else {
                if (packet.sound.value() != SoundEvents.NOTE_BLOCK_PLING.value()) return@register
                if (packet.volume != 8f || packet.pitch != 4.047619f) return@register
            }

            event.isCanceled = true
        }

        register<PacketEvent.Sent> {
            if (! soundsEnabled.value) return@register
            if (! TerminalListener.inTerm) return@register
            if (event.packet !is ServerboundContainerClickPacket) return@register
            playClickSound()
        }
    }

    fun renderTerminal(ctx: GuiGraphicsExtractor, handler: Terminal, items: Map<Int, ItemStack>) {
        Resolution.push(ctx)

        val uiScale = 3f * scale.value
        val screenWidth = Resolution.width / uiScale
        val screenHeight = Resolution.height / uiScale
        val (gridWidth, gridHeight) = handler.gridSize
        val gap = slotGap.value
        val pad = padding.value
        val slotSize = 16f

        val width = gridWidth * slotSize + (gridWidth - 1) * gap
        val height = gridHeight * slotSize + (gridHeight - 1) * gap
        val offsetX = screenWidth / 2f - width / 2f
        val offsetY = screenHeight / 2f - height / 2f
        val mx = (Resolution.getMouseX() / uiScale) - offsetX
        val my = (Resolution.getMouseY() / uiScale) - offsetY

        ctx.pose().pushMatrix()
        ctx.pose().scale(uiScale, uiScale)
        ctx.pose().translate(offsetX, offsetY)

        if (titleColor.value.isVisable()) ctx.drawCenteredString(handler.displayName, width / 2f, - 15f - pad, color = titleColor.value, scale = 1.2f)
        if (backgroundColor.value.isVisable()) ctx.drawRect(- pad, - pad, width + pad * 2, height + pad * 2, backgroundColor.value)
        if (borderColor.value.isVisable()) {
            if (rainbowBorder.value) ctx.drawRainbowBorder(- pad, - pad, width + pad * 2, height + pad * 2, rainbowBorderSpeed.value, borderColor.value.alpha)
            else ctx.drawBorder(- pad, - pad, width + pad * 2, height + pad * 2, borderColor.value)
        }

        val solutionSlots = handler.solution.map { it.slotId }
        val minCol = cachedMinCol ?: if (solutionSlots.isEmpty()) 0 else solutionSlots.minOf { it % 9 }.also { cachedMinCol = it }
        val minRow = cachedMinRow ?: if (solutionSlots.isEmpty()) 0 else solutionSlots.minOf { it / 9 }.also { cachedMinRow = it }

        fun slotPos(slot: Int): Pair<Float, Float> {
            val col = slot % 9 - minCol
            val row = slot / 9 - minRow
            val x = col * (slotSize + gap)
            val y = row * (slotSize + gap)
            return x to y
        }

        hoveredSlot = run {
            // fork: same fix as the real-terminal path; `&&` let a cursor right of the grid wrap into the next row (sim only)
            if (mx < 0 || my < 0 || mx > width || my > height) return@run null

            val cell = slotSize + gap
            val col = (mx / cell).toInt() + minCol
            val row = (my / cell).toInt() + minRow

            return@run row * 9 + col
        }

        handler.solution.forEachIndexed { index, click ->
            val slot = click.slotId
            val (slotX, slotY) = slotPos(slot)

            handler.renderSlot(ctx, slotX, slotY, index, click, solutionColor.value)

            val item = items[slot]
            if (item != null && "terminal" in NoammAddons.debugFlags) {
                ctx.item(item, slotX.toInt(), slotY.toInt())
                ctx.itemDecorations(mc.font, item, slotX.toInt(), slotY.toInt())
            }
        }

        handler.render(ctx, solutionColor.value, ::slotPos, mx, my, ::hoveredSlot)
        drawOsuOverlay(ctx, handler, ::slotPos, mx, my, height, pad)

        ctx.pose().popMatrix()
        Resolution.pop(ctx)
    }



    fun spanFor(count: Int) = count * 16f + (count - 1) * slotGap.value

    fun drawSlot(ctx: GuiGraphicsExtractor, x: Number, y: Number, color: Color, w: Number = 16, h: Number = 16) {
        when (slotStyle.value) {
            0 -> ctx.drawRect(x, y, w, h, color)
            1 -> {
                ctx.drawRect(x, y, w, h, color.withAlpha(40))
                ctx.drawBorder(x, y, w, h, color)
            }

            2 -> ctx.drawFloatingRect(x, y, w, h, color.darker())
            3 -> {
                val fx = x.toFloat()
                val fy = y.toFloat()
                val fw = w.toFloat()
                val fh = h.toFloat()
                // shortcut: only Melody's column is non-square and it is taller than wide; a wide slot would need a horizontal capsule
                if (fw == fh) drawOsuCircle(ctx, fx + fw / 2f, fy + fh / 2f, fw / 2f - 0.4f, color)
                else ctx.drawCapsule(fx, fy, fw, fh, color, Color.WHITE, 1.2f)
            }
        }
    }

    private fun drawOsuCircle(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Color) {
        ctx.drawRing(cx, cy, 0f, r - 1.2f, color)
        ctx.drawRing(cx, cy, r - 2f, r - 1.2f, osuShadow)
        ctx.drawRing(cx, cy, r - 1.2f, r, Color.WHITE)
    }

    private fun resetOsu() {
        osuWas.fill(false)
        osuHitAt.fill(0L)
        osuTrailSize = 0
    }

    /** Approach rings, "300" bursts, combo and cursor trail. Reads solver state, writes none of it. */
    private fun drawOsuOverlay(ctx: GuiGraphicsExtractor, handler: Terminal, slotPos: (Int) -> Pair<Float, Float>, mx: Float, my: Float, height: Float, pad: Float) {
        if (slotStyle.value != 3) return
        val now = System.currentTimeMillis()

        if (handler is MelodyTerminal) {
            val correct = handler.correct
            val current = handler.current
            val row = handler.buttonRow
            // the ring closes onto the button as the indicator reaches the right column
            if (correct != null && current != null && row != null) {
                val (x, y) = slotPos(handler.gridSize.first - 1 + row * 9)
                val r = 7.6f + abs(current - correct) * 3f
                ctx.drawRing(x + 8f, y + 8f, r - 0.8f, r, Color.WHITE.withAlpha(200))
            }
        }
        else {
            if (handler is NumberTerminal) handler.solution.firstOrNull()?.let {
                val (x, y) = slotPos(it.slotId)
                val t = (now % 600L) / 600f
                val r = 7.6f * (1f + 1.6f * (1f - t))
                ctx.drawRing(x + 8f, y + 8f, r - 0.8f, r, Color.WHITE.withAlpha((80 + 175 * t).toInt()))
            }

            // a slot that left the solution since last frame was just hit (predict() drops it on the click itself)
            osuIs.fill(false)
            for (i in handler.solution.indices) {
                val slot = handler.solution[i].slotId
                if (slot in osuIs.indices) osuIs[slot] = true
            }
            for (slot in osuIs.indices) {
                if (osuWas[slot] && ! osuIs[slot]) osuHitAt[slot] = now
                osuWas[slot] = osuIs[slot]

                val age = now - osuHitAt[slot]
                if (age >= OSU_HIT_MS) continue
                val k = age / OSU_HIT_MS.toFloat()
                val textScale = 0.8f + 0.3f * k
                val (x, y) = slotPos(slot)
                // alpha floor: text drawn with alpha near 0 renders opaque
                ctx.drawCenteredString("300", x + 8f, y + 8f - 4.5f * textScale, color = osuHitColor.withAlpha((255 * (1f - k)).toInt().coerceAtLeast(8)), scale = textScale)
            }
        }

        ctx.drawString("${handler.completedClicks()}x", - pad, height + pad + 2f, scale = 1.2f)

        osuTrail[osuTrailHead * 2] = mx
        osuTrail[osuTrailHead * 2 + 1] = my
        osuTrailHead = (osuTrailHead + 1) % OSU_TRAIL
        if (osuTrailSize < OSU_TRAIL) osuTrailSize ++
        for (i in 1 until osuTrailSize) {
            val idx = (osuTrailHead - 1 - i + OSU_TRAIL) % OSU_TRAIL
            val fade = 1f - i / OSU_TRAIL.toFloat()
            ctx.drawRing(osuTrail[idx * 2], osuTrail[idx * 2 + 1], 0f, 1.5f * fade, osuCursorColor.withAlpha((160 * fade).toInt()))
        }
        ctx.drawRing(mx, my, 2.6f, 3.4f, osuCursorColor)
    }

    fun drawCenteredText(ctx: GuiGraphicsExtractor, text: String, slotX: Number, slotY: Number) {
        val centerX = slotX.toFloat() + 8f
        val centerY = slotY.toFloat() + 8f - UGraphics.getFontHeight() / 2
        ctx.drawCenteredString(text, centerX, centerY, color = overlayTextColor.value)
    }

    fun resetLayout() {
        cachedMinCol = null
        cachedMinRow = null
        hoveredSlot = null
        resetOsu()
    }

    fun playClickSound() {
        if (! soundsEnabled.value) return
        // mc.execute like the Click Sound's own path: PacketEvent.Sent is not guaranteed to be on the client thread
        if (slotStyle.value == 3 && osuHitsound.value) mc.execute { USound.playSoundStatic(osuHitSoundEvent, osuHitsoundVolume.value, 1f) }
        else clickSound.invoke()
    }

    fun Terminal.hoveredClick(): TerminalClick? {
        val slot = hoveredSlot ?: return null
        return getClickForSlot(slot)?.also(::predict)
    }

    private fun Terminal.click() {
        if (TerminalListener.checkFcDelay()) return
        hoveredClick()?.send()
    }

    fun Terminal.enabled() = when (this) {
        is NumberTerminal -> numbersTerm.value
        is ColorsTerminal -> colorsTerm.value
        is MelodyTerminal -> melodyTerm.value
        is RubixTerminal -> rubixTerm.value
        is RedGreenTerminal -> redGreenTerm.value
        is StartWithTerminal -> startsWithTerm.value
    }

    override fun isActive() = TerminalListener.inTerm
    override fun CommandBuilder.command() {
        setName(*NaCommand.names)
        requires("enable $name feature first") { enabled }
        literal("termsim") {
            description("Opens the terminal simulator")
            runs { GuiUtils.setScreen(TerminalSimSelectorScreen()) }

            argument("terminal", StringArgumentType.word()) {
                suggests { SimTerminal.all.map(SimTerminal::name) + "random" }
                runs { ctx ->
                    val name = StringArgumentType.getString(ctx, "terminal")
                    val sim = if (name.equals("random", ignoreCase = true)) SimTerminal.all.random()
                    else SimTerminal.fromName(name) ?: return@runs ChatUtils.modMessage("§cUnknown terminal")
                    GuiUtils.setScreen(TerminalSimScreen(sim))
                }
            }
        }
    }
}