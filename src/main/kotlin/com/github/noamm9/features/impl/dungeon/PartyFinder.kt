package com.github.noamm9.features.impl.dungeon

import com.github.noamm9.commands.CommandBuilder
import com.github.noamm9.config.types.*
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.init.types.ICommandProvider
import com.github.noamm9.utils.*
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.NumbersUtils.romanToDecimal
import com.github.noamm9.utils.NumbersUtils.toFixed
import com.github.noamm9.utils.items.ItemRarity
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.network.ProfileUtils
import com.github.noamm9.utils.network.cache.ProfileCache
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.RenderHelper.width
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.minecraft.network.chat.*
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import kotlin.random.Random
import java.util.*

object PartyFinder: Feature(), ICommandProvider {
    private val showJoinStats by ToggleSetting("Join Stats", true).withDescription("Prints the dungeon stats of a player that joins your party.").section("Join Stats")

    private val showLevelReq by ToggleSetting("Show Level Req", true).withDescription("Shows the red level requirement number.").section("Menu")
    private val showMissingOverlay by ToggleSetting("Show Missing Classes", true).withDescription("Shows missing classes on the head.")
    private val showMenuButton by ToggleSetting("Better PF Button", true).withDescription("Adds a button beside the Party Finder that opens the Better PF site's live F7/M7 party list as a screen. Clicking a party clicks it in the real menu when it is on the page you have open.")

    private val showMissingTooltip by ToggleSetting("Show Missing List", true).withDescription("Shows the list of missing classes at the bottom of the tooltip.").section("Tooltip")
    private val showTooltipStats by MultiCheckboxSetting("Show Player Stats", mutableMapOf(
        "Catacombs Level" to false,
        "Total Secrets & Avg" to false,
        "Magical Power" to false,
        "PB Time" to false
    )).withDescription("Shows player stats (Cata/Secrets/PB) in tooltip.")

    private val autoKick by ToggleSetting("Auto Kick", false).withDescription("Automatically kick players that don't meet requirements.").section("Auto Kick")
    private val autoKickFloor by DropdownSetting("Floor", 6, listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7")).showIf { autoKick.value }
    private val masterMode by ToggleSetting("Master Mode", true).showIf { autoKick.value }
    private val informKicked by ToggleSetting("Inform Kicked", false).withDescription("Send a party chat message before kicking.").showIf { autoKick.value }
    // fork: a Maximum Seconds for every floor (F1-F7, M1-M7), each saved on its own - only the selected floor's shows
    // (owner, 2026-10-23: switching floors no longer means setting the time again)
    private val kickFloorLabel get() = (if (masterMode.value) "M" else "F") + (autoKickFloor.value + 1)
    private val maximumSeconds: Map<String, SliderSetting<Int>> = listOf("F", "M").flatMap { m -> (1 .. 7).map { "$m$it" } }.associateWith { label ->
        SliderSetting("Maximum Seconds $label", 400, 60, 480, 5, suffix = "s").withDescription("Maximum S+ PB time in seconds on $label.")
            .showIf { autoKick.value && kickFloorLabel == label }.also { configSettings.add(it) }
    }
    private val minimumSecrets by SliderSetting("Minimum Secrets", 0, 0, 200, 1, suffix = "k").withDescription("Minimum secrets in thousands.").showIf { autoKick.value }
    private val minimumMagicalPower by SliderSetting("Minimum MP", 0, 0, 3000, 10).withDescription("Minimum MP Set to 0 to disable. Skipped when Inventory API is off.").showIf { autoKick.value }
    // fork: a joke kick message - a share of joiners who meet the requirements are told their IQ is too low, and stay
    private val iqCheck by ToggleSetting("IQ Check", false).withDescription("Sends a fake kick message in party chat for a made-up low IQ, e.g. IQ(83/140), to a random share of joiners who meet the requirements. Nobody is kicked for it.").showIf { autoKick.value }
    private val iqMessageChance by SliderSetting("IQ Message Chance", 20, 1, 100, 1, suffix = "%").withDescription("Chance that a joiner who meets the requirements gets the IQ message.").showIf { autoKick.value && iqCheck.value }

    private val dungeonGroupJoinRegex = Regex("^Party Finder > (\\w{1,16}) joined the dungeon group! \\((\\w+) Level (\\d+)\\)$")
    private val kickedPlayers = mutableSetOf<String>()

    private val partyMembersRegex = Regex("^\\s*(\\w{1,16}):?\\s+(Archer|Tank|Berserk|Healer|Mage)\\s*\\((\\d+)\\)\\s*$")
    private val levelRequiredRegex = Regex("Dungeon Level Required:\\s*(\\d+)")
    private val floorRegex = Regex("Floor:\\s*Floor\\s+(\\w+)")
    private val selectedClassRegex = Regex("Currently Selected: (.+)")
    private val selectDungeonClassRegex = Regex("§7View and select a dungeon class\\.")

    /// fork: the tab list's layout placeholders, whose profile names look like `!B-a`. This pattern was
    /// being compiled inside the filter, so a full `Regex` was built for each of the ~80 tab entries on
    /// every keystroke that asked the `pfs` argument for suggestions.
    private val tabPlaceholderRegex = Regex("^![A-Z]-[a-z]$")

    private val classNames = listOf("&4&lArcher", "&a&lTank", "&6&lBerserk", "&5&lHealer", "&b&lMage")

    /// fork: three separate places stripped the formatting off these same five constants to compare them
    /// against a listing - one of them per head per frame - so the stripped forms are kept alongside the
    /// formatted ones instead of being rebuilt from them every time.
    private val classNamesPlain = classNames.map { it.removeFormatting() }

    private var selectedClass: String? = null
    private var inPartyFinder = false

    private val fetchSemaphore = Semaphore(5)
    private val pendingFetches = Collections.synchronizedSet(HashSet<String>())

    /// fork: the slot render below fires once per slot per frame, and for each of the 21 head slots it
    /// rebuilt the whole lore list, stripped the formatting off every line and ran two regexes over each
    /// one - a few hundred regex passes and a couple of hundred throwaway strings every frame the Party
    /// Finder menu was open, all to recompute the same numbers. A head's stack is replaced rather than
    /// edited when the menu refreshes, so the stack itself is a sound key: a refreshed slot misses the
    /// cache and is reparsed, the rest are answered from it.
    ///
    /// Both readings live on one immutable result rather than in fields of their own, so a merge cannot
    /// leave them holding a verdict that belongs to some other head - it either keeps this whole class or
    /// fails to compile.
    private class HeadInfo(lore: List<String>, wantLevel: Boolean, wantClasses: Boolean) {
        val levelRequired: Int
        val missingLines: List<String>

        init {
            var level = 0
            val found = mutableListOf<String>()

            for (line in lore) {
                val cleanLine = line.removeFormatting()
                if (wantLevel) levelRequiredRegex.find(cleanLine)?.groupValues?.get(1)?.toInt()?.let { level = it }
                if (wantClasses) partyMembersRegex.matchEntire(cleanLine)?.destructured?.let { found.add(it.component2()) }
            }

            levelRequired = level

            /// fork: the two short lines the overlay draws are a function of the classes just read and of
            /// five constants, and nothing else - so they are folded in here, where a head is parsed once,
            /// rather than rebuilt from that reading for every head on every frame. The shape is the one
            /// the draw site had: the missing initials in declaration order, two to a line, blanks dropped.
            missingLines = if (! wantClasses) emptyList()
            else {
                val missing = classNames.filterIndexed { i, _ -> classNamesPlain[i] !in found }.map { it.take(5) }
                listOf(
                    missing.take(2).joinToString(""),
                    missing.drop(2).take(2).joinToString("")
                ).filter { it.isNotBlank() }
            }
        }
    }

    /// the keys are weak so the map cannot outlive the menu: Party Finder replaces its listings often,
    /// and once a refreshed slot drops the old stack nothing else holds it. WeakHashMap falls back to
    /// identity here because ItemStack overrides neither equals nor hashCode, which is what is wanted.
    /// Only the render thread touches this, so it needs no synchronizing - unlike [ItemRarity]'s cache.
    private var headCacheKey: Pair<Boolean, Boolean>? = null
    private val headCache = WeakHashMap<ItemStack, HeadInfo>()

    private fun headInfo(stack: ItemStack): HeadInfo {
        val key = showLevelReq.value to showMissingOverlay.value
        if (key != headCacheKey) {
            headCache.clear()
            headCacheKey = key
        }
        return headCache.getOrPut(stack) { HeadInfo(stack.lore, key.first, key.second) }
    }

    private val headSlots = setOf(
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 44
    )

    /// fork: the Party Finder is gone without a Close event when the Better PF screen was over it (PartyFinderMenu.kt).
    internal fun menuScreenLost() {
        inPartyFinder = false
        prefetchQueue.clear()
    }

    override fun init() {
        PfMenuButton.register { enabled && showMenuButton.value }

        register<ContainerEvent.Render.Slot.Post> {
            if (! inPartyFinder) return@register
            if (event.screen.title.string != "Party Finder") return@register
            if (! showLevelReq.value && ! showMissingOverlay.value) return@register
            if (event.slot.index !in headSlots) return@register
            val item = event.slot.item.takeUnless { it.isEmpty || ! it.`is`(Blocks.PLAYER_HEAD.asItem()) } ?: return@register

            val info = headInfo(item)
            val levelRequired = info.levelRequired

            event.context.pose().translate(event.slot.x.toFloat(), event.slot.y.toFloat())

            if (levelRequired != 0) {
                val str = "&c$levelRequired"
                event.context.drawString(str, 16f - str.width() * 0.6f, 0f, scale = 0.6f)
            }

            if (showMissingOverlay.value) {
                for ((i, line) in info.missingLines.withIndex()) {
                    event.context.drawString(
                        line,
                        0,
                        10f - if (i == 1) 6f else 0f,
                        scale = 0.65
                    )
                }
            }

            event.context.pose().translate(- event.slot.x.toFloat(), - event.slot.y.toFloat())
        }

        register<ContainerEvent.Render.Tooltip> {
            if (! inPartyFinder) return@register
            if (event.screen.title.string != "Party Finder") return@register
            if (! event.stack.`is`(Items.PLAYER_HEAD)) return@register
            if (event.screen.menu.slots.find { it.item == event.stack }?.index !in headSlots) return@register

            var floor = 0
            var type = 'F'

            val remainingClasses = classNamesPlain.toMutableList()

            event.lore.toList().forEachIndexed { index, comp ->
                val line = comp.formattedText

                val cleanLine = line.removeFormatting()
                if (cleanLine.contains("Dungeon: Master Mode")) type = 'M'
                floorRegex.find(cleanLine)?.groupValues?.get(1)?.let {
                    floor = it.toIntOrNull() ?: it.romanToDecimal()
                }

                partyMembersRegex.matchEntire(cleanLine)?.destructured?.let { (playerName, className, cLvl) ->
                    val level = cLvl.toInt()
                    val color = getColor(level)

                    val stats = if (showTooltipStats.value.values.any { it }) getLoreStats(playerName, floor, type) else ""

                    event.lore[index] = Component.literal(" §b$playerName: §e$className $color$level $stats".addColor())
                    remainingClasses.remove(className)
                }
            }

            if (selectedClass?.removeFormatting() in remainingClasses) {
                val idx = remainingClasses.indexOf(selectedClass?.removeFormatting())
                remainingClasses[idx] = "${selectedClass}§7"
            }

            if (showMissingTooltip.value) {
                event.lore.add(Component.literal("§cMissing: §7" + remainingClasses.joinToString(", ") { it.addColor() }))
            }
        }

        register<ContainerFullyOpenedEvent> {
            if (event.title.string == "Catacombs Gate") {
                event.items[45]?.lore?.takeIf { it.size > 3 && it[0].matches(selectDungeonClassRegex) }?.run {
                    selectedClassRegex.matchEntire(get(2).removeFormatting())?.destructured?.run {
                        /// fork: an `indexOf` straight into `classNames` throws the moment the line reads
                        /// anything but one of the five, which is the whole rest of the capture group.
                        /// Not knowing the selected class only costs the tooltip its grey highlight.
                        selectedClass = classNames.getOrNull(classNamesPlain.indexOf(component1()))
                    }
                }
            }
            else if (event.title.string == "Party Finder") {
                /// fork: `lore[5]` throws whenever that star arrives with a shorter lore than this
                /// expects, and it throws inside a container-open handler, so the rest of the handler is
                /// lost with it. `getOrNull` leaves the menu unrecognised instead, which is exactly what
                /// the star being absent already does.
                event.items[50]?.takeIf { it.`is`(Items.NETHER_STAR) }?.lore?.getOrNull(5)?.let {
                    if (! it.contains("§aCombat Level: ")) {
                        inPartyFinder = true
                    }
                }
                if (inPartyFinder && showTooltipStats.value.values.any { it }) prefetch(event.items)
                if (inPartyFinder && showMenuButton.value) PfMenuFeed.ensure()
            }
        }

        register<ContainerEvent.Close> { inPartyFinder = false; prefetchQueue.clear(); PfMenuFeed.close() }

        register<WorldChangeEvent> { kickedPlayers.clear() }

        register<ChatMessageEvent> {
            if (! autoKick.value && ! showJoinStats.value) return@register
            val name = dungeonGroupJoinRegex.find(event.unformattedText)?.groupValues?.getOrNull(1) ?: return@register
            if (name == mc.user.name) return@register

            scope.launch {
                if (autoKick.value) autoKickPlayer(name, PartyUtils.isLeader())
                if (showJoinStats.value) printPlayerStats(name)
            }
        }
    }

    override fun CommandBuilder.command() {
        setName("pfs")
        runs { scope.launch { printPlayerStats(mc.user.name) } }

        argument("name", StringArgumentType.word()) {
            suggests { TabListUtils.getTabList().mapNotNull { it.second.profile.name }.filterNot { it.matches(tabPlaceholderRegex) } }
            runs {
                val ign = StringArgumentType.getString(it, "name")
                scope.launch { printPlayerStats(ign) }
            }
        }
    }

    suspend fun printPlayerStats(name: String) {
        val cleanName = name.removeFormatting()

        val data = ProfileUtils.getProfile(cleanName).onFailure {
            ChatUtils.modMessage("&cFailed to get stats for $name. ${it.message?.take(100)}")
        }.getOrNull() ?: return
        val dungeons = data.dungeons

        val catacombs = dungeons.catacombs
        val masterCatacombs = dungeons.masterCatacombs

        val selectedArrow = data.favoriteArrow.lowercase().split("_").joinToString(" ") { it.uppercaseFirst() }
        val powerStone = data.selectedPower.lowercase().split("_").joinToString(" ") { it.uppercaseFirst() }

        /// fork: only the dragons (owner: "I just need to know if they have golden dragon and ender dragon"); the
        /// backup APIs send every pet the player owns.
        val pets = data.pets.filter { it.type == "GOLDEN_DRAGON" || it.type == "ENDER_DRAGON" }.map { pet ->
            val formattedType = if (pet.type == "GOLDEN_DRAGON") "Gdrag" else "Edrag"
            (runCatching { ItemRarity.valueOf(pet.tier).baseColor.toString() }.getOrNull() ?: "§7") to formattedType
        }.toSet()

        val inventoryApiEnabled = data.talismanBagData.isNotBlank()

        val armorComponents = data.armorInv.reversed().map { item ->
            val displayName = item.hoverName.formattedText
            val hoverText = buildList {
                add(displayName)
                addAll(item.lore)
            }.joinToString("\n")

            Component.literal("  $displayName".addColor()).withStyle {
                it.withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText.addColor())))
            }
        }

        val completionComponents = listOfNotNull(
            // fork: a backup API sends the S+ times and no completion counts, so the floors are the union of both.
            catacombs.tierCompletions.let { completionObj ->
                val highestFloor = (completionObj.keys + catacombs.fastestTimeSPlus.keys).mapNotNull(String::toIntOrNull).maxOrNull() ?: return@let null
                val header = "  &aFloor Completions &7(F$highestFloor): ${catacombs.fastestTimeSPlus["$highestFloor"]?.let(::formatTime) ?: "N/A"}"
                val hover = (0 .. highestFloor).joinToString("\n") { floor ->
                    val pb = catacombs.fastestTimeSPlus["$floor"]?.let(::formatTime) ?: "&cNo Comp"
                    val floorName = if (floor == 0) "Entrance" else floor.toString()
                    "&2&l*&a Floor $floorName: ${completionObj["$floor"]?.let { "&e$it &7(&6S+ &e$pb&7)" } ?: if (catacombs.fastestTimeSPlus["$floor"] != null) "&7(&6S+ &e$pb&7)" else "&cDNF"}"
                }

                Component.literal(header.addColor()).withStyle {
                    it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover.addColor())))
                }
            },
            masterCatacombs.tierCompletions.let { completionObj ->
                val highestFloor = (completionObj.keys + masterCatacombs.fastestTimeSPlus.keys).mapNotNull(String::toIntOrNull).maxOrNull() ?: return@let null
                val header = "  &l&4Master Completions &7(M$highestFloor): ${masterCatacombs.fastestTimeSPlus["$highestFloor"]?.let(::formatTime) ?: "N/A"}"
                val hover = (1 .. highestFloor).joinToString("\n") { floor ->
                    val pb = masterCatacombs.fastestTimeSPlus["$floor"]?.let(::formatTime) ?: "&cNo Comp"
                    "&c&l*&4 Floor $floor: ${completionObj["$floor"]?.let { "&e$it &7(&6S+ &e$pb&7)" } ?: if (masterCatacombs.fastestTimeSPlus["$floor"] != null) "&7(&6S+ &e$pb&7)" else "&cDNF"}"
                }

                Component.literal(header.addColor()).withStyle {
                    it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover.addColor())))
                }
            }
        )

        ChatUtils.chat("&a&l--- &r&b$cleanName&b's Dungeon Stats &a&l---&r")
        ChatUtils.chat(
            "  &4Cata Level: &b${data.cataLevel} &f| &dClass Avg: &b${data.classAverage.toFixed(1)} &f| &dMP: ${
                if (data.hasMagicalPower) "&e${data.magicalPower}" else if (data.inventoryKnown) "&cAPI off" else "&7?"
            }"
        )
        ChatUtils.chat("  &bSecrets: &d${dungeons.secrets} &b(&d${data.secretAverage.toFixed(2)}&b) &f| &cBlood Mobs: &f${data.bloodMobsKilled}")
        // fork: the F7 and M7 S+ times in plain sight (owner: "most important thing is their pb times", "I only care
        // about m7 and f7 pbs"), not only in the hover.
        fun pb(times: Map<String, Long>) = times["7"]?.takeIf { it > 0 }?.let(::formatTime) ?: "&7none"
        ChatUtils.chat("  &aF7 S+: &e${pb(catacombs.fastestTimeSPlus)} &f| &cM7 S+: &e${pb(masterCatacombs.fastestTimeSPlus)}")
        ChatUtils.chat("  Pets: ${if (pets.isEmpty()) "&cNone" else pets.joinToString("&7,&r ") { it.first + it.second }}")
        ChatUtils.chat("  &9Power Stone: &e$powerStone &f| &6Arrows: &e$selectedArrow")
        ChatUtils.chat(" ")

        when {
            ! data.inventoryKnown -> ChatUtils.chat("  &7Armor unknown (stats from a backup API)")
            ! inventoryApiEnabled -> ChatUtils.chat("  &cInventory API is Off")
            armorComponents.isEmpty() -> ChatUtils.chat("  &cArmor data unavailable")
            else -> armorComponents.forEach(ChatUtils::chat)
        }

        if (completionComponents.isNotEmpty()) {
            ChatUtils.chat(" ")
            completionComponents.forEach(ChatUtils::chat)
        }

        ChatUtils.chat(" ")
        ChatUtils.chat(Component.literal("  §cPress to kick $cleanName").withStyle {
            it.withClickEvent(ClickEvent.RunCommand("/party kick $cleanName"))
        })
        ChatUtils.chat("&a&l------------------------------------&r")
    }

    /// fork: the kick message goes out whether or not you are the leader; only the /party kick needs leader.
    private suspend fun autoKickPlayer(name: String, leader: Boolean) {
        if (name.equalsOneOf("Noamm", mc.user.name)) return

        if (leader && name in kickedPlayers) {
            ChatUtils.modMessage("&9AutoKick &f> &cAuto-kicking &e$name &c(previously kicked)")
            ThreadUtils.scheduledTask(6) { ChatUtils.sendCommand("party kick $name") }
            return
        }

        // fork: the kick reason names the floor the requirements were checked against, whatever the reason was
        var floorLabel = ""
        val reasons = mutableListOf<String>().apply {
            val profile = ProfileUtils.getProfile(name).getOrNull() ?: return@apply

            val dungeons = profile.dungeons
            val floor = autoKickFloor.value + 1
            val dungeonType = if (masterMode.value) dungeons.masterCatacombs else dungeons.catacombs

            val floorPrefix = if (masterMode.value) "M" else "F"
            floorLabel = "$floorPrefix$floor"
            val maxSeconds = maximumSeconds.getValue(floorLabel).value
            val pbReq = formatTime(maxSeconds * 1000)
            val pb = dungeonType.fastestTimeSPlus["$floor"]
            if (pb == null) add("PB(No S+/$pbReq)")
            else if (pb > maxSeconds * 1000L) {
                add("PB(${formatTime(pb)}/$pbReq)")
            }

            if (minimumSecrets.value > 0 && dungeons.secrets < minimumSecrets.value * 1000) {
                add("Secrets(${dungeons.secrets / 1000}k/${minimumSecrets.value}k)")
            }

            if (minimumMagicalPower.value > 0 && profile.hasMagicalPower) {
                if (profile.magicalPower < minimumMagicalPower.value) {
                    add("MP(${profile.magicalPower}/${minimumMagicalPower.value})")
                }
            }
        }.ifEmpty {
            // fork: a joiner who meets the requirements may still get the joke IQ kick message, but is not kicked
            if (iqCheck.value && Random.nextInt(100) < iqMessageChance.value) {
                val label = if (floorLabel.isEmpty()) "" else " ($floorLabel)"
                ChatUtils.sendCommand("pc Kicking $name$label: IQ(${Random.nextInt(60, 100)}/140)")
            }
            return
        }

        if (leader) {
            ThreadUtils.setTimeout(60 * 1000) { kickedPlayers.remove(name) }
            kickedPlayers.add(name)
        }

        if (informKicked.value) {
            ChatUtils.sendCommand("pc Kicking $name ($floorLabel): ${reasons.joinToString(", ")}")
            if (leader) ThreadUtils.scheduledTask(6) { ChatUtils.sendCommand("party kick $name") }
        }
        else {
            ChatUtils.modMessage("&c${if (leader) "Kicking" else "Not kicking (not leader)"} &e$name ($floorLabel):&r ${reasons.joinToString(", ")}")
            if (leader) ChatUtils.sendCommand("party kick $name")
        }
    }

    /// fork: every listed member's lookup is queued when the Party Finder opens, so a party's stats are usually
    /// there by the time its tooltip is hovered, instead of each lookup starting on the hover. Two at a time, outside
    /// [fetchSemaphore], so a hovered lookup never waits behind the queue; leaving the menu drops what has not started.
    private val prefetchQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val prefetchRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefetch(items: Map<Int, ItemStack>) {
        for ((slot, item) in items) {
            if (slot !in headSlots || ! item.`is`(Items.PLAYER_HEAD)) continue
            for (line in item.lore) {
                val key = partyMembersRegex.matchEntire(line.removeFormatting())?.groupValues?.get(1)?.uppercase() ?: continue
                if (key !in pendingFetches && ProfileCache.check(key) == null) prefetchQueue.add(key)
            }
        }
        drainPrefetch()
    }

    private fun drainPrefetch() {
        if (prefetchQueue.isEmpty() || ! prefetchRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                kotlinx.coroutines.coroutineScope {
                    repeat(2) {
                        launch {
                            while (true) {
                                val key = prefetchQueue.poll() ?: break
                                if (ProfileCache.check(key) != null || ! pendingFetches.add(key)) continue
                                try { ProfileUtils.getProfile(key) } finally { pendingFetches.remove(key) }
                            }
                        }
                    }
                }
            }
            finally {
                prefetchRunning.set(false)
                // names queued after the workers saw an empty queue would otherwise wait for the next menu update
                drainPrefetch()
            }
        }
    }

    private fun getLoreStats(name: String, floor: Int, type: Char): String {
        val key = name.removeFormatting().uppercase()
        val cached = ProfileCache.check(key)

        when {
            cached?.isFailure == true -> return "§c(Failed)"

            cached?.isSuccess == true -> {
                val data = cached.getOrThrow()
                return buildString {
                    if (showTooltipStats["Catacombs Level"]) append("§b(§6${data.cataLevel}§b)§r ")
                    if (showTooltipStats["Total Secrets & Avg"]) append("§8[§a${data.dungeons.secrets}§8/§b${data.secretAverage.toFixed(2)}§8]§r ")
                    /// fork: unknown (inventory API off, or a fallback source without it) shows ?, not 0.
                    if (showTooltipStats["Magical Power"]) append("§8[§d${if (! data.hasMagicalPower) "?" else data.magicalPower}§8]§r ")
                    if (showTooltipStats["PB Time"]) {
                        val pbObj = if (type == 'F') data.dungeons.catacombs else data.dungeons.masterCatacombs
                        val pb = pbObj.fastestTimeSPlus["$floor"]?.let(::formatTime) ?: "N/A"
                        append("§8[§9$pb§8]§r ")
                    }
                }
            }

            cached == null -> {
                if (key !in pendingFetches) {
                    pendingFetches.add(key)
                    scope.launch {
                        fetchSemaphore.withPermit {
                            ProfileUtils.getProfile(key)
                            pendingFetches.remove(key)
                        }
                    }
                }
                return "§7(Loading...)"
            }
        }

        return ""
    }

    private fun getColor(level: Int) = when {
        level >= 50 -> "§c§l"
        level >= 45 -> "§c"
        level >= 40 -> "§6"
        level >= 35 -> "§d"
        level >= 30 -> "§9"
        level >= 25 -> "§b"
        level >= 20 -> "§2"
        level >= 15 -> "§a"
        level >= 10 -> "§e"
        level >= 5 -> "§f"
        else -> "§7"
    }

    private fun formatTime(milliseconds: Number): String {
        val totalSecs = milliseconds.toLong() / 1000
        val m = (totalSecs % 3600) / 60
        val s = (totalSecs % 60).toString().padStart(2, '0')
        return "$m:$s"
    }
}