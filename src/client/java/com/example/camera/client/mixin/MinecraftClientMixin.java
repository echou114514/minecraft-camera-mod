package com.example.camera.client.mixin;

import com.example.camera.client.HighResCapture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true)
    private void cameraMod$getTileFramebuffer(CallbackInfoReturnable<Framebuffer> cir) {
        if ((HighResCapture.isCapturing() || HighResCapture.isWaiting())
                && HighResCapture.isInWorldRender()) {
            Framebuffer fb = HighResCapture.getTileFramebuffer();
            if (fb != null) {
                GL11.glViewport(0, 0, HighResCapture.FULL_W, HighResCapture.FULL_H);
                cir.setReturnValue(fb);
            }
        }
    }
}