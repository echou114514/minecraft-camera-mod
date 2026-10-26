package com.example.camera.client.mixin;

import com.example.camera.client.HighResCapture;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void cameraMod$hudStart(DrawContext ctx, RenderTickCounter tickCounter, CallbackInfo ci) {
        // HUD 开始渲染 → 切回主 framebuffer
        HighResCapture.setInWorldRender(false);
    }
}