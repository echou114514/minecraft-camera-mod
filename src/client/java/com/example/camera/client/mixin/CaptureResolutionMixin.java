package com.example.camera.client.mixin;

import com.example.camera.client.HighResCapture;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class CaptureResolutionMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void cameraMod$restoreCaptureResolution(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (HighResCapture.isWaiting() || HighResCapture.isCapturing()) {
            HighResCapture.afterFrame(client);
        }
        HighResCapture.restoreAfterRender(client);
    }
}
