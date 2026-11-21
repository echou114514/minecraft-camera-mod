package com.example.camera.client;

import com.example.camera.CameraMod;

import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;

/**
 * Single owner of the "hide the player's held item / arm" switch used during
 * the 8K capture. The camera item renderer additionally hides its own model
 * via {@link CameraModClient#isHandHidden()} while the viewfinder is up.
 */
public final class HandVisibility {
    private static Field renderHandField;
    private static boolean restoreRenderHand = true;

    private HandVisibility() {
    }

    public static void hideDuringCapture(MinecraftClient client) {
        try {
            if (renderHandField == null) {
                renderHandField = client.gameRenderer.getClass().getDeclaredField("renderHand");
                renderHandField.setAccessible(true);
            }
            restoreRenderHand = renderHandField.getBoolean(client.gameRenderer);
        } catch (Throwable ignored) {
            restoreRenderHand = true;
        }
        client.gameRenderer.setRenderHand(false);
    }

    public static void restoreAfterCapture(MinecraftClient client) {
        try {
            client.gameRenderer.setRenderHand(restoreRenderHand);
        } catch (Throwable t) {
            CameraMod.LOGGER.warn("[CameraMod] 恢复手持物品渲染失败: {}", t.toString());
        }
    }
}
