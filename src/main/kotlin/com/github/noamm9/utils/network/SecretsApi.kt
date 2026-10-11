package com.github.noamm9.utils.network

import com.github.noamm9.NoammAddons
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/// fork: this mod's secrets lookup, for Odin's Secrets Counter (the owner's overlay) to fall back on when Odin's own
/// lookup fails and would print N/A. Odin reaches it by reflection, so the class name, `secrets` and its signature must
/// not change without changing Odin's SecretsCounter.kt overlay hunk too.
object SecretsApi {
    /** [name]'s total secrets, through [ProfileUtils.getSecrets]; null when the lookup fails. */
    @JvmStatic
    fun secrets(name: String): CompletableFuture<Long?> {
        val result = CompletableFuture<Long?>()
        NoammAddons.scope.launch {
            result.complete(runCatching { ProfileUtils.getSecrets(name).getOrNull() }.getOrNull())
        }
        return result
    }
}
