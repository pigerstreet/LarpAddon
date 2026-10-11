package com.github.noamm9.features.impl.dungeon

/// engineerClient's Better PF Online, reached by name so neither mod needs the other: `BetterPfApi` in its
/// `com.engineerclient.betterpf` package. Without engineerClient [present] is false and the Party Finder menu
/// shows no buttons for it. Every call is wrapped: a missing or renamed method does nothing.
internal object EcBetterPf {
    private val api: Class<*>? = runCatching { Class.forName("com.engineerclient.betterpf.BetterPfApi") }.getOrNull()

    val present: Boolean get() = api != null

    private fun call(name: String, vararg args: Any?): Any? = runCatching {
        api?.methods?.firstOrNull { it.name == name && it.parameterCount == args.size }?.invoke(null, *args)
    }.getOrNull()

    /// Better PF is switched on in engineerClient's panel.
    fun moduleOn(): Boolean = call("moduleOn") as? Boolean ?: false

    /// Online Link: engineerClient tells the site who you are, your party, where you are and your PBs.
    fun linked(): Boolean = call("onlineLinked") as? Boolean ?: false

    fun setLinked(on: Boolean) { call("setOnlineLinked", on) }

    /// The site's online page in the browser, signed in as you (turns the link on).
    fun openOnlinePage() { call("openOnlinePage") }

    /// Every saved run the site doesn't have yet, one at a time, progress in chat.
    fun uploadMissingRuns() { call("uploadMissingRuns") }
}
