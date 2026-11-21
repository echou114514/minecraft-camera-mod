package com.example.camera.client;

public class SceneColorTemp {

    public static int getSceneColorTemp(long timeOfDay, boolean isCave) {
        long t = timeOfDay % 24000L;
        if (isCave) return 5500;

        // Night plateau, then a continuous ramp through dawn (22800) so the
        // white balance never jumps a full step within a single tick.
        if (t >= 13000 && t < 22800) return 5500;
        if (t >= 22800) return lerp(t, 22800, 24000, 5500, 3500);
        if (t < 1000)   return lerp(t, 0, 1000, 3500, 5000);
        if (t < 3000)   return lerp(t, 1000, 3000, 5000, 5500);
        if (t < 5000)   return lerp(t, 3000, 5000, 5500, 6500);
        if (t < 7000)   return 6500;
        if (t < 9000)   return lerp(t, 7000, 9000, 6500, 4000);
        if (t < 11000)  return lerp(t, 9000, 11000, 4000, 3500);
        if (t < 12000)  return lerp(t, 11000, 12000, 3500, 3000);
        return lerp(t, 12000, 13000, 3000, 5500);
    }

    private static int lerp(long t, long a, long b, int ka, int kb) {
        double f = (t - a) / (double) (b - a);
        return (int) (ka + f * (kb - ka));
    }
}