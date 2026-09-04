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
listen. `ItemUtils.customData` deep copies the whole nbt on every read, and `skyblockId` goes through
it, so a hovered item was being copied about six times a frame. Two of those were `ItemTooltip`
resolving the same id twice, one of them inside `marketId`, which resolved the tag a second time of
its own.

| File | Change |
| --- | --- |
| `utils/items/ItemUtils.kt` | `marketId` is now a one-liner over a new `marketIdOf(id)`. Only the three ids that read the tag copy it, so an ordinary item pays one copy instead of two. `marketId` itself is kept so upstream call sites still work. |
| `features/impl/general/ItemTooltip.kt` | Resolves `skyblockId` into `sbId` once and hands it to both `marketIdOf` and the npc sell lookup. |

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
state lookup per entry. It is throttled to the same 250ms the tile scan already uses.

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

### Text replacement bails out before it allocates

`MixinFont` routes `Font.width` and `Font.prepareText` - both String and `FormattedCharSequence`
overloads, plus `width(FormattedText)` - through `TextReplacer`, so the cosmetic name replacer sees
every string the game measures or draws. That is thousands of calls a frame in a menu, and `Cosmetics`
ships `toggled = true` with `Show Custom Names` on, so it is live on a default config.

The keys are the cosmetic users' names fetched from `api.noamm.org`, so on a normal client essentially
nothing ever matches. `replaceString` at least had an lru cache in `TextReplacer`; `replaceComponent`
and `replaceCharSequence` had none and rebuilt the whole component or sequence on every call - two
scratch arrays, a `Style` list one entry per codepoint, then a `StringBuilder`, a `Component.literal`
and an `append` per style run - only to throw the result away as an `int` width.

Each of the three now runs the same automaton over its input first, building nothing, and returns the
input untouched if no output fires. That is exact rather than approximate: the real loop takes the
identical transitions, so if nothing fires in the pre-pass nothing fires in the real pass either, and
with no output the old code simply reassembled its input. A hit that an overwrite blocker would have
vetoed just falls through to the full path, which decides it exactly as before.

| File | Change |
| --- | --- |
| `features/impl/dev/text/AhoCorasick.kt` | Three `mightMatch` overloads, and a one-line guard at the top of each `replace*`. The bodies below are untouched. |

Checked against a port of the upstream body over 240,000 inputs and 4,000 randomly generated key sets,
including overwrite blockers and surrogate pairs: no output differed, and the pre-pass skipped 70% even
with keys drawn from a 16-character alphabet.

`replaceCharSequence` consumes the input sequence once more than it used to, but only on the rare hit,
and Minecraft's sequences are re-consumable by design. On a miss the count is unchanged, and returning
the original is strictly more faithful than the old rebuild, which flattened every character to
position 0.

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

### Nothing in chat says [NA]

| File | Change |
| --- | --- |
| `NoammAddons.kt` | `PREFIX` is an empty `Component`. This is the safety net: any use upstream adds later prints nothing. |
| `utils/ChatUtils.kt` | `modMessage` and `clickableChat` no longer prepend it (which would leave a stray space). |
| `utils/dungeons/map/handlers/ClearInfoUpdater.kt` | Same, on the end of run clear info line. |
| `features/impl/dungeon/BloodCamp.kt` | The Watcher speed goes to party chat as plain text. |

`BloodCamp` was the only one other players could see; the rest are client side, so this is about
screenshots rather than leaks. If a sync conflicts, the constant alone covers it - the call sites are
only there so messages do not start with a space.

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
