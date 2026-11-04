package com.github.noamm9.mixin;

import com.github.noamm9.features.impl.misc.TimeChanger;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;

// fork: upstream's file is the 26.3 form behind stonecutter switches this build does not run; 26.2's getBaseColor returns int
@Mixin(AtmosphericFogEnvironment.class)
public class MixinAtmosphericFogEnvironment {
    @WrapMethod(method = "getBaseColor")
    private int onGetBaseColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, Operation<Integer> original) {
        TimeChanger.extractingSky = TimeChanger.INSTANCE.enabled;
        int color = original.call(level, camera, renderDistance, partialTicks);
        TimeChanger.extractingSky = false;
        return color;
    }
}
