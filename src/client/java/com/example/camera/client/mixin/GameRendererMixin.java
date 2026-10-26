package com.example.camera.client.mixin;

import com.example.camera.client.CameraFovState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void cameraMod$customFov(Camera camera, float tickDelta, boolean changingFov,
                                     CallbackInfoReturnable<Double> cir) {
        double custom = CameraFovState.getCustomFov();
        if (custom > 0) {
            cir.setReturnValue(custom);
        }
    }
}