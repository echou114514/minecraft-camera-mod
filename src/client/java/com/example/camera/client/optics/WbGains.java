package com.example.camera.client.optics;

/**
 * Colour-temperature to linear RGB gain conversion (Tanner Helland
 * approximation of the Planckian locus, normalized to green = 1).
 *
 * <p>Shared by the capture pipeline (per-channel gains) and the viewfinder
 * preview (tint strength), so both agree instead of using unrelated
 * hardcoded thresholds.</p>
 */
public final class WbGains {
    private WbGains() {
    }

    /** Returns linear {r, g, b} multipliers for a colour temperature in kelvin. */
    public static float[] gainsFromCct(float kelvin) {
        // Convert to scaled input for the approximation (valid ~1000K-40000K).
        float t = Math.max(1000f, Math.min(40000f, kelvin)) / 100f;
        float r, g, b;

        if (t <= 66f) {
            r = 255f;
        } else {
            r = 329.698727446f * (float) Math.pow(t - 60f, -0.1332047592);
        }

        if (t <= 66f) {
            g = 99.4708025861f * (float) Math.log(t) - 161.1195681661f;
        } else {
            g = 288.1221695283f * (float) Math.pow(t - 60f, -0.0755148492);
        }

        if (t >= 66f) {
            b = 255f;
        } else if (t <= 19f) {
            b = 0f;
        } else {
            b = 138.5177312231f * (float) Math.log(t - 10f) - 305.0447927307f;
        }

        // 0..255 sRGB space -> linear light, then normalize green to 1.
        float lr = srgbToLinear(clamp255(r) / 255f);
        float lg = srgbToLinear(clamp255(g) / 255f);
        float lb = srgbToLinear(clamp255(b) / 255f);
        float norm = Math.max(lg, 1e-4f);
        return new float[] { lr / norm, 1f, lb / norm };
    }

    /**
     * Per-channel gain that re-white-balances a scene lit at {@code sceneCct}
     * as if the sensor were set to {@code sensorCct} (channel-wise ratio).
     */
    public static float[] relativeGains(float sensorCct, float sceneCct) {
        float[] sensor = gainsFromCct(sensorCct);
        float[] scene = gainsFromCct(sceneCct);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = clampRatio(sensor[i] / Math.max(scene[i], 1e-4f));
        }
        return out;
    }

    private static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static float clamp255(float v) {
        return Math.max(1f, Math.min(255f, v));
    }

    private static float clampRatio(float v) {
        return Math.max(0.25f, Math.min(4f, v));
    }
}
