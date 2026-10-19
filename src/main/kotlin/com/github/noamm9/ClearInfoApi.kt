package com.github.noamm9

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Who sends the end-of-run secrets lines to party chat.
 *
 * Two mods can: this one's "Clear Info To Party" ([com.github.noamm9.utils.dungeons.map.handlers.ClearInfoUpdater])
 * and engineerClient's Clear Breakdown, which works out the same total secrets and rooms from the same Hypixel API.
 * Left alone they race — both are ready about a second and a half after the run ends, and whichever's API lookups
 * come back first sends, so the party sometimes sees this fork's lines and sometimes EC's, and sometimes both.
 *
 * [claim] settles it: the first caller of a run wins and sends, and the other prints locally only. Both send the
 * same wording ("Name: 12 secrets, 3-5 rooms"), so the party cannot tell which client posted.
 *
 * [reset] starts a run: this mod calls it from the run-start event, engineerClient from its own run start. Each
 * side resets, so the claim is fresh with either mod alone on the instance.
 *
 * See FORK.md, "Clear info to party: one sender".
 */
object ClearInfoApi {
    private val state = AtomicBoolean(false)

    /** True for exactly one caller per run: the winner sends the party lines. */
    @JvmStatic
    fun claim(): Boolean = state.compareAndSet(false, true)

    /** A new run. */
    @JvmStatic
    fun reset() {
        state.set(false)
    }
}
