package com.github.noamm9.mixin;

import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Fork: endFrame() rotates the ring buffer, and every rotate creates a GPU fence (glFenceSync), even in a frame
 * where nothing was written. Several storages are cleared every frame but rarely written (Odin's rounded rects,
 * owo's blur, SkyHanni's chroma), and a 2-hour spark profile put those empty rotates at about 1.2% of the render
 * thread. A storage with nothing written this frame has nothing for the GPU to read, so skipping the rotate is safe:
 * the next write still waits on the slot's own fence through MappableRingBuffer.currentBuffer().
 */
@Mixin(DynamicUniformStorage.class)
public class MixinDynamicUniformStorage {
    @Shadow private int nextBlock;
    @Shadow @Final private List<MappableRingBuffer> oldBuffers;

    @Inject(method = "endFrame", at = @At("HEAD"), cancellable = true)
    private void noammaddons$skipEmptyFrame(CallbackInfo ci) {
        if (nextBlock == 0 && oldBuffers.isEmpty()) ci.cancel();
    }
}
