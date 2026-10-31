package com.github.noamm9.utils

import net.minecraft.network.chat.TextColor
import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.NoammAddons.scope
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.EventBus.register
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import gg.essential.universal.UChat
import gg.essential.universal.UResolution
import gg.essential.universal.wrappers.UPlayer
import kotlinx.coroutines.*
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.*
import net.minecraft.network.protocol.game.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object ChatUtils: ISelfInit {
    private val queue = ConcurrentLinkedQueue<Pair<String, Long>>()
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var lastSentTime = 0L

    override fun init() {
        register<PacketEvent.Sent> {
            if (event.packet !is ServerboundChatPacket &&
                event.packet !is ServerboundChatCommandPacket &&
                event.packet !is ServerboundChatCommandSignedPacket
            ) return@register

            lastSentTime = System.currentTimeMillis()
        }
    }

    private fun process() {
        if (! isProcessing.compareAndSet(false, true)) return
        scope.launch {
            while (isActive && queue.isNotEmpty()) {
                var (str, time) = queue.poll() ?: break
                str = str.removeFormatting()

                val waitTime = time - (System.currentTimeMillis() - lastSentTime)
                if (waitTime > 0) delay(waitTime)

                suspendCancellableCoroutine { cont ->
                    mc.execute {
                        val player = UPlayer.getPlayer()
                        if (player != null) {
                            if (str.startsWith("/")) player.connection.sendCommand(str.removePrefix("/"))
                            else UChat.say(str)
                        }
                        lastSentTime = System.currentTimeMillis()
                        cont.resume(Unit)
                    }
                }
            }
        }.invokeOnCompletion {
            isProcessing.set(false)
            if (queue.isNotEmpty()) process()
        }
    }

    fun sendMessage(message: String, ms: Long = 300L) {
        queue.add(message to ms)
        process()
    }

    fun sendCommand(command: String, ms: Long = 300L) {
        queue.add("/" + command.removePrefix("/") to ms)
        process()
    }

    fun String.removeFormatting(): String {
        if (isEmpty()) return this

        val len = length
        val out = CharArray(len)
        var outPos = 0
        var i = 0

        while (i < len) {
            val c = this[i]

            if ((c == '§' || c == '&') && i + 1 < len) {
                val next = this[i + 1]

                if ((next == 'x' || next == 'X') && i + 7 < len) {
                    val end = i + 7
                    var k = i + 2
                    while (k <= end) {
                        val h = this[k]
                        if (h !in '0' .. '9' && h !in 'a' .. 'f' && h !in 'A' .. 'F') break
                        k ++
                    }
                    if (k > end) {
                        i += 8
                        continue
                    }
                }

                if (next in '0' .. '9'
                    || next in 'a' .. 'f' || next in 'A' .. 'F'
                    || next in 'k' .. 'o' || next in 'K' .. 'O'
                    || next == 'r' || next == 'R'
                ) {
                    i += 2
                    continue
                }
            }

            out[outPos ++] = c
            i ++
        }

        return if (outPos == len) this else String(out, 0, outPos)
    }

    /// fork: no prefix, and so no leading space either
    fun modMessage(msg: Any?) = chat(msg)

    fun debug(flag: String, msg: Any?) {
        if (NoammAddons.debugFlags.contains(flag)) modMessage(msg)
    }

    /// fork: Engineer Client's P3 Sim answers /pc itself (party chat as Hypixel shows it), and there is no Hypixel
    /// party list for PartyUtils to learn, so an alert tried in the sim was dropped here. Looked up by name.
    private val inSimMethod by lazy { runCatching { Class.forName("com.engineerclient.p3sim.P3Sim").getMethod("getInSim") }.getOrNull() }
    fun inP3Sim() = runCatching { inSimMethod?.invoke(null) as? Boolean }.getOrNull() == true

    /// fork: true when a party line can actually be sent — in the sim, or in a real party with
    /// someone else in it. Shared so callers can test it before taking a one-shot send slot.
    fun canSendPartyMessage() = inP3Sim() || (PartyUtils.isInParty && PartyUtils.partySize > 1)

    fun sendPartyMessage(msg: Any?) {
        if (! canSendPartyMessage()) return
        sendMessage("/pc $msg")
    }

    fun chat(msg: Any?) = mc.execute { UChat.chat(msg.toString().addColor()) }
    fun chat(comp: Component) = mc.execute { UChat.chat(comp) }

    fun String.addColor() = replace("&", "§")

    val Component.unformattedText get() = string.removeFormatting()
    val Component.formattedText get() = formatted(this)

    // fork (perf): a colour value -> legacy format table, built once. The loop below used to scan all 22
    // ChatFormatting entries per styled segment, constructing a TextColor for each one just to compare - and this
    // runs for every chat line, every action-bar packet and every tab-list entry.
    private val legacyFormatsByColor: Map<Int, ChatFormatting> = buildMap {
        for (format in ChatFormatting.entries) {
            TextColor.fromLegacyFormat(format)?.let { put(it.value, format) }
        }
    }

    private val formatted = fun(comp: Component): String {
        val sb = StringBuilder()

        comp.visit({ style, string ->
            style.color?.let { textColor ->
                legacyFormatsByColor[textColor.value]?.let { sb.append(it) }
            }

            if (style.isBold) sb.append(ChatFormatting.BOLD)
            if (style.isItalic) sb.append(ChatFormatting.ITALIC)
            if (style.isUnderlined) sb.append(ChatFormatting.UNDERLINE)
            if (style.isStrikethrough) sb.append(ChatFormatting.STRIKETHROUGH)
            if (style.isObfuscated) sb.append(ChatFormatting.OBFUSCATED)

            sb.append(string)

            Optional.empty<String>()
        }, Style.EMPTY)

        return sb.toString()
    }

    private var title = ""
    private var subtitle = ""

    fun showTitle(title: Any? = "", subtitle: Any? = "") {
        this.title = title.toString()
        this.subtitle = subtitle.toString()
        ThreadUtils.scheduledTask(40, titleRenderer::unregister)
        titleRenderer.register()
    }

    fun clickableChat(message: String, prefix: Boolean = false, command: String? = null, hover: String? = null, copy: String? = null) {
        if (mc.player == null) return
        val mainComponent = Component.literal(message.addColor())
        var style = Style.EMPTY

        if (hover != null) style = style.withHoverEvent(HoverEvent.ShowText(Component.literal(hover.addColor())))
        if (command != null) style = style.withClickEvent(ClickEvent.RunCommand(command))
        else if (copy != null) style = style.withClickEvent(ClickEvent.CopyToClipboard(copy))

        mainComponent.style = style

        ChatUtils.chat(mainComponent)
    }

    private val titleRenderer = EventBus.listener<RenderOverlayEvent> {
        val x = UResolution.scaledWidth / 2f
        val height = UResolution.scaledHeight
        val y = height / 2f - (height * 0.056).roundToInt()

        event.context.drawCenteredString(title, x, y, scale = 2.5)
        event.context.drawCenteredString(subtitle, x, y + (height / 15.42f).roundToInt(), scale = 1.5)
    }
}