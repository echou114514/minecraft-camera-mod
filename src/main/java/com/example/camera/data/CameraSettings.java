package com.example.camera.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Exposure/white-balance state of a Lumin body. Flat NBT keys match the legacy
 * {@code CUSTOM_DATA} layout so old cameras migrate losslessly.
 */
public record CameraSettings(
        int iso,
        int shutter,
        float aperture,
        int wb,
        int tone,
        float focus,
        int evCompStep,
        int autoEvStep,
        boolean evAuto,
        boolean isoAuto,
        boolean shutterAuto,
        boolean apertureAuto,
        boolean wbAuto,
        boolean toneAuto,
        boolean focusAuto
) {
    public static final CameraSettings DEFAULT = new CameraSettings(
            100, 125, 5.6f, 5500, 0, 10.0f, 0, 0,
            true, false, false, false, false, false, false);

    public static final Codec<CameraSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("iso", 100).forGetter(CameraSettings::iso),
            Codec.INT.optionalFieldOf("shutter", 125).forGetter(CameraSettings::shutter),
            Codec.FLOAT.optionalFieldOf("aperture", 5.6f).forGetter(CameraSettings::aperture),
            Codec.INT.optionalFieldOf("wb", 5500).forGetter(CameraSettings::wb),
            Codec.INT.optionalFieldOf("tone", 0).forGetter(CameraSettings::tone),
            Codec.FLOAT.optionalFieldOf("focus", 10.0f).forGetter(CameraSettings::focus),
            Codec.INT.optionalFieldOf("evCompStep", 0).forGetter(CameraSettings::evCompStep),
            Codec.INT.optionalFieldOf("autoEvStep", 0).forGetter(CameraSettings::autoEvStep),
            Codec.BOOL.optionalFieldOf("evAuto", true).forGetter(CameraSettings::evAuto),
            Codec.BOOL.optionalFieldOf("isoAuto", false).forGetter(CameraSettings::isoAuto),
            Codec.BOOL.optionalFieldOf("shutterAuto", false).forGetter(CameraSettings::shutterAuto),
            Codec.BOOL.optionalFieldOf("apertureAuto", false).forGetter(CameraSettings::apertureAuto),
            Codec.BOOL.optionalFieldOf("wbAuto", false).forGetter(CameraSettings::wbAuto),
            Codec.BOOL.optionalFieldOf("toneAuto", false).forGetter(CameraSettings::toneAuto),
            Codec.BOOL.optionalFieldOf("focusAuto", false).forGetter(CameraSettings::focusAuto)
    ).apply(i, CameraSettings::new));
}
