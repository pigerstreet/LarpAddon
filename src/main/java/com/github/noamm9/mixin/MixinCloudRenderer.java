package com.github.noamm9.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fork: endFrame() rotates the cloud uniform ring buffer, creating a GPU fence, every frame, including when clouds
 * are turned off and render() never ran (about 0.4% of the render thread in a 2-hour profile with clouds off).
 * Only rotate when this frame actually rendered clouds.
 */
@Mixin(CloudRenderer.class)
public class MixinCloudRenderer {
    @Unique private boolean noammaddons$renderedThisFrame = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void noammaddons$markRendered(CallbackInfo ci) {
        noammaddons$renderedThisFrame = true;
    }

    @Inject(method = "endFrame", at = @At("HEAD"), cancellable = true)
    private void noammaddons$skipUnusedFrame(CallbackInfo ci) {
        if (! noammaddons$renderedThisFrame) ci.cancel();
        noammaddons$renderedThisFrame = false;
    }
}
