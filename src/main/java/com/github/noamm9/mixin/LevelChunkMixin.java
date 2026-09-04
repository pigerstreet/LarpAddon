package com.github.noamm9.mixin;

import com.github.noamm9.event.EventBus;
import com.github.noamm9.event.impl.BlockChangeEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void onBlockChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        // fork: this fires for every block update the client applies, and read the old state out of the
        // chunk before asking whether anything wanted the event. All six BlockChangeEvent listeners belong
        // to features that ship disabled, so with none of them on this was a chunk lookup, a BlockPos and
        // an event object per block, thrown away inside post().
        if (! EventBus.hasListeners(BlockChangeEvent.class)) return;

        BlockState old = getBlockState(pos);
        if (old == state) return;

        EventBus.post(new BlockChangeEvent(pos.immutable(), state, old));
    }
}