package com.example.camera.client;

public class CameraFovState {
    private static double customFov = -1;

    public static void setCustomFov(double fov) {
        customFov = fov;
    }

    public static void clear() {
        customFov = -1;
    }

    public static double getCustomFov() {
        return customFov;
    }
}