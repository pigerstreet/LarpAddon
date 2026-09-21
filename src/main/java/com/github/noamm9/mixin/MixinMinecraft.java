package com.github.noamm9.mixin;

import com.github.noamm9.event.EventBus;
import com.github.noamm9.event.impl.CheckEntityGlowEvent;
import com.github.noamm9.event.impl.PlayerInteractEvent;
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlay;
import com.github.noamm9.features.impl.visual.InfoDisplay;
import com.github.noamm9.interfaces.IGlowingEntity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//#if LEGIT
//$import com.github.noamm9.utils.render.LegitEntityVisibility;
//#endif

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow @Nullable public Screen screen;
    @Shadow @Nullable public HitResult hitResult;
    @Shadow public LocalPlayer player;
    @Shadow @Nullable public ClientLevel level;
    @Shadow @Final private User user;
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        InfoDisplay.addLeftClick();
    }

    @Inject(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startUseItem()V",
            ordinal = 0
        )
    )
    private void onUseClick(CallbackInfo ci) {
        InfoDisplay.addRightClick();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void preUseItem(CallbackInfo ci) {
        handleHitResult(ci, false);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void preAttack(CallbackInfoReturnable<Boolean> cir) {
        handleHitResult(cir, true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void preWhileAttack(boolean down, CallbackInfo ci) {
        if (! down) return;
        handleHitResult(ci, true);
    }

    @Unique
    private void handleHitResult(CallbackInfo ci, boolean isLeftClick) {
        if (this.player == null || this.level == null) return;
        ItemStack itemStack = player.getMainHandItem();

        PlayerInteractEvent event;

        if (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS) {
            event = isLeftClick
                ? new PlayerInteractEvent.LEFT_CLICK.AIR(itemStack)
                : new PlayerInteractEvent.RIGHT_CLICK.AIR(itemStack);
        } else {
            event = switch (this.hitResult.getType()) {
                case ENTITY -> {
                    Entity entity = ((EntityHitResult) this.hitResult).getEntity();
                    yield isLeftClick
                        ? new PlayerInteractEvent.LEFT_CLICK.ENTITY(itemStack, entity)
                        : new PlayerInteractEvent.RIGHT_CLICK.ENTITY(itemStack, entity);
                }
                case BLOCK -> {
                    BlockPos pos = ((BlockHitResult) this.hitResult).getBlockPos();
                    yield isLeftClick
                        ? new PlayerInteractEvent.LEFT_CLICK.BLOCK(itemStack, pos)
                        : new PlayerInteractEvent.RIGHT_CLICK.BLOCK(itemStack, pos);
                }
                default -> isLeftClick
                    ? new PlayerInteractEvent.LEFT_CLICK.AIR(itemStack)
                    : new PlayerInteractEvent.RIGHT_CLICK.AIR(itemStack);
            };
        }

        if (EventBus.post(event)) ci.cancel();
    }

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void onSetScreen(Screen screen, CallbackInfo ci, @Local(argsOnly = true) LocalRef<Screen> screenRef) {
        if (! StorageOverlay.INSTANCE.enabled) return;
        var newScreen = StorageOverlay.onScreenChange(this.screen, screen);
        if (newScreen != null) screenRef.set(newScreen);
    }

    // Apply our glow after other mods have changed the vanilla glow state
    @ModifyExpressionValue(
        method = "shouldEntityAppearGlowing",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isCurrentlyGlowing()Z")
    )
    private boolean onShouldEntityAppearGlowing(boolean original, Entity entity) {
        //#if LEGIT
        // fork: upstream added a cached visibility check at THIS point, still above the event. The
        // fork had already deleted that check from here and moved it below, so that only an entity
        // something actually wants to glow is ever tested - which is the same cost upstream's cache
        // exists to reduce. Both are kept rather than picking one: the null guard stays here, and
        // the test below now calls upstream's cached helper instead of raycasting directly.
        //$if (this.player == null) return original;
        //#endif

        // fork: the legit build used to raycast line of sight to every entity being drawn, every frame, before it
        // knew whether anything wanted that entity to glow - thousands of raycasts a second in a busy lobby. It now
        // asks the event first and only raycasts for an entity that would glow. A glow refused for being out of
        // sight also clears the flag, which Box3D would otherwise keep drawing from the last frame it passed.
        // The cheat build has no sight check and is unchanged.
        // fork: the event object was allocated per entity per frame before finding out whether
        // anything was listening. All nine CheckEntityGlowEvent listeners belong to features that
        // can be off, and in a lobby none of them are. Same guard as MixinEntityRenderDispatcher.
        if (! EventBus.hasListeners(CheckEntityGlowEvent.class)) return original;
        var event = new CheckEntityGlowEvent(entity);
        //#if CHEAT
        // fork: a cancel here means Box3D wants this entity lit but draws it as a box, so the vanilla outline is
        // refused by answering false. That answer was also given to EntityCulling, whose cull task runs on its own
        // CullThread and deliberately never culls an entity this method says is glowing - so with Box3D on, every
        // ESP target behind a wall was culled, never extracted, and never got its box. The render thread still
        // gets false; any other caller gets the truth.
        if (EventBus.post(event)) return ! ((Minecraft) (Object) this).isSameThread();
        //#else
        //$boolean canceled = EventBus.post(event);
        // fork: the two raw checks that used to sit here - isInvisibleTo and hasLineOfSight - are
        // what upstream has now wrapped in LegitEntityVisibility. Its isVisible is exactly
        // `!isInvisibleTo && hasLineOfSight`, so the negation below is the predicate this line
        // already had; it additionally bounds the raycast to 128 blocks, memoises the answer for
        // 100ms per entity and invalidates on world change and entity unload. The placement is the
        // fork's and stays: this still runs only for an entity the event said should glow.
        //$if ((canceled || event.getShouldGlow()) && ! LegitEntityVisibility.isVisible(this.player, entity)) {
        //$    ((IGlowingEntity) entity).noammaddons$isGlowing(false);
        //$    return original;
        //$}
        //$if (canceled) return false;
        //#endif

        var glow = (IGlowingEntity) entity;
        glow.noammaddons$isGlowing(event.getShouldGlow());
        glow.noammaddons$glowColor(event.getColor());

        return original || glow.noammaddons$isGlowing();
    }

    @SuppressWarnings("ConstantValue")
    @Inject(method = "createUserApiService", at = @At("HEAD"), cancellable = true)
    private void onCreateUserApiService(YggdrasilAuthenticationService authService, GameConfig config, CallbackInfoReturnable<UserApiService> cir) {
        String token = user.getAccessToken();
        if (token == null || token.equals("0") || token.equals("FabricMC")) return;
        cir.setReturnValue(authService.createUserApiService(token));
    }
}