package com.example.camera.client.dof;

/**
 * 薄透镜景深模型（参考 Snapmatica 的 {@code evf_blur.fsh}）。
 *
 * <p>CoC 用真实的薄透镜公式算，再和艾里斑按平方和合并；世界尺度由
 * {@link #mmPerBlock} 决定（默认 375 mm/方块，约 37.5 cm）。这样焦距、光圈、
 * 对焦距离的物理关系是对的，不再需要经验调参。</p>
 */
public final class DepthOfField {
    private DepthOfField() {}

    /** 1 个 Minecraft 方块对应多少毫米。可由 -Dcameramod.mmPerBlock 覆盖。 */
    public static final float mmPerBlock = readMmPerBlock();

    private static float readMmPerBlock() {
        String v = System.getProperty("cameramod.mmPerBlock");
        if (v != null) {
            try {
                float parsed = Float.parseFloat(v);
                if (parsed > 1f && parsed < 100000f) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 375f;
    }

    /**
     * 有符号 CoC（像素）。正数表示背景，负数表示前景。
     *
     * @param objectDistance 物距（方块）
     * @param focusDistance  对焦距离（方块），{@link Float#POSITIVE_INFINITY} 为无穷远
     * @param focalLengthMm  焦距（mm）
     * @param aperture       f 值
     * @param imageHeightPx  成片高度（像素）
     * @param sensorHeightMm 传感器高度（mm）
     */
    public static float computeCocPixels(
            float objectDistance,
            float focusDistance,
            float focalLengthMm,
            float aperture,
            int imageHeightPx,
            float sensorHeightMm
    ) {
        if (objectDistance <= 0f || focalLengthMm <= 0f || aperture <= 0f
                || imageHeightPx <= 0 || sensorHeightMm <= 0f) {
            return 0f;
        }

        float f = focalLengthMm;
        float n = aperture;
        float s2 = objectDistance * mmPerBlock;

        float cocMm;
        boolean infiniteFocus = Float.isInfinite(focusDistance) || focusDistance > 10000f;
        if (infiniteFocus) {
            // 对焦在无穷远：coc = f² / (N · S2)
            cocMm = (f * f) / (n * s2);
        } else {
            float s1 = focusDistance * mmPerBlock;
            float denom = s2 * n * (s1 - f);
            if (Math.abs(denom) < 1e-4f) {
                return 0f;
            }
            // 有限对焦：coc = f² · |S2 - S1| / (S2 · N · (S1 - f))
            cocMm = (f * f * Math.abs(s2 - s1)) / denom;
        }

        // 衍射：550 nm 的艾里斑直径。失焦和衍射按平方和叠加。
        float airyMm = 2.44f * 0.00055f * n;
        cocMm = (float) Math.sqrt(cocMm * cocMm + airyMm * airyMm);

        float cocPixels = (cocMm / sensorHeightMm) * imageHeightPx;
        float signed = (infiniteFocus || s2 >= focusDistance * mmPerBlock) ? cocPixels : -cocPixels;
        return signed;
    }

    /**
     * 软膝：0 附近的值和斜率都趋近 0，避免景深从清晰到模糊时出现硬边。
     * {@code g = c² / (c + 1.5)}。
     */
    public static float softKnee(float cocPixels) {
        float a = Math.abs(cocPixels);
        float g = (a * a) / (a + 1.5f);
        return cocPixels < 0f ? -g : g;
    }

    public static float clampCoc(float coc, int imageHeightPx) {
        float max = imageHeightPx * 0.02f;
        return Math.max(-max, Math.min(max, coc));
    }
}
