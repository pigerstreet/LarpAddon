package com.github.noamm9.utils

import com.github.noamm9.NoammAddons.scope
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.*
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.ThreadUtils.scheduledTask
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.*
import kotlin.coroutines.resume

object ActionUtils: ISelfInit {
    private data class Action(val priority: Int, val blockInput: Boolean, val block: suspend () -> Unit): Comparable<Action> {
        override fun compareTo(other: Action) = other.priority.compareTo(this.priority)
    }

    private val actionQueue = PriorityBlockingQueue<Action>()
    @Volatile private var isBlocked = false
    private var processingJob: Job? = null
    private var running = false
    private val lock = Any()

    /**
     * @param priority The priority of the action (higher values executed first).
     * @param block The suspendable action to execute.
     */
    fun queue(priority: Int = 0, blockInput: Boolean = false, block: suspend () -> Unit) = synchronized(lock) {
        actionQueue.add(Action(priority, blockInput, block))
        if (running) return@synchronized
        running = true
        processingJob = scope.launch { run() }
    }

    /// fork: upstream moved `running = true` out of the guarded block above and into the top of this
    /// function, which only runs once the coroutine is actually dispatched. Between `queue` releasing
    /// the lock and that happening, a second `queue` still saw `running == false` and launched a second
    /// runner over the same queue - and `scope` is `Dispatchers.Default`, so the two drain it on
    /// different threads. AutoI4 has two producers that can hit that window (the `BlockChangeEvent`
    /// handler and the stall watchdog, which both queue a `shootAtBlock`), and the point of the queue is
    /// that those never overlap. It also left `processingJob` pointing at only the newer runner, so
    /// `reset` cancelled one and let the other keep going.
    ///
    /// The flag is set back under the lock, and cleared in the same critical section that finds the
    /// queue empty. Clearing it after the loop instead - as upstream did before this too - leaves a gap
    /// where a caller sees `running == true`, declines to launch, and its action then sits unclaimed.
    /// Upstream's `catch` and `isBlocked` handling are kept exactly as they are.
    private suspend fun run() {
        while (true) {
            val action = synchronized(lock) {
                actionQueue.poll().also { if (it == null) running = false }
            } ?: break

            if (action.blockInput) ThreadUtils.setTimeout(5000) { isBlocked = false }
            isBlocked = action.blockInput
            catch { action.block() }
            isBlocked = false
        }
    }

    fun reset() = catch {
        synchronized(lock) {
            actionQueue.clear()
            processingJob?.cancel()
            processingJob = null
            running = false
            isBlocked = false
        }
    }

    suspend fun waitTicks(ticks: Int = 0, cb: Runnable = {}) = suspendCancellableCoroutine {
        scheduledTask(ticks) {
            cb.run()
            it.resume(Unit)
        }
    }

    override fun init() {
        EventBus.register<WorldChangeEvent> { reset() }
        EventBus.register<MouseClickEvent> { if (isBlocked) event.cancel() }
        EventBus.register<KeyboardEvent.KeyPressed> { if (isBlocked) event.cancel() }
        EventBus.register<KeyboardEvent.CharTyped> { if (isBlocked) event.cancel() }
    }
}