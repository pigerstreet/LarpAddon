package com.github.noamm9.mixin;

import com.github.noamm9.features.impl.misc.Camera;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    public void onBobHurt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (minecraft.options.damageTiltStrength().get() == 0) ci.cancel();
    }

    @ModifyVariable(method = "renderLevel", at = @At("STORE"), name = "nauseaIntensity")
    public float zeroNauseaIntensity(float nauseaIntensity) {
        return Camera.INSTANCE.enabled && Camera.getDisableNausea().getValue() ? 0F : nauseaIntensity;
    }

    // GETFIELD only: renderLevel also writes this field (it copies Options.bobView into the render state)
    @ModifyExpressionValue(method = {"renderLevel", "renderItemInHand"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;bobView:Z", opcode = Opcodes.GETFIELD))
    private boolean overrideBobView(boolean original) {
        return Camera.INSTANCE.enabled ? Camera.getViewBobbing().getValue() : original;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void scaleCameraBob(GameRenderer instance, CameraRenderState cameraState, PoseStack poseStack, Operation<Void> original) {
        noammaddons$bobView(instance, cameraState, poseStack, original, Camera.getBobScale(false));
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void scaleHandBob(GameRenderer instance, CameraRenderState cameraState, PoseStack poseStack, Operation<Void> original) {
        noammaddons$bobView(instance, cameraState, poseStack, original, Camera.getBobScale(true));
    }

    @Unique
    private static void noammaddons$bobView(GameRenderer instance, CameraRenderState cameraState, PoseStack poseStack, Operation<Void> original, float scale) {
        if (scale == 0F) return;
        float bob = cameraState.entityRenderState.bob;
        cameraState.entityRenderState.bob = bob * scale;
        try {
            original.call(instance, cameraState, poseStack);
        } finally {
            /// fork: restore the bob even if rendering throws, so the scaled value cannot leak into the next frame.
            cameraState.entityRenderState.bob = bob;
        }
    }
}