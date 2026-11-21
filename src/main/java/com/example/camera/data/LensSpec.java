package com.example.camera.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.NbtCompound;

/**
 * Optical specification of a lens (focal range, aperture, autofocus).
 *
 * <p>Stored as a typed data component ({@link ModComponents#LENS_SPEC}); the
 * NBT methods below only serve the legacy {@code CUSTOM_DATA} migration path.</p>
 */
public class LensSpec {
    public int focalLength;
    public int maxFocalLength;
    public int baseFocalLength;   // 变焦头的广角端（定焦头 == focalLength）
    public boolean isZoom;
    public boolean autoFocus;
    public float maxAperture;

    public static final Codec<LensSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("focalLength").forGetter(s -> s.focalLength),
            Codec.INT.fieldOf("maxFocalLength").forGetter(s -> s.maxFocalLength),
            Codec.INT.fieldOf("baseFocalLength").forGetter(s -> s.baseFocalLength),
            Codec.BOOL.fieldOf("isZoom").forGetter(s -> s.isZoom),
            Codec.BOOL.fieldOf("autoFocus").forGetter(s -> s.autoFocus),
            Codec.FLOAT.fieldOf("maxAperture").forGetter(s -> s.maxAperture)
    ).apply(i, (f, m, b, z, a, ap) -> {
        LensSpec s = new LensSpec(f, m, z, a, ap);
        s.baseFocalLength = b;
        return s.sanitize();
    }));

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

    /** Clamps out-of-range fields so hand-edited or legacy data cannot yield f/0.0. */
    public static LensSpec sanitize(LensSpec s) {
        if (s.focalLength <= 0) s.focalLength = 13;
        if (s.focalLength > 100000) s.focalLength = 100000;
        if (s.maxFocalLength < s.focalLength) s.maxFocalLength = s.focalLength;
        if (s.baseFocalLength <= 0 || (!s.isZoom && s.baseFocalLength != s.focalLength)) {
            s.baseFocalLength = s.focalLength;
        }
        if (s.isZoom && s.baseFocalLength > s.focalLength) s.baseFocalLength = s.focalLength;
        if (!(s.maxAperture > 0f) || Float.isNaN(s.maxAperture)) s.maxAperture = 1.8f;
        s.maxAperture = Math.max(0.5f, Math.min(64f, s.maxAperture));
        return s;
    }

    public LensSpec sanitize() {
        return sanitize(this);
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

        return s.sanitize();
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
