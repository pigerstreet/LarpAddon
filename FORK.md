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
| `ui/hud/HudElement.kt` | 1 word: `scale` is `open`. 6 lines: an `open fun reset()` for the editor's Reset button. |
| `ui/hud/HudEditorScreen.kt` | Reset calls `HudElement::reset` instead of assigning x/y/scale inline. |

Notes for when one of those lines conflicts:

* `StorageOverlayScreen.Measurements.x/y` must come from `StorageOverlayHud.panelX/panelY`, and
  `playerX` must be relative to `x` so the player inventory travels with the panel.
* `StorageOverlayHud` mirrors the column/width/height formulas of `Measurements` for its editor
  preview. If upstream changes that layout, the preview drifts (cosmetic only) until it is updated.
* Reset in the hud editor puts every element at (20, 20), which for a whole menu is the top left
  corner. `StorageOverlayHud.reset()` sends it back to auto instead, which is why `HudElement.reset`
  has to stay open and stay the thing the editor calls.
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

### No mod prefix on the watcher speed alert

`BloodCamp` sends the Watcher speed to party chat as plain text instead of `"$PREFIX $title"`, so it
reads as a normal message rather than announcing the mod. One line, plus the now-unused `PREFIX` import.

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
