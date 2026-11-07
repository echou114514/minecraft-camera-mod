package com.example.camera.client.mixin;

import com.example.camera.client.HighResCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Iris hook. If Iris is not installed the whole config is skipped and
 * high resolution capture falls back to the vanilla main target.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer")
public class IrisFinalPassMixin {
    @Inject(method = "renderFinalPass", at = @At("HEAD"), require = 0, remap = false)
    private void cameraMod$captureHdrBeforeTonemap(CallbackInfo ci) {
        HighResCapture.onIrisFinalPass(this);
    }
}
