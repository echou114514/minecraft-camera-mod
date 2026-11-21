package com.example.camera.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.NbtCompound;

import java.util.Optional;

/**
 * Full typed state of a Lumin body: exposure settings, selected mode and HUD
 * mode, plus the installed lens (the sensor is part of the body).
 *
 * <p>Stored as a typed data component ({@link ModComponents#CAMERA_DATA}).
 * {@link #toFlatNbt}/{@link #fromFlatNbt} keep the legacy flat key layout so
 * old {@code CUSTOM_DATA} stacks migrate losslessly and the settings screen
 * keeps working on plain NBT copies.</p>
 */
public record CameraData(CameraSettings settings, String mode, int hudMode, Optional<LensSpec> lens) {

    public static final CameraData DEFAULT =
            new CameraData(CameraSettings.DEFAULT, "A_PLUS", 0, Optional.empty());

    public static final Codec<CameraData> CODEC = RecordCodecBuilder.create(i -> i.group(
            CameraSettings.CODEC.fieldOf("settings").forGetter(CameraData::settings),
            Codec.STRING.optionalFieldOf("mode", "A_PLUS").forGetter(CameraData::mode),
            Codec.INT.optionalFieldOf("hudMode", 0).forGetter(CameraData::hudMode),
            LensSpec.CODEC.optionalFieldOf("lens").forGetter(CameraData::lens)
    ).apply(i, CameraData::new));

    /** Builds a snapshot from a legacy/working flat settings NBT, falling back to {@code fallback}. */
    public static CameraData fromFlatNbt(NbtCompound nbt, CameraData fallback) {
        CameraSettings s = fallback.settings;
        CameraSettings next = new CameraSettings(
                nbt.contains("iso") ? nbt.getInt("iso") : s.iso(),
                nbt.contains("shutter") ? nbt.getInt("shutter") : s.shutter(),
                nbt.contains("aperture") ? nbt.getFloat("aperture") : s.aperture(),
                nbt.contains("wb") ? nbt.getInt("wb") : s.wb(),
                nbt.contains("tone") ? nbt.getInt("tone") : s.tone(),
                nbt.contains("focus") ? nbt.getFloat("focus") : s.focus(),
                nbt.contains("evCompStep") ? nbt.getInt("evCompStep") : s.evCompStep(),
                nbt.contains("autoEvStep") ? nbt.getInt("autoEvStep") : s.autoEvStep(),
                // Legacy stacks without the flag started with EV auto ON.
                nbt.contains("evAuto") ? nbt.getBoolean("evAuto") : s.evAuto(),
                nbt.contains("isoAuto") ? nbt.getBoolean("isoAuto") : s.isoAuto(),
                nbt.contains("shutterAuto") ? nbt.getBoolean("shutterAuto") : s.shutterAuto(),
                nbt.contains("apertureAuto") ? nbt.getBoolean("apertureAuto") : s.apertureAuto(),
                nbt.contains("wbAuto") ? nbt.getBoolean("wbAuto") : s.wbAuto(),
                nbt.contains("toneAuto") ? nbt.getBoolean("toneAuto") : s.toneAuto(),
                nbt.contains("focusAuto") ? nbt.getBoolean("focusAuto") : s.focusAuto());
        return new CameraData(
                next,
                nbt.contains("mode") ? nbt.getString("mode") : fallback.mode,
                nbt.contains("hudMode") ? nbt.getInt("hudMode") : fallback.hudMode,
                fallback.lens);
    }

    /** Flat NBT layout identical to the legacy Settings compound (hudMode included). */
    public NbtCompound toFlatNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("iso", settings.iso());
        nbt.putInt("shutter", settings.shutter());
        nbt.putFloat("aperture", settings.aperture());
        nbt.putInt("wb", settings.wb());
        nbt.putInt("tone", settings.tone());
        nbt.putFloat("focus", settings.focus());
        nbt.putInt("evCompStep", settings.evCompStep());
        nbt.putInt("autoEvStep", settings.autoEvStep());
        nbt.putBoolean("evAuto", settings.evAuto());
        nbt.putBoolean("isoAuto", settings.isoAuto());
        nbt.putBoolean("shutterAuto", settings.shutterAuto());
        nbt.putBoolean("apertureAuto", settings.apertureAuto());
        nbt.putBoolean("wbAuto", settings.wbAuto());
        nbt.putBoolean("toneAuto", settings.toneAuto());
        nbt.putBoolean("focusAuto", settings.focusAuto());
        nbt.putString("mode", mode);
        nbt.putInt("hudMode", hudMode);
        return nbt;
    }

    public CameraData withLens(LensSpec spec) {
        return new CameraData(settings, mode, hudMode, Optional.ofNullable(spec));
    }

    public boolean hasLens() {
        return lens.isPresent();
    }
}
