package com.github.noamm9

/**
 * Recording mode: a mod that records the world for later playback (engineerClient's Dungeon Recorder) sets
 * [recording] by reflection while its recording is being written — see FORK.md, "Recording mode".
 *
 * While it is on, what this mod does that a replay would show as anything but vanilla is left out: the
 * client-side block edits ([com.github.noamm9.utils.WorldUtils.setBlockAt] — the P2 pillars' glass, the S3 door
 * rotations, the Three Weirdos chests) do nothing, so the recording holds the server's blocks, and the scripted
 * swaps in [com.github.noamm9.features.impl.floor7.devices.AutoI4] are delayed by its Recording Delay, so the
 * recorded timeline does not fire on the tick the boss timer allows.
 *
 * Nothing is switched off: the features still run and the game plays as it always does. Off when no recorder sets it.
 */
object RecordingApi {
    @JvmField
    @Volatile
    var recording = false
}
