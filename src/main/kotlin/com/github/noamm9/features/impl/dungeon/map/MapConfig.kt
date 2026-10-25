package com.github.noamm9.features.impl.dungeon.map

import com.github.noamm9.config.ConfigHolder
import com.github.noamm9.config.SettingProvider
import com.github.noamm9.config.types.*
import java.awt.Color

//#if LEGIT
//$import java.util.*
//#endif

object MapConfig: SettingProvider {
    override val configSettings = mutableSetOf<ConfigHolder<*>>()

    val mapEnabled by ToggleSetting("Map Enabled", true).section("Map")

    //#if CHEAT
    val dungeonMapCheater by ToggleSetting("Cheater Map", false)
    //#else
    //$val dungeonMapCheater by ToggleSetting("Legit", false).hidden().jsonName(UUID.randomUUID().toString())
    //#endif

    val mapExtraInfo by ToggleSetting("Show Extra Info Under Map", false)
    val mapHideInBoss by ToggleSetting("Hide In Boss", false)
    val printPlayersClearInfo by ToggleSetting("Player Clear Info", false)
    val sendClearInfoToParty by ToggleSetting("Clear Info To Party", true).withDescription("With Player Clear Info on, also sends each player's secrets and rooms cleared to party chat at the end of the run, one line per player.").showIf { printPlayersClearInfo.value }
    /// fork: the party gets one line instead of one per player — yours.
    val onlyMyClearInfoToParty by ToggleSetting("Only My Stats", false).withDescription("With Clear Info To Party on, sends only your own secrets and rooms to party chat, not one line for every player. The chat list still shows everyone.").showIf { printPlayersClearInfo.value && sendClearInfoToParty.value }
    val playerNames by DropdownSetting("Show Player Names", 0, listOf("Off", "Holding Leap", "Always"))
    val mapVanillaMarker by ToggleSetting("Vanilla Head Marker", false)

    val textScale by SliderSetting("Map Text Scale", 1f, 0.4f, 1.5f, 0.1).section("Size")
    val doorHeightSize by SliderSetting("Door Width", 6, 6, 10, 1)
    val checkmarkSize by SliderSetting("Map Checkmark Scale", 1f, 0.3f, 1.5f, 0.1f)
    val playerHeadScale by SliderSetting("Player Heads Scale", 1f, 0.3f, 1.5f, 0.1f)
    val playerNameScale by SliderSetting("Player Name Scale", 0.5f, 0.3f, 1.5f, 0.1f)

    val mapBackground by ColorSetting("Map Background Color", Color(255, 255, 255, 50), true)
    val mapBorderColor by ColorSetting("Map Border Color", Color(255, 255, 255), true)
    val mapBorderWidth by SliderSetting("Border Thickness", 1, 1, 5, 1)

    val dungeonMapCheckmarkStyle by DropdownSetting("Checkmark Style", 0, listOf("Checkmarks", "Secrets", "Room Name", "Room Name + Secrets", "Secrets + Puzzle Name")).section("Rooms")
    val centerStyle by ToggleSetting("Center Checkmark/Text", true).jsonName("Center Checkmark")
    val hideBloodroomOvelay by ToggleSetting("Hide text/checkmark of Blood room", false)
    val hideQuestionCheckmarks by ToggleSetting("Hide Unknown Room Checkmark", false).showIf { dungeonMapCheckmarkStyle.value == 0 }
    val limitRoomNameSize by ToggleSetting("Limit Room Name Size", true).showIf { dungeonMapCheckmarkStyle.value in 2 .. 4 }
    val predictRooms by ToggleSetting("Predict Unopened 1x1s", true).showIf { ! dungeonMapCheater.value }

    /// fork: ported from engineerClient's Room Prediction. Uses only what the map item shows and the tab list's
    /// secret total; off while Cheater Map is on.
    val roomPrediction by ToggleSetting("Room Prediction", true).withDescription("F7/M7: colours the unopened rooms by what they probably are (puzzle, trap, miniboss, blood, fairy), from what the map shows and the rules every recorded map follows. Only the map item's own information is used. Off while Cheater Map is on.").section("Room Prediction")
    val predictionSplitText by ToggleSetting("Split Contents", true).withDescription("On each \"?\" behind a door: what that side of the map holds (puzzles P, trap T, yellow Y, blood B; a letter shows when it is more likely there than not) and about how many rooms.").showIf { roomPrediction.value }
    val predictionHiddenHints by ToggleSetting("Hidden Tile Hints", true).withDescription("A small mark on unseen tiles further in where a special room is likely.").showIf { roomPrediction.value }
    val predictionShowChances by ToggleSetting("Show Chances", false).withDescription("The most likely special room's chance on each tile.").showIf { roomPrediction.value }
    val predictionMinChance by SliderSetting("Min Chance", 0.3f, 0.1f, 0.9f, 0.05f).withDescription("A room type shows on a tile only from this chance up.").showIf { roomPrediction.value }
    val predictionDeadEndChance by SliderSetting("Dead End Chance", 60, 0, 100, 1, "%").withDescription("How sure the prediction has to be that a tile is a puzzle, trap or miniboss (a 1x1 dead end) before it gets the guess tile. From 95% it always does.").showIf { roomPrediction.value }
    val predictionBigRoomChance by SliderSetting("Big Room Chance", 70, 0, 100, 1, "%").withDescription("How sure the prediction has to be of a big room's size and place before it is drawn greyed out at its whole size.").showIf { roomPrediction.value }
    val predictionOneByOneChance by SliderSetting("1x1 Room Chance", 70, 0, 100, 1, "%").withDescription("How sure the prediction has to be that a tile is a normal 1x1 room before it is drawn greyed out.").showIf { roomPrediction.value }
    val predictionExactRooms by ToggleSetting("Exact Last Rooms", true).withDescription("Once the secrets the tab list counts leave only one way for the last rooms to be (their size, place and name), they are drawn in full with their name and secrets.").showIf { roomPrediction.value }

    //#if CHEAT
    val mimicEsp by ToggleSetting("Mimic ESP")
    val mimicEspColor by ColorSetting("Mimic ESP Color", Color(255, 0, 0, 50), true).showIf { mimicEsp.value }
    val highlightMimicRoom by ToggleSetting("Highlight Mimic Room", true)
    //#endif

    val mapPlayerHeadColor by ColorSetting("Head Border", Color(0, 0, 0), true).section("Colors")
    val mapVanillaMarkerColor by ColorSetting("Vanilla Head Marker", Color(0, 255, 0), true).jsonName("Vanilla Head Marker Color")
    val mapPlayerHeadColorClassBased by ToggleSetting("Head Border Class Base", false)
    val mapPlayerNameClassColorBased by ToggleSetting("Player Names Class Base", false)

    //#if CHEAT
    val colorMimic by ColorSetting("Mimic Room", Color(255, 0, 0), true).showIf { highlightMimicRoom.value }
    //#endif

    val colorUnopened by ColorSetting("Unopened Room", Color(65, 65, 65), true)
    val colorBlood by ColorSetting("Blood Room", Color(178, 0, 0), true)
    val colorFairy by ColorSetting("Fairy Room", Color(227, 155, 226), true)
    val colorRare by ColorSetting("Rare Room", Color(178, 178, 178), true)
    val colorMiniboss by ColorSetting("Miniboss Room", Color(255, 200, 0), true)
    val colorPuzzle by ColorSetting("Puzzle Room", Color(123, 0, 123), true)
    val colorTrap by ColorSetting("Trap Room", Color(255, 130, 0), true)
    val colorRoom by ColorSetting("Normal Room", Color(121, 70, 0), true)
    val colorEntrance by ColorSetting("Entrance Room", Color(0, 255, 0), true)

    val colorUnopenedDoor by ColorSetting("Unopened Door", colorUnopened.value, true)
    val colorBloodDoor by ColorSetting("Blood Door", colorBlood.value, true)
    val colorWitherDoor by ColorSetting("Wither Door", Color(16, 16, 16), true)
    val colorRoomDoor by ColorSetting("Normal Door", colorRoom.value, true)
    val colorOpenWitherDoor by ColorSetting("Opened Wither Door", colorRoom.value, true)
    val colorEntranceDoor by ColorSetting("Entrance Door", colorEntrance.value, true)
}