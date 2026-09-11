# Fork notes

This fork tracks [Noamm9/NoammAddons](https://github.com/Noamm9/NoammAddons). Everything below exists
so that pulling upstream updates stays a one-command job.

## Syncing with upstream

```sh
git sync            # fetch upstream + rebase the current branch onto it (autostashes local edits)
git push --force-with-lease origin 26.1.2
```

`git sync` is a local alias for `git fetch upstream && git rebase --autostash upstream/<current branch>`.
Rebasing (not merging) keeps the fork as "upstream + a short list of my commits", which is what makes
the next sync cheap. The force-push is expected: rebasing rewrites the commits of this fork only.

The `upstream` remote is push-disabled on purpose, so `git push upstream` cannot go anywhere.

## Customisations

Keep every change to upstream files as small as possible, and put real code in new files. A patch that
touches 6 lines rebases cleanly for years, one that reorganises a file conflicts on every sync.

### Movable storage overlay

Lets the storage overlay be positioned and scaled from the hud editor (`/na` -> hud editor) like any
other hud element, instead of being locked to the centre of the screen.

| File | Change |
| --- | --- |
| `features/impl/general/storageoverlay/StorageOverlayHud.kt` | **New.** The hud element: position, scale, and the editor preview. |
| `features/impl/general/storageoverlay/StorageOverlay.kt` | 1 line: registers the hud element. |
| `features/impl/general/storageoverlay/StorageOverlayScreen.kt` | 4 lines: `Measurements` anchors on the hud element, and the companion object is no longer private. |
| `ui/hud/HudElement.kt` | 1 word each: `x`, `y` and `scale` are `open`, so the element can back them with its own state. |

Notes for when one of those lines conflicts:

* `StorageOverlayScreen.Measurements.x/y` must come from `StorageOverlayHud.panelX/panelY`, and
  `playerX` must be relative to `x` so the player inventory travels with the panel.
* `StorageOverlayHud` mirrors the column/width/height formulas of `Measurements` for its editor
  preview. If upstream changes that layout, the preview drifts (cosmetic only) until it is updated.
* Reset in the hud editor puts every element at (20, 20), which for a whole menu is the top left
  corner. `StorageOverlayHud` sets upstream's `defaults` hook to send it back to auto instead. The
  editor assigns the default x/y/scale and *then* invokes `defaults`, so the hook has the last word.
* The overlay stays screen-centred until it is actually moved. `x`/`y` default to `-1f`, which means
  "auto", and opening the hud editor converts that into a real position.

### Tooltips scale with the storage overlay

An item tooltip inside the overlay is drawn at the overlay's own scale instead of full gui size.

| File | Change |
| --- | --- |
| `features/impl/general/storageoverlay/StorageOverlayTooltip.kt` | **New.** Works out the scale a tooltip should use. |
| `mixin/MixinGuiGraphicsExtractor.java` | The `tooltip` wrapper multiplies in `StorageOverlayTooltip.scale()`, and applies `ItemTooltip`'s own scale/scroll only while its scrolling is on. |
| `features/impl/general/storageoverlay/StorageOverlay.kt` | 1 line: the `Tooltip Scale` slider. |

The factor is `Resolution.scale * StorageOverlay.scaleSetting`, because the overlay is drawn inside
`Resolution`'s space *and* scaled again by its own setting, while vanilla draws tooltips in plain gui
space. If upstream reworks that mixin, keep the `storageScale` multiply and the `scrolling` guard.

### Faster inventory search

`InventorySearch.matches` walks the lore components directly instead of going through `ItemUtils.lore`.
`lore` maps every line through `formattedText`, so the old matcher built a formatted string for each
line of each stack only to strip the formatting straight back off. It runs per stack per frame - for
every rendered slot, and for every stack of every cached page while `Hide Non-Matching Pages` filters -
so on a full storage that was a few thousand throwaway strings a frame. Same results, no allocation.

### Cached storage pages are only matched once per query

`StorageOverlayScreen.visibleStorageData` runs once per frame from the render. With `Hide
Non-Matching Pages` on it re-matched every stack of every cached page - roughly a thousand items,
each with its whole lore walked - to recompute a verdict that only changes when the query or the
page does. The verdicts are now memoised in an `IdentityHashMap` keyed on the `NBTInventory`.

| File | Change |
| --- | --- |
| `features/impl/misc/InventorySearch.kt` | New `matchKey`, a string of everything `matches` depends on. |
| `.../storageoverlay/StorageOverlayScreen.kt` | `matchCacheKey` + `matchCache` fields and a `pageMatches` helper, used for the non-active pages. |

This is safe because `NBTInventory` is an immutable data class that `savePage` **replaces** rather
than edits, so a re-saved page is a new object and misses the cache. Note that `savePage` mutates the
`storageMenuData` map in place, so keying the cache on the map would have been wrong. The open page is
still matched live every frame against its real slots, since items move under you and it is only 45 of them.

### Fewer nbt copies for etherwarp

`EtherwarpHelper.getEtherwarpDistance` resolved `skyblockId` twice. Both `skyblockId` and `customData`
call `CustomData.copyTag()`, which deep copies the item nbt, and this runs every frame the overlay is
up. The id is now read once. Same results.

| File | Change |
| --- | --- |
| `utils/items/EtherwarpHelper.kt` | `getEtherwarpDistance` reads `skyblockId` into a local instead of resolving it twice. |

### Per-slot render handlers do less work

`ContainerEvent.Render.Slot.Pre`/`.Post` fire once per slot per frame, so anything a handler does
before it decides the slot is uninteresting is paid ~54 times a frame in every menu. Three handlers
were doing real work up front. Nothing here changes what gets drawn - the reordered checks are all
pure predicates, so the order they are tested in cannot change the outcome.

| File | Change |
| --- | --- |
| `features/impl/dungeon/SalvageOverlay.kt` | Checks reordered so `baseStatBoostPercentage` gates first. Only dungeon gear has it, so most stacks bail after one nbt read instead of also paying `skyblockId` (a second deep copy), a display name and the two lists `PlayerUtils.getArmor()` builds. |
| `features/impl/general/ProtectItem.kt` | `getProtectType` returns early when neither `protectStarred` nor `protectRarity` is on, so `customData` is not deep copied, and the display name is built inside the condition that needs it rather than always. The uuid and id branches now also require their protection list to be non-empty, which skips two more deep copies until something has actually been protected. |
| `features/impl/dungeon/ChestProfit.kt` | The screen title was rebuilt and both croesus regexes re-run for every slot. a `TitleInfo` cached against the title component identity carries all three, which is stable for the life of a screen. |
| `features/impl/dungeon/PartyFinder.kt` | Each of the 21 head slots rebuilt its lore list, stripped formatting off every line and ran two regexes over each, every frame. A `HeadInfo` memo keyed on the stack (weak keys, so it cannot outlive the menu) parses each head once. |
| `features/impl/dungeon/PartyFinder.kt` | The missing-class initials drawn on those heads were still rebuilt per head per frame out of `HeadInfo.classes` and five formatted constants, five strings stripped each time. They are folded into `HeadInfo` as `missingLines`, and the stripped class names are kept once as `classNamesPlain` (two other call sites were rebuilding them too). |
| `features/impl/dungeon/ChestProfit.kt` | Every head of the Croesus menu had its name component flattened and its whole lore list rebuilt out of nbt - a component flattened per line - every frame, to re-decide a highlight that cannot change while the menu is open. A `CroesusHead` memo, keyed on the stack the same way, reads each head once. It also returns early when both croesus toggles are off, which used to do all of that and then draw nothing. |

Worth knowing for future passes: a disabled `Feature` **unregisters its listeners**
(`Feature.onDisable`), so a handler with no `enabled` check is not running while the feature is off.
These only cost anything when the feature is actually on.

### Chat splits do not recompile their regexes

`RunSplits` matches every chat message in a dungeon against the start and end line of every split for
the floor, and `DialogueEntry.startMatches`/`endMatches` built a fresh `Regex` from the same string on
each call - roughly twenty `Pattern.compile` calls per message on M7, all thrown away again. The
strings come out of `runSplits.json` and never change, so each is compiled once and kept.

Worth knowing: of the 35 patterns in that file only F5's Livid line is written as a real regex. The
rest are literal chat lines, matched by the `==` that runs first; their regex was compiled every
message and could never have matched (`[BOSS]` is a character class, not the text `[BOSS]`).

| File | Change |
| --- | --- |
| `features/impl/visual/RunSplits.kt` | Two `by lazy` regex delegates in the `DialogueEntry` body. They are in the class body, not the constructor, so `equals`/`hashCode`/`copy` and the json decoding are unaffected. |

### Tooltips resolve the item id once

`ContainerEvent.Render.Tooltip` fires every frame an item tooltip is on screen, and four features
listen. `ItemUtils.skyblockId` deep copies the whole item nbt, flattens the display name, and for
several ids rebuilds the lore on top of that - and `ItemTooltip` asked for it twice per frame.

| File | Change |
| --- | --- |
| `features/impl/general/ItemTooltip.kt` | Resolves `skyblockId` into `itemId` once and hands it to both the market lookups and the npc sell lookup. |
| `utils/items/ItemUtils.kt` | `skyblockId` read `hoverName.unformattedText` at the top of the getter and threw it away for every item that has an id in its nbt - which is everything Hypixel hands out. It is only read on the branch for an item with no id at all, so it moved there. |

The original other half of this patch is gone: it used to split `marketId` into a `marketIdOf(id)` that
only copied the tag for the three ids that read it. Upstream deleted `marketId` outright in `9ecc6c94`,
folding book/rune/potion/pet/shard resolution into `skyblockId` itself with the tag read once into a
local - the same fix, arrived at independently. If a future sync brings `marketId` back, that split is
the shape to restore.

### Render handlers check their toggles before doing the work

`RenderOptimizer` listens to two of the busiest packets in the game and did the expensive part of each
before asking whether any setting wanted the answer.

Entity metadata arrives for every mob nametag and health tick. The handler scanned the packet's fields
for a `Component` and built a formatted string out of it, then used the result only if `Hide Healer
Orbs` was on. Equipment packets never stop in a dungeon, and the handler pulled the skull texture -
a profile lookup and a ~300 character base64 string - out of every equipped item of every entity,
then compared it against as many equally long constants. With those toggles off, none of it was ever
read.

Every condition involved is a pure predicate over the same values the original code tested, so the
reordering cannot change which entities get hidden. Upstream's `shouldDiscard` block is left exactly
as written; the guards sit above it.

| File | Change |
| --- | --- |
| `features/impl/visual/RenderOptimizer.kt` | `ClientboundSetEntityDataPacket` returns early unless `hideHealerOrbs` is on. `ClientboundSetEquipmentPacket` returns early unless some head or hand toggle is on, and skips a slot no toggle wants before reading its texture. |
| `features/impl/visual/MaskTimers.kt` | The invulnerability overlay took `maxByOrNull` over a list `filter` had just allocated, every frame. It takes the maximum directly and rejects it when not positive. |

`3aa4e039`'s parent `e906209a` added a name-string cache for `Hide 0 Health` and invalidates it from this
same metadata branch. That invalidation has to sit above the `Hide Healer Orbs` early return - a rebase
put it below, which with Healer Orbs off meant the cache was never cleared and a health bar first seen at
full was never seen reaching 0. If this section conflicts again, check that ordering first.

### The action queue keeps one runner

`ActionUtils.queue` serialises actions that move the player - swapping to a rod, changing a mask,
rotating and shooting. Upstream moved `running = true` out of the guarded block in `queue` and into the
top of `run`, which only executes once the coroutine is dispatched. In that window a second `queue`
still saw `running == false` and launched a second runner over the same queue, and `scope` is
`Dispatchers.Default`, so the two drained it on different threads.

AutoI4 has two producers that can land in that window - the `BlockChangeEvent` handler and the stall
watchdog, which both queue a `shootAtBlock` - and the whole point of the queue is that those never
overlap. `processingJob` also only tracked the newer runner, so `reset` cancelled one and left the
other running.

| File | Change |
| --- | --- |
| `utils/ActionUtils.kt` | `running` is set under the lock again, and cleared in the same critical section that finds the queue empty rather than after the loop - clearing it afterwards leaves a gap where a caller sees `running == true`, declines to launch, and its action sits unclaimed. Upstream's `catch` and `isBlocked` handling are untouched. |

### The dungeon scanner stops re-resolving the same chunk

`WorldUtils.getStateAt` resolves the chunk and re-runs the bobby class-name comparison on every call,
and the two column scanners in `ScanUtils` went through it for every block: `getCore` reads 129 blocks
and `getHighestY` up to 257, per tile of the 11x11 grid, four times a second while the scan is
unfinished. Grid cells that hold no room never stop being rescanned - an empty column reads as height
0, so the cell stays `Unknown` and is retried on every pass for the rest of the run.

The chunk is the same for a whole column, so it is resolved once per call and queried directly. An
unresolvable chunk still reads as air, which is what `getStateAt` returned for one, so results are
identical.

`findMimicRoom` was separate: it ran every tick for the whole of a floor 6 or 7 run until the mimic
turned up, and each call rebuilt a list of every block entity in render distance before doing a block
state lookup per entry. It is throttled to the same 250ms the tile scan already uses. Upstream has
since made that call cheat-only (`76dc9d2a`); the throttle sits inside that guard, so the legit
build returns before either of them.

These are modest savings rather than dramatic ones - the per-lookup cost is small, it is the
repetition that adds up - but they cost nothing in behaviour.

| File | Change |
| --- | --- |
| `utils/WorldUtils.kt` | `getLoadedChunk` is no longer private, so a caller walking a column can hoist it. |
| `utils/dungeons/map/utils/ScanUtils.kt` | `getCore` and `getHighestY` resolve the chunk once and index it directly. `getHighestY` returns 0 up front for an unloaded chunk, which is what the old air-reading loop produced. |
| `utils/dungeons/map/handlers/DungeonScanner.kt` | `findMimicRoom` throttled to 250ms with its own timer. |

### The update checker only checks on startup

Upstream ran an hourly `ThreadUtils.loop` in `UpdateChecker.init` that called `runCheck()` directly.
It looked at neither `enabled` nor `Check On Startup`. `Feature.initialize` calls `init` on every
feature whether it is toggled on or not, and the loop lives on the mod coroutine scope rather than in
the feature's listener set, so `onDisable` had nothing to unregister - the update notification kept
arriving every hour with the feature switched off.

| File | Change |
| --- | --- |
| `features/impl/dev/UpdateChecker.kt` | The hourly loop is gone, along with its now-unused `java.util.concurrent` import. The `GameStartEvent` check and the `Check For Updates` button are unchanged. |

`AutoGFS` starts a similar unguarded loop, but its `refill` opens with an `enabled` check, so it is
fine as it is. If a sync reintroduces the loop here, delete it again rather than gating it - the two
settings already describe the behaviour that is left.

### Events are not built for listeners that do not exist

`EventBus.post` returns immediately when nothing is listening, but it cannot say so until the event
object exists - and the two hottest callers build one per rendered entity per frame and per block
update. Every listener for both events belongs to a feature that ships disabled, so on a default config
all of that was allocated and dropped.

| File | Change |
| --- | --- |
| `event/EventBus.kt` | New `hasListeners(Class)`. `_unregisterListener` drops the map key when the last listener goes, so `containsKey` is exactly "would `post` do anything". |
| `mixin/LevelChunkMixin.java` | Asks first. This also skips the `getBlockState` call that read the old state out of the chunk - the expensive half - plus a `BlockPos` and the event. |
| `mixin/MixinEntityRenderDispatcher.java` | Asks first, after the vanilla return value check. |

Adding anything above the inline `register`/`listener` helpers in `EventBus.kt` shifts their line
numbers, so a jar diff will show every class that inlines them as changed. That is the `SourceDebugExtension`
line map only - after the change above, 125 of 130 changed classes had byte-identical instructions.

### Effective health counts defense below a hundred

`ActionBarParser.effectiveHP` was `currentHealth * (1 + currentDefense / 100)` with three `Int`s, so
the division truncated: the effective health on the player hud only ever counted defense in whole
hundreds. 850 defense multiplied as 8, and anything under 100 counted for nothing at all - at 99
defense the readout was understated by half.

| File | Change |
| --- | --- |
| `utils/ActionBarParser.kt` | The division is done in floating point and rounded once at the end. `roundToInt` was already imported. |

### The server brand is not re-lowercased for every packet

`LocationUtils.onHypixel` was `mc.player?.connection?.serverBrand()?.lowercase()?.contains("hypixel")`,
and `LocationUtils`' own `MainThreadPacketReceivedEvent.Post` handler reads it for **every packet the
client handles** - both packet mixins funnel through that event, so in a dungeon that is a few thousand
throwaway strings a second in the hottest path the mod has.

`serverBrand()` is a plain field read on the connection, so the same `String` instance comes back until
a new connection handles the brand plugin message. The result is computed once per instance and reused,
keyed on identity. Over a simulated 200k reads with the connection occasionally replaced, the lowercase
ran 208 times instead of 200,000 with the same answer every time.

| File | Change |
| --- | --- |
| `utils/location/LocationUtils.kt` | `onHypixel` became a getter over `brandCache`/`brandIsHypixel`. The cached expression is upstream's, unchanged. |

The two fields are read from the render thread as well (the debug hud), but a stale read only costs a
recompute, and neither a reference nor a boolean can tear, so no lock is needed.

### The Livid cache is checked the right way round

`LividSolver`'s tick handler meant to skip its entity scan while the right Livid is already cached, but
it asked for `currentLivid.isRemoved` rather than `! currentLivid.isRemoved`. That is backwards in both
directions: with a live cached Livid - the normal case for the whole fight - it rescanned every tick,
walking everything in `entitiesForRendering` through a sequence and a `filterIsInstance` twenty times a
second; and when the cached entity actually had been removed it took the early return and kept the dead
id instead of looking for the new one.

| File | Change |
| --- | --- |
| `features/impl/dungeon/solvers/LividSolver.kt` | One negation, in the `TickEvent.Start` guard. |

### The tab list survives a ping update

`TabListUtils` invalidated its cache on every `ClientboundPlayerInfoUpdatePacket`, and the server sends
one carrying nothing but a fresh ping for all ~80 players once a second. Each of those forced the next
`getTabList` to re-sort every player through the comparator and build a new display-name component for
each - and `DungeonListener` reads the list on that same packet, so in a dungeon it also re-ran two
regexes over all 80 lines.

Only three of the eight actions can change what `fetchTabList` produces. `getNameForDisplay` reads
`getTabListDisplayName` (`UPDATE_DISPLAY_NAME`), the team, and `profile.name`; `decorateName` reads
`getGameMode` (`UPDATE_GAME_MODE`); and the set of players is `onlinePlayers`, which is the whole
`playerInfoMap` rather than the listed subset, so only `ADD_PLAYER` grows it. Enumerating all 255
non-empty action sets, the fork skips the rebuild for 31 of them and none of those 31 can change the
output.

| File | Change |
| --- | --- |
| `utils/TabListUtils.kt` | Dirty only on `UPDATE_DISPLAY_NAME`, `ADD_PLAYER` or `UPDATE_GAME_MODE` - `EnumSet` lookups, so three bit tests. Also dirty on `ClientboundPlayerInfoRemovePacket`, which upstream never handled, so a player leaving used to leave a stale entry until something unrelated cleared it. |

Team membership arrives on `ClientboundSetPlayerTeamPacket` and is still not watched, as upstream did
not watch it either. Hypixel sends those constantly and every line here carries a tab list display name,
so the team only ever affects sort order - watching it would hand the saving straight back.

### The debug flag registry is safe across threads

`NoammAddons.debugFlags` overrides `contains` to record the flag in `availableDebugFlags`, so every flag
*check* is a write. Those checks come from several threads - `Event.isCanceled` consults one on every
cancellation, and the autoclicker, chat helpers and puzzle solvers read flags from coroutines on
`Dispatchers.Default` - so a plain `LinkedHashSet` was being mutated concurrently, which can corrupt its
table and leave a later read spinning. Same class of bug as the rarity cache below.

| File | Change |
| --- | --- |
| `NoammAddons.kt` | `availableDebugFlags` is a `ConcurrentHashMap.newKeySet()`. |

A `synchronizedSet` would not have been enough: the one reader is `/na debug`'s tab completion, which
iterates the set, and a synchronized wrapper's iterator still throws if another thread adds mid-walk.
The cost is insertion order in the completion list, which was arbitrary anyway.

### The rarity cache is safe across threads

`ItemRarity.rarityCache` was a bare `WeakHashMap`. `PartyFinder` runs up to five profile lookups at
once (it caps `pendingRequests` at 5), and each resolved `DungeonStats.magicalPower` walks a whole
talisman bag through `ItemUtils.getRarity`, so several threads could be writing that map at the same
time. Concurrent writes to a `HashMap`-family map can corrupt its table and leave a later read
spinning forever, which would hang whichever thread hit it. It is now wrapped in
`Collections.synchronizedMap`.

| File | Change |
| --- | --- |
| `utils/items/ItemRarity.kt` | `rarityCache` is a synchronized map, and typed as `MutableMap` so the wrapper fits. |

`getRarity` still reads and then writes without holding the lock across both. That is deliberate -
the only cost is two threads occasionally computing the same rarity and storing the same answer.

### The reload cosmetics button counts down from the right number

`Cosmetics.reload` allows a reload every `15_000`ms but built its "please wait another ..." message
from `150_000`, so the button said two and a half minutes when the real wait was fifteen seconds.
One digit, in `features/impl/dev/Cosmetics.kt`.

`formatTime` also returns an empty string under a second, so the final second read "Please wait another
 before reloading again." The call site falls back to "1s" there.

### The lobby regex does not run for every scoreboard tick

Hypixel draws its sidebar out of teams, so `ClientboundSetPlayerTeamPacket` arrives many times a
second in every lobby and every dungeon. `LocationUtils` flattened both components to strings, joined
and stripped them and ran `lobbyRegex` over the result on every one - to reassign a server id it
already had, and to test a dungeon flag that was already set.

| File | Change |
| --- | --- |
| `utils/location/LocationUtils.kt` | Returns before building the string once the id is known and the dungeon is detected, and only runs the regex while the id is still missing. |

Neither answer can change once it is known: `serverId` belongs to the server and `inDungeon` only
goes back to false on `WorldChangeEvent`, which resets both. A blank id does not count as known - the
capture group is `\w{0,6}`, which also matches nothing, so the id can come back empty before the
scoreboard has rendered and upstream leant on the next packet to correct it. The regex therefore
keeps running until it produces something, which is the behaviour upstream had.

### Teammate nametags stop being hidden when the feature stops hiding them

`TeammateESP` draws its own name above each teammate, so `MixinAvatarRenderer.shouldShowName` asks it
whether to suppress the vanilla one. The answer is memoised per entity id for the frame, and the map
was emptied by a `cache.clear()` sitting at the **bottom of the loop that draws the names** - so it
cleared once per teammate drawn, and not at all on a frame where that loop did not run. Turn `Show
Teammate Name` off, finish the run, or walk a floor solo, and every id cached while it was on stays
cached for the rest of the session. Entity ids are handed out per world and reused, so whatever
inherits one of them in the next lobby silently loses its nametag.

| File | Change |
| --- | --- |
| `features/impl/dungeon/TeammateESP.kt` | `cache.clear()` moved to the top of the `RenderWorldEvent` handler, ahead of both guards, so it runs once per frame whatever else happens. |
| `features/impl/dungeon/TeammateESP.kt` | The three toggle checks moved out of `cache.getOrPut` and in front of it. They answer the same for every entity, so caching them was pointless, and caching them is what let a `true` outlive the toggle that produced it. Only the teammate lookup is memoised now. |

No world-change reset is needed on top of this: `inDungeon` is now tested before the cache is
consulted, and the per-frame clear covers moving between dungeons. If a sync conflicts, the thing to
preserve is that the clear cannot be skipped by an early return and that the toggles are read live.

### Two lookups that could throw, and a regex compiled per tab entry

Small, unrelated, all in code the fork was already editing.

| File | Change |
| --- | --- |
| `features/impl/dungeon/PartyFinder.kt` | `classNames[classNames.map { .. }.indexOf(component1())]` throws `IndexOutOfBounds` the moment `Currently Selected: (.+)` captures anything but one of the five class names - and `(.+)` will capture whatever is on the line. `getOrNull` instead: not knowing the selected class only costs the tooltip its grey highlight. |
| `features/impl/dungeon/ChestProfit.kt` | `lore[lore.lastIndex - 3]` throws on any Croesus head with under four lore lines. Now `getOrNull`, inside `CroesusHead`. |
| `features/impl/dungeon/PartyFinder.kt` | The `pfs` argument filtered the tab list against `"^![A-Z]-[a-z]$".toRegex()` **inside** the filter lambda, compiling a fresh `Pattern` for each of the ~80 entries on every keystroke. Hoisted to `tabPlaceholderRegex`. |


### The scoreboard dirty check is not reflective

`ScoreboardUtils` is the one listener here that nothing can turn off - it is an `ISelfInit`, not a
`Feature` - and it ran on every packet the client receives.

| File | Change |
| --- | --- |
| `utils/ScoreboardUtils.kt` | The `Set<KClass>` and its `none { it.java.isInstance(...) }` are replaced by a five-branch `when (event.packet)`. Same five packet types, same answer, no iterator and no `KClass.java` hop. The set had no other reader, so it goes too. |

`features/impl/dev/ScoreboardLogger.kt` has the same construct and is deliberately left alone: it is a
`Feature`, so its listener is unregistered while it is off, and it ships disabled.

### The clock builds its formatter once

| File | Change |
| --- | --- |
| `features/impl/visual/InfoDisplay.kt` | `DateTimeFormatter.ofPattern` sat inside the hud lambda, so the clock parsed the pattern and built a whole formatter on every frame it drew, to print a string that changes once a second. Both shapes it can take are hoisted to fields. |

`DateTimeFormatter` is immutable, and `HH:mm`/`HH:mm:ss` hold only numeric fields, which render through
`DecimalStyle.STANDARD` regardless of locale - so nothing about the output depends on when the formatter
was built.

### The waypoint toggle track runs the right way

Upstream replaced `MathUtils.lerpColor(a, b, t)` with the `Color.lerp` extension across the ui, and in
`DungeonWaypointScreen` the two ends came out swapped.

| File | Change |
| --- | --- |
| `ui/gui/DungeonWaypointScreen.kt` | 1 line: the toggle track lerps grey -> accent again. `switchAnim` runs to 1 while the toggle is *on*, so as upstream left it an enabled waypoint toggle read grey and a disabled one read accent. The `withAlpha(200)` upstream added on the same line is a genuine fix - `lerpColor` returns an opaque `Color` - and is kept. |

### Croesus prices pets with the id the rest of the file builds

`9ecc6c94` taught the Croesus preview to price pets, but its id builder is the only one in the file that
does not underscore the spaces in a name.

| File | Change |
| --- | --- |
| `features/impl/dungeon/ChestProfit.kt` | 1 call: `.replace(" ", "_")` on the pet name in `getIdFromName`, matching the shard branch one line above it, `enchantNameToID`, and `skyblockId`'s own pet branch. |

The opened-chest path prices through `skyblockId`, which builds `PET-${petInfo.type}-${tier}` from nbt,
and Hypixel's pet types are underscored. The Croesus preview built `PET-GOLDEN DRAGON-LEGENDARY`, missed
both price maps and scored the pet at 0. Checked against a table of 14 pets: 7 were mispriced, every one
of them multi-word, which is most of the expensive ones - Golden Dragon, Ender Dragon, Black Cat, Blue
Whale. After the change, 0.

### The lore name toggle shows names in lore

`3c2b7768` added a `Show Name in Lore` toggle to `Cosmetics`, defaulting to on, and wired it into the
tooltip mixin the wrong way round.

| File | Change |
| --- | --- |
| `mixin/MixinGuiGraphicsExtractor.java` | The condition raising `TextReplacer.drawingTooltip` is negated. |

`drawingTooltip` has exactly one reader - `MixinFont.noammaddons$shouldReplace` - and that reader
*negates* it, so raising the flag has always meant "do not replace names here". Gating the raise on the
new toggle being **on** therefore made it do the opposite of its label: on hid cosmetic names in lore,
off showed them.

Note that this changes the default. Before `3c2b7768` tooltips never replaced names at all; upstream's
intent, read from the label and the `true` default, is that they now should, and this patch delivers
that. If a sync conflicts here, check first whether upstream has renamed the flag or moved the negation
into `MixinFont` - if the polarity has been fixed on their side, drop this patch rather than merging it.

### The render batches are not rebuilt every frame

`RenderBatcher.flush` ended by clearing the two batch maps, so the batch objects themselves were thrown
away every frame. The next frame rebuilt one per pipeline, each with a fresh `ArrayList` that then had
to grow from capacity 10 back up to the few hundred vertices a dungeon frame puts in it.

| File | Change |
| --- | --- |
| `utils/render/world/RenderBatcher.kt` | Each batch's `data` is emptied in place after it draws, and the two `filledBatches.clear()`/`lineBatches.clear()` calls are gone. The empty check moves onto the batch, because with the maps retained they stop being empty after the first box. The font renderer is also hoisted out of the text loop. |

An outlined box is 12 edges at 2 vertices each, and `Box3D` draws one per glowing entity - so 30 boxes
is 720 elements. Modelling `ArrayList`'s growth policy, reaching 720 costs 12 array allocations and
2,456 reference slots copied, per batch, per frame; at 150 fps that is 1,800 array allocations and
1.4 MB/s of copying that now happens once and never again. `NoammRenderPipelines` has six pipelines in
total, so keeping the batches bounds the maps exactly as tightly as clearing them did.

The larger cost in this file is untouched on purpose: `FilledBatch.vertex` and `LineBatch.vertex`
allocate an immutable record per vertex, so those same 30 boxes are 720 short-lived objects a frame,
~108,000 a second. Pooling them means making the records mutable and tracking a fill count across
`FilledBatch.kt`, `LineBatch.kt` and both loops here - three upstream files, one of which upstream edits
regularly. That is a much worse trade against keeping this fork easy to sync, so it is left alone.

### Two more lore reads that could throw

Both are the shape of the Croesus read fixed further up: a constant index into a lore list whose length
nothing checked.

| File | Change |
| --- | --- |
| `utils/items/ItemUtils.kt` | `skyblockId`'s `ENCHANTED_BOOK` branch reads `lore[0]` and `lore[2]`. They become `getOrNull`, falling back to the raw id. `skyblockId` is read from twenty-odd call sites, several of them per frame while a tooltip or menu is open, so a throw here loses a whole render rather than one id. |
| `features/impl/dungeon/PartyFinder.kt` | The Party Finder menu check reads `lore[5]` off the slot-50 nether star. `getOrNull` leaves the menu unrecognised instead, which is what an absent star already did. Thrown inside a container-open handler, the old version lost the rest of the handler with it. |

Checked by simulating both against the originals: the book branch over all 1,365 lore shapes up to five
lines drawn from the four strings it distinguishes, and the star over every lore length 0-8. No
behavioural difference in any case where the original returned; the only divergences are the 6 shapes
each where it threw.

### Fixed-point numbers print the way they are asked to

`NumbersUtils.toFixed` rounded through `roundToInt` and rebuilt the result from `Double.toString`.

| File | Change |
| --- | --- |
| `utils/NumbersUtils.kt` | `toFixed` rounds with `roundToLong` and cuts the digits from the rounded Long. The now-unused `roundToInt` import is swapped for `abs` and `roundToLong`. |

That fixes three things. `toFixed(0)` could not print zero decimals and returned "30.0" - which is what
every Float or Double slider with a whole-number step shows as its value in the gui (7 of them: Check Delay,
Rarity Opacity, Clicks Per Second, Blink Duration, Rotation X/Y/Z). `Double.toString` switches to scientific
notation at 1e7, so 12345678.9 printed as "1.23456789E7". And `roundToInt` clamps, so anything past about
2.1e9 / 10^precision printed as 21474836.47.

Checked on the JVM against a port of the original over 8,000,000 values at precision 0-3, including exact
.5 ties: 5,905,868 identical, and every difference is one of the two bugs - 2,000,000 precision-0 cases that
lost the ".0" and 94,132 that had overflowed. No other output changed.

### Dragons out of render distance can be seen dying on the scoreboard

When a Wither dragon's entity is unloaded, `WitherDragons` asks `DragonCheck.isAliveOnScoreboard` every
server tick and marks it dead after a grace period if the answer is no. Part of that answer is
`healthRegex.find(line)?.value != "0"`, but the pattern required a b/m/k suffix, which a bare 0 never has.

| File | Change |
| --- | --- |
| `features/impl/floor7/dragons/DragonCheck.kt` | 1 character: the suffix in `healthRegex` is optional. |

With the suffix mandatory the match could never be "0", so the clause was always true and a dragon whose
line read 0 kept counting as alive. Checked on the JVM: `1.2M`, `450k`, `0.5M` and `10M` match exactly as
before and stay alive; `Ice Dragon 0` now reads dead; a line with no number still reads alive, as it did.
This only makes the author's existing check reachable - if Hypixel never prints a bare 0 there, nothing
changes.

### Glow highlights work behind walls with EntityCulling installed

Every highlight in the mod is a glow, decided in `Minecraft.shouldEntityAppearGlowing`. Box3D turns glow into
boxes by cancelling `CheckEntityGlowEvent`, and a cancel makes that method answer `false` so the vanilla outline
isn't drawn on top. EntityCulling asks the same method from its own `CullThread` (`CullTask.cullEntities`) and
deliberately never culls an entity that answers `true` - so with Box3D on, every ESP target behind a wall was
told "not glowing", culled, never extracted, and never got its box. Star mobs, the right Livid, blazes, withers,
Thorn, spirit bears, dragons and teammates all only lit up once in view, while mods that draw boxes straight
from their own id lists (OdinClient's Highlight) showed them at once.

| File | Change |
| --- | --- |
| `mixin/MixinMinecraft.java` | Cheat branch, 1 line: a cancelled glow returns `false` only on the client thread and `true` to any other caller. The render thread still suppresses the outline; EntityCulling gets the truth and keeps the target un-culled, which also keeps it ticking. |
| `features/impl/dungeon/StarMobESP.kt` | `checkStarMob` only records a nametag in `checked` once a mob was found. It used to record it first, so a mob not yet loaded on the first metadata packet was never looked for again. |
| `features/impl/dungeon/StarMobESP.kt` | The fallback search box is `expandTowards(0, -2, 0)` instead of `move(0, -1, 0)`. Nametag stands are markers with `EntityDimensions.fixed(0, 0)`, so the old box was a single point one block below the nametag; the new one contains that point. |

With Box3D off this already worked, because an uncancelled glow answers `true` everywhere. `1.2.7-29` shipped
a workaround instead - a star-mob-only tick handler forcing EntityCulling visibility through reflection, plus a
`ModCompatibility` helper - and both were removed when the real cause was found. If a sync conflicts on this
line, the rule is only that the render thread must keep getting `false` for a cancelled glow.
### The legit glow check raycasts only when something would glow

The legit build refuses glow to entities out of line of sight, and it checked that first - a
`hasLineOfSight` raycast to every entity being drawn, every frame, before knowing whether any feature wanted it
to glow. In a busy lobby that is thousands of block raycasts a second for nothing.

| File | Change |
| --- | --- |
| `mixin/MixinMinecraft.java` | Legit branch only: `CheckEntityGlowEvent` is posted first, and the invisibility and line-of-sight checks run only for an entity that would glow. A glow refused there also sets the glow flag false. The cheat branch is unchanged. |

Same answers as before for every entity: one that would not glow returns the vanilla value either way, one in
sight is unaffected, and one out of sight still returns the vanilla value. The difference is that the refused
case now clears the flag - before, it returned without touching it, so Box3D kept whatever it had been set to
the last frame the entity was in sight.

### Leap Counter reads a walking teammate's position once

`LeapCounter` works out where a teammate is from every movement packet, on `MainThreadPacketReceivedEvent.Post`.
For a relative move it decoded the packet's delta against the entity's position codec - but Post fires after
`ClientPacketListener.handleMoveEntity`, which has already decoded that delta and called `setBase` with the
result, so decoding again added the move a second time.

| File | Change |
| --- | --- |
| `features/impl/floor7/LeapCounter.kt` | 1 line: the relative-move branch reads `positionCodec.base`, the exact position the packet just moved the entity to. |

Leaps themselves arrive as teleport or position-sync packets, which were never affected. The double-applied
position only mattered for a teammate walking across the edge of a leap region, where it could count a
teammate who hadn't arrived or miss one who had.

### Star Mob ESP can draw its own boxes, like OdinClient

Any glow - vanilla outline or Box3D's box, which reads the glow flag - only exists while Minecraft is drawing
that entity, so every renderer that skips a mob (render distance, EntityCulling, Sodium, entity view distance
mods) took its highlight with it. OdinClient's Highlight never had this problem because it draws boxes straight
from its own list of starred entity ids.

| File | Change |
| --- | --- |
| `features/impl/dungeon/StarMobESP.kt` | Cheat-only `Box ESP` toggle (on by default) and `Box Style` dropdown. A `RenderWorldEvent` handler draws a through-walls box for every id in `starMobs`, plus bats and fels while their toggles are on. |
| `features/impl/dungeon/StarMobESP.kt` | With `Box ESP` on, the glow listener still sets the colour but cancels the event and clears the entity's glow flag, the way Box3D does. The render thread then draws no outline and Box3D draws no second box, while EntityCulling's cull thread still hears "glowing" (see the section above) and keeps the mob un-culled, so it keeps moving smoothly. |

The legit build is untouched: it keeps the line-of-sight glow, which must not show through walls. With `Box ESP`
off the feature behaves exactly as upstream's. If upstream edits this feature, the patch is the two settings, the
cancel block in the glow listener, and the render handler - all inside `//#if CHEAT`.

### Puzzle solver click colours each save to their own key

A setting is saved under its `jsonName`, which defaults to its display name, and `PuzzleSolvers` has three
`ColorSetting`s all named "Click Color" - Boulder, Water Board and Ice Fill. `ConfigManager.save` writes them
into one JSON object, so the last (Ice Fill) overwrote the other two; `read` then hands the value to
`getSettingByName`, whose `find` returns the first (Boulder). So every restart gave Boulder Ice Fill's colour,
and neither Water Board's nor Ice Fill's own colour ever persisted.

| File | Change |
| --- | --- |
| `features/impl/dungeon/solvers/PuzzleSolvers.kt` | `.jsonName("Boulder Click Color")` and `.jsonName("Water Board Click Color")`. Ice Fill keeps "Click Color", so the value already saved there - which was Ice Fill's - loads into Ice Fill. |

The display names are unchanged. A scan of every feature for other colliding keys found none (Map Config's two
"Vanilla Head Marker" settings already had distinct `jsonName`s).

### Nothing in chat says [NA]

| File | Change |
| --- | --- |
| `NoammAddons.kt` | `PREFIX` is a getter returning a fresh empty `Component`. This is the safety net: any use upstream adds later prints nothing. |
| `utils/ChatUtils.kt` | `modMessage` and `clickableChat` no longer prepend it (which would leave a stray space). |
| `utils/dungeons/map/handlers/ClearInfoUpdater.kt` | Same, on the end of run clear info line. |

The Watcher speed alert in `BloodCamp` was the only one other players could see, and upstream took
the prefix off that one itself in `d81c0a60`, so the fork no longer touches that file. The rest are
client side, so this is about screenshots rather than leaks. If a sync conflicts, `PREFIX` alone
covers it - the call sites are only edited so messages do not start with a stray space. Upstream
turned `PREFIX` from a `String` into a `Component` in `d81c0a60` (the legit build gets a different,
less obvious prefix); an empty `Component` keeps every upstream call site compiling as written. It is
a getter rather than a stored value on purpose - `Component.empty()` is a `MutableComponent`, and a
single upstream `PREFIX.append(..)` that forgot the `copy()` would append into the shared instance
and every later message would carry everything printed before it.

### Removed the rat overlay

Upstream ships a joke that, roughly once every few days of playtime, blits a full-screen image over
the game for three seconds. The code is deliberately written to look like a session-id stealer
(`AutoSessionIdStealer.stealBrowserCookies`, variables named `OAUTH_TOKENS`, `BLOCKCHAIN_GRABBER`);
it does none of that, it only downloads `bigrat.monster/media/bigrat.jpg`. It is still removed here.

| File | Change |
| --- | --- |
| `init/AutoSessionIdStealer.kt` | **Deleted.** |
| `event/impl/RatEvent.kt` | **Deleted.** |
| `NoammAddons.kt` | 5 lines removed from `onInitializeClient` plus 3 now-unused imports. |

If a sync conflicts here, upstream touched the joke again: delete whatever it added and drop the
calls back out of `onInitializeClient`.

### The cheat jar is named na.jar

`build.gradle.kts` sets `archiveFileName` on `jarCheat`, so it builds as `na.jar` instead of
`NoammAddons-<version>-<mc>-cheat.jar`. The legit jar keeps upstream's name.
