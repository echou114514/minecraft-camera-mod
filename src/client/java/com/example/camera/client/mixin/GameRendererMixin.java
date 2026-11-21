package com.example.camera.client.mixin;

import com.example.camera.client.CameraFovState;
import com.example.camera.client.CameraModClient;
import com.example.camera.client.HighResCapture;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void cameraMod$customFov(Camera camera, float tickDelta, boolean changingFov,
                                     CallbackInfoReturnable<Double> cir) {
        // Only take over while the viewfinder is actually up, so world loading
        // and other screens keep the vanilla FOV.
        if (!CameraModClient.isViewfinderActive()) return;
        double custom = CameraFovState.getCustomFov();
        if (custom > 0) {
            cir.setReturnValue(custom);
        }
    }

    /**
     * Tracks the live perspective matrix so the DoF code can convert depth
     * buffer values to world distances with the real near/far planes (the
     * vanilla far plane follows the render distance).
     *
     * <p>The bundled JOML keeps the m22/m23 fields package-private, so the
     * matrix is read out in column-major order: index 10 = row2/col2 (m22),
     * index 14 = row3/col2 (m23).</p>
     */
    @Inject(method = "loadProjectionMatrix", at = @At("RETURN"))
    private void cameraMod$trackProjection(Matrix4f matrix, CallbackInfo ci) {
        float[] buf = new float[16];
        matrix.get(buf);
        HighResCapture.updateProjection(buf[10], buf[14]);
    }
}
