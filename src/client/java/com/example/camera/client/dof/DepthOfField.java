package com.example.camera.client.dof;

public final class DepthOfField {
    private DepthOfField() {}

    // 【新增】全局景深衰减：让 8K 下的虚化符合真实相机比例
    public static float DOF_STRENGTH = 0.35f;

    public static float computeCocPixels(
            float objectDistance,
            float focusDistance,
            float focalLengthMm,
            float aperture,
            int imageHeightPx,
            float sensorHeightMm
    ) {
        if (objectDistance <= 0f || focusDistance <= 0f ||
                focalLengthMm <= 0f || aperture <= 0f) {
            return 0f;
        }

        float s2 = objectDistance * 1000f;
        float cocMm;

        if (Float.isInfinite(focusDistance) || focusDistance > 10000f) {
            cocMm = (aperture * focalLengthMm) / s2;
        } else {
            float s1 = focusDistance * 1000f;
            float numerator = aperture * focalLengthMm * Math.abs(s2 - s1);
            float denominator = s2 * (s1 - focalLengthMm);
            if (Math.abs(denominator) < 1e-4f) return 0f;
            cocMm = numerator / denominator;
        }

        float cocPixels = (cocMm / sensorHeightMm) * imageHeightPx;
        float s1 = focusDistance * 1000f;

        // 乘以真实的衰减系数
        return (s2 < s1 ? -cocPixels : cocPixels) * DOF_STRENGTH;
    }

    public static float clampCoc(float coc, int imageHeightPx) {
        float max = imageHeightPx * 0.02f;
        return Math.max(-max, Math.min(max, coc));
    }
}