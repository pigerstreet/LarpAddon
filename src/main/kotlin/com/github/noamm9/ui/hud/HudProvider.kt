package com.github.noamm9.ui.hud

interface HudProvider {
    val hudElements: MutableSet<HudElement>

    /// fork: upstream's hud refactor turned `.apply { x = ..; scale = .. }` into `defaults { .. }`,
    /// but the stored block is only ever invoked by the hud editor's Reset button - so an element the
    /// config has never seen starts at (20, 20) with scale 1 instead of where it says it belongs. That
    /// is the new M7 Ragnarock alert in the corner of the screen, the terminal progress off centre and
    /// a third of its size, the quiz timer at a third of its size and the autopet title at under half.
    /// Invoking the block here as well as storing it is exactly what the `.apply` it replaced did, at
    /// the same point in startup and with the same `Resolution` values, and Reset still works as
    /// upstream wrote it. A saved position is read after every feature has initialised, so it wins.
    infix fun HudElement.defaults(block: HudElement.() -> Unit) = apply {
        ::defaults.set(block)
        this.block()
    }
}