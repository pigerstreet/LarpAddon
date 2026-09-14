package com.github.noamm9.features.impl.dungeon

//#if CHEAT

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.*
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import gg.essential.universal.UMinecraft

object AutoGFS: Feature("Automatically refills dungeon items from your sacks using /gfs while in dungeons.") {
    /// fork: refill once when the run starts instead of topping items up every few seconds all run.
    private val onlyAtStart by ToggleSetting("Only At Run Start", true).withDescription("Refill once when the dungeon run starts instead of checking all run.")
    private val delay by SliderSetting("Check Delay", 20.0, 5.0, 60.0, 1.0, "s").withDescription("How often to check for refills.").hideIf { onlyAtStart.value }

    private val refillPearl by ToggleSetting("Refill Pearl")
    private val refillTNT by ToggleSetting("Refill TNT")
    private val refillJerry by ToggleSetting("Refill Jerry")
    private val refillLeaps by ToggleSetting("Refill Leaps")
    private val refillTwilight by ToggleSetting("Refill Twilight")

    private val pyTwilight by ToggleSetting("Refill after lightning", true).section("Twilight").showIf { refillTwilight.value }
    private val coreTwilight by ToggleSetting("Refill in Core").showIf { refillTwilight.value }
    private val p5Twilight by ToggleSetting("Refill after M7 relics", true).showIf { refillTwilight.value }
    private val twilightAmount by SliderSetting("Twilight Amount", 8, 4, 8, 1).withDescription("The amount of Twilight you want the auto to pull out of sacks").showIf { (p5Twilight.value || pyTwilight.value) && refillTwilight.value }

    private val p5Message = Regex("^\\[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you\\.$")
    private val pyMessage1 = Regex("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$")
    private var pyHappened = false

    private val sackEmptyMessage = Regex("^You have no (.+) in your Sacks!$")
    private val emptySacks = mutableSetOf<String>()
    private var refillCursor = 0

    override fun init() {
        register<WorldChangeEvent> {
            pyHappened = false
            emptySacks.clear()
        }

        ThreadUtils.loop({ delay.value * 1000 }) { if (! onlyAtStart.value) refill() }
        /// fork: posted from DungeonListener's coroutine once Mort's map line arrives and classes are known,
        /// so the refill hops to the client thread before it reads the inventory.
        register<DungeonEvent.RunStatedEvent> { if (onlyAtStart.value) mc.execute { refill(atStart = true) } }

        register<ChatMessageEvent> {
            if (! refillTwilight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (! LocationUtils.inBoss) return@register
            if (LocationUtils.dungeonFloor != "M7") return@register
            val clazz = DungeonListener.thePlayer?.clazz ?: return@register
            val msg = event.unformattedText

            val dps = clazz.equalsOneOf(DungeonClass.Archer, DungeonClass.Berserk)
            fun fn() = gfs("twilight_arrow_poison", twilightAmount.value)

            when {
                coreTwilight.value && msg == "The Core entrance is opening!" && dps -> fn()
                p5Twilight.value && ! dps && p5Message.matches(msg) -> fn()
                pyTwilight.value && ! pyHappened && clazz == DungeonClass.Archer && pyMessage1.matches(msg) -> fn().also { pyHappened = true }
            }
        }
    }

    private val chatListener = EventBus.listener<ChatMessageEvent> {
        val item = sackEmptyMessage.matchEntire(event.unformattedText)?.groupValues?.get(1) ?: return@listener
        emptySacks.add(item.lowercase().replace(" ", "_"))
        listener.unregister()
    }

    private fun refill(atStart: Boolean = false) {
        if (! enabled || ! LocationUtils.inDungeon) return
        if (UMinecraft.currentScreenObj != null) return
        if (DungeonListener.thePlayer?.isDead == true) return

        var pearlCount = 0
        var jerryCount = 0
        var tntCount = 0
        var leapCount = 0

        for (stack in player.inventory.nonEquipmentItems) when (stack.skyblockId) {
            "ENDER_PEARL" -> pearlCount += stack.count
            "INFLATABLE_JERRY" -> jerryCount += stack.count
            "SUPERBOOM_TNT" -> tntCount += stack.count
            "SPIRIT_LEAP" -> leapCount += stack.count
        }

        /// fork: at run start top up any shortfall, including from empty; the periodic check keeps upstream's
        /// "you already carry some, and at least 4 are missing" gate so it never runs /gfs for one pearl.
        val pending = listOf(
            RefillEntry(pearlCount, 16, "ender_pearl", refillPearl.value),
            RefillEntry(jerryCount, 64, "inflatable_jerry", refillJerry.value),
            RefillEntry(tntCount, 64, "superboom_tnt", refillTNT.value),
            RefillEntry(leapCount, 16, "spirit_leap", refillLeaps.value),
        ).filter { it.enabled && (atStart || it.current > 0) && it.max - it.current >= (if (atStart) 1 else 4) && it.gfsName !in emptySacks }

        if (pending.isEmpty()) return
        /// fork: with Only At Run Start this is the only refill that runs, so it tops up everything at once
        /// instead of one item per check.
        if (atStart) return pending.forEach { gfs(it.gfsName, it.max - it.current) }
        val target = pending[refillCursor % pending.size]
        refillCursor ++
        gfs(target.gfsName, target.max - target.current)
    }

    private fun gfs(id: String, count: Int) {
        ChatUtils.sendCommand("gfs $id $count", 3000)
        ThreadUtils.setTimeout(5000) { chatListener.unregister() }
        chatListener.register()
    }

    private data class RefillEntry(val current: Int, val max: Int, val gfsName: String, val enabled: Boolean)
}
//#endif