package com.github.noamm9.mixin;

import com.github.noamm9.features.impl.dev.ModHider;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rewrites outgoing channel registrations in place rather than cancelling and re-sending them.
 * Fabric registers channels during the configuration phase, where there is no ClientPacketListener
 * to send a replacement through, so dropping the packet there would leave us with no channels at all.
 */
@Mixin(Connection.class)
public class MixinConnectionModHider {
    @ModifyVariable(method = "sendPacket", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> filterOutgoingPayload(Packet<?> packet) {
        return ModHider.rewriteOutgoing(packet);
    }
}
