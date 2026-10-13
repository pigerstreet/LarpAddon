package com.github.noamm9.mixin;

import com.github.noamm9.NoammAddons;
import com.github.noamm9.utils.ChatUtils;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;

/**
 * Fork: a launcher such as Pandora cannot hand a link to the browser, so every link the game or a mod opens (chat
 * links, the confirm-link screen, "open in browser" buttons) goes to the clipboard instead, with a chat line saying so.
 * {@code openUri(String)} calls this one, so it is the single place links leave the game.
 */
@Mixin(Util.OS.class)
public class MixinUtilOS {
    @Inject(method = "openUri(Ljava/net/URI;)V", at = @At("HEAD"), cancellable = true)
    private void noammaddons$copyLinkInstead(URI uri, CallbackInfo ci) {
        String link = uri.toString();
        String host = uri.getHost() != null ? uri.getHost() : "link";
        NoammAddons.getMc().execute(() -> {
            NoammAddons.getMc().keyboardHandler.setClipboard(link);
            ChatUtils.INSTANCE.modMessage("§aLink copied to your clipboard§7 (" + host + ") - paste it in your browser.");
        });
        ci.cancel();
    }
}
