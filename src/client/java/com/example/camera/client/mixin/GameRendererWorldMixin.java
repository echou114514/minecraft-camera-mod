package com.example.camera.client.mixin;

import com.example.camera.client.HighResCapture;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererWorldMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void cameraMod$frameStart(CallbackInfo ci) {
        HighResCapture.setInWorldRender(true);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void cameraMod$frameEnd(CallbackInfo ci) {
        HighResCapture.setInWorldRender(false);
    }
}