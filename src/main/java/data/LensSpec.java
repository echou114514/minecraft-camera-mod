package data;

import net.minecraft.nbt.NbtCompound;

public class LensSpec {
    public int focalLength;
    public int maxFocalLength;
    public int baseFocalLength;   // 变焦头的广角端（定焦头 == focalLength）
    public boolean isZoom;
    public boolean autoFocus;
    public float maxAperture;

    public static final int[] FOCAL_LENGTHS = {
            13, 20, 24, 28, 35, 40, 50, 70, 105, 135, 200, 400, 600, 800, 1200
    };

    public static final float[] STANDARD_APERTURES = {
            0.95f, 1.0f, 1.1f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f,
            3.5f, 4.0f, 4.5f, 5.0f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f,
            16f, 18f, 20f, 22f
    };

    public LensSpec(int focalLength, int maxFocalLength,
                    boolean isZoom, boolean autoFocus, float maxAperture) {
        this.focalLength = focalLength;
        this.maxFocalLength = maxFocalLength;
        this.baseFocalLength = focalLength; // 默认定焦
        this.isZoom = isZoom;
        this.autoFocus = autoFocus;
        this.maxAperture = maxAperture;
    }

    public static LensSpec base() {
        return new LensSpec(13, 13, false, false, 1.8f);
    }

    public static LensSpec fromNbt(NbtCompound nbt) {
        LensSpec s = new LensSpec(
                nbt.getInt("focalLength"),
                nbt.getInt("maxFocalLength"),
                nbt.getBoolean("isZoom"),
                nbt.getBoolean("autoFocus"),
                nbt.getFloat("maxAperture")
        );

        if (nbt.contains("baseFocalLength")) {
            s.baseFocalLength = nbt.getInt("baseFocalLength");
        } else {
            s.baseFocalLength = s.focalLength;
        }

        // 如果是变焦头，且广角端异常（大于当前焦距或小于等于0），强行拉回 13
        if (s.isZoom && (s.baseFocalLength <= 0 || s.baseFocalLength > s.focalLength)) {
            s.baseFocalLength = 13;
        }

        return s;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("focalLength", focalLength);
        nbt.putInt("maxFocalLength", maxFocalLength);
        nbt.putInt("baseFocalLength", baseFocalLength);
        nbt.putBoolean("isZoom", isZoom);
        nbt.putBoolean("autoFocus", autoFocus);
        nbt.putFloat("maxAperture", maxAperture);
        return nbt;
    }

    public static float apertureLimit(int focalLength, boolean isZoom) {
        if (isZoom) {
            if (focalLength <= 200) return 2.8f;
            if (focalLength <= 600) return 4.0f;
            return 5.6f;
        } else {
            if (focalLength <= 70)  return 0.95f;
            if (focalLength <= 400) return 1.8f;
            return 5.6f;
        }
    }

    public static int nextFocal(int current, int steps) {
        int idx = 0;
        for (int i = 0; i < FOCAL_LENGTHS.length; i++) {
            if (FOCAL_LENGTHS[i] <= current) idx = i;
            else break;
        }
        idx = Math.min(FOCAL_LENGTHS.length - 1, idx + steps);
        return FOCAL_LENGTHS[idx];
    }

    public static int maxFocalWithin(int current, float multiplier) {
        int maxAllowed = (int) (current * multiplier);
        int target = current;
        for (int f : FOCAL_LENGTHS) {
            if (f > current && f <= maxAllowed) target = f;
        }
        return target;
    }

    public static float snapAperture(float v) {
        float best = STANDARD_APERTURES[0];
        float bestDiff = Math.abs(v - best);
        for (float a : STANDARD_APERTURES) {
            float d = Math.abs(v - a);
            if (d < bestDiff) { best = a; bestDiff = d; }
        }
        return best;
    }

    public LensSpec copy() {
        LensSpec s = new LensSpec(focalLength, maxFocalLength, isZoom, autoFocus, maxAperture);
        s.baseFocalLength = this.baseFocalLength;
        return s;
    }
}