package com.github.noamm9.mixin;

import com.github.noamm9.features.impl.dev.Cosmetics;
import com.github.noamm9.features.impl.dev.text.TextReplacer;
import com.github.noamm9.features.impl.general.ItemTooltip;
import com.github.noamm9.features.impl.general.storageoverlay.StorageOverlayTooltip;
import com.github.noamm9.features.impl.misc.Tweaks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = GuiGraphicsExtractor.class)
public abstract class MixinGuiGraphicsExtractor {
    @Shadow @Final private Matrix3x2fStack pose;

    @WrapMethod(method = "tooltip")
    private void onRenderTooltipPre(Font font, List<ClientTooltipComponent> lines, int xo, int yo, ClientTooltipPositioner positioner, @org.jspecify.annotations.Nullable Identifier style, Operation<Void> original) {
        // fork: `drawingTooltip` has exactly one reader - MixinFont's shouldReplace - and it *negates*
        // the flag, so raising it means "do not replace names here". Gating it on `Show Name in Lore`
        // being on therefore made the new toggle do the opposite of its label: on hid cosmetic names in
        // lore, off showed them. Negated so the setting reads the way it is written. It defaults to on,
        // so the default is now to show them, where before this sync tooltips never replaced at all.
        if (Cosmetics.INSTANCE.enabled && Cosmetics.getCustomNames().getValue() && ! Cosmetics.getLoreNames().getValue()) TextReplacer.drawingTooltip = true;
        boolean scrolling = ItemTooltip.isScrollingEnabled();
        float storageScale = StorageOverlayTooltip.scale(); /// fork: matches the storage overlay
        if (! scrolling && storageScale == 1f) original.call(font, lines, xo, yo, positioner, style);
        else {
            pose.pushMatrix();
            pose.translate(xo, yo);
            pose.scale((scrolling ? ItemTooltip.getTooltipScale().getValue().floatValue() / 100f + ItemTooltip.scaleOverride / 10f : 1f) * storageScale);
            if (scrolling) pose.translate(ItemTooltip.scrollAmountX, ItemTooltip.scrollAmountY);
            pose.translate(- xo, - yo);
            original.call(font, lines, xo, yo, positioner, style);
            pose.popMatrix();
        }

        TextReplacer.drawingTooltip = false;
    }

    @WrapOperation(
        method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemCooldown(Lnet/minecraft/world/item/ItemStack;II)V"
        )
    )
    private void hideItemCooldownOverlay(GuiGraphicsExtractor instance, ItemStack itemStack, int x, int y, Operation<Void> original) {
        if (Tweaks.shouldHideItemCooldownOverlay()) return;
        original.call(instance, itemStack, x, y);
    }
}