package com.example.camera.client.mixin;

import com.example.camera.client.CameraModClient;
import com.example.camera.client.config.CameraModConfig;

import net.minecraft.client.gui.hud.InGameHud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the vanilla hotbar / health / hunger / crosshair while the camera
 * viewfinder is up, so the new borderless HUD is the only thing on screen.
 *
 * <p>We must not cancel {@code InGameHud.render} itself: Fabric's
 * {@code HudRenderCallback} is invoked at its TAIL, which is where the camera
 * HUD is drawn. Both injections honour {@code hideVanillaHud} in the config so
 * HUD-mod users can keep their overlays.</p>
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    private boolean cameraMod$shouldHide() {
        return CameraModConfig.get().hideVanillaHud && CameraModClient.isViewfinderActive();
    }

    @Inject(method = "renderMainHud", at = @At("HEAD"), cancellable = true)
    private void cameraMod$hideMainHud(CallbackInfo ci) {
        if (cameraMod$shouldHide()) {
            ci.cancel();
        }
    }

    // Purely cosmetic: never hard-crash if another mod changes renderCrosshair.
    @Inject(method = "renderCrosshair", at = @At("HEAD"), require = 0, cancellable = true)
    private void cameraMod$hideCrosshair(CallbackInfo ci) {
        if (cameraMod$shouldHide()) {
            ci.cancel();
        }
    }
}
