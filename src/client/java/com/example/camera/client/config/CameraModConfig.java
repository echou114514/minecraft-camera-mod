package com.example.camera.client.config;

import com.example.camera.CameraMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client config, stored in {@code config/camera-mod.json}. Replaces the old
 * JVM-only tuning knobs (system properties still win when explicitly set, so
 * the dev run configurations keep working).
 */
public final class CameraModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CameraModConfig instance;

    /** Hide the vanilla hotbar/health/crosshair while the viewfinder is up. */
    public boolean hideVanillaHud = true;
    /** Viewfinder HUD style: "Neon" (modern borderless) or "Classic" (Canon EOS style). */
    public String uiStyle = "Neon";
    /** Metering raycast runs every N client ticks. */
    public int meteringIntervalTicks = 3;
    /** Frames rendered at full resolution before the 8K pixels are read. */
    public int settleFrames = 4;
    /** World scale for the thin-lens model (millimeters per block). */
    public float mmPerBlock = 375f;
    /**
     * Iris colortex holding the pre-tonemap HDR scene.
     * -100 = AUTO (parse the pack's final program, default), -1 = disable the
     * Iris path entirely, 0-15 = manual override.
     */
    public int irisHdrTarget = -100;
    /** Prefix for saved files. */
    public String filenamePrefix = "camera_";
    /** Export format: "TIFF" (32-bit float, display-referred), "EXR" (linear HDR + Z), "DNG" (LinearRaw 16-bit + DepthMap). */
    public String exportFormat = "TIFF";

    public boolean isClassicUi() {
        return "Classic".equalsIgnoreCase(uiStyle);
    }

    public static CameraModConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void reload() {
        instance = load();
    }

    private static CameraModConfig load() {
        CameraModConfig cfg = new CameraModConfig();
        Path path = path();
        if (Files.exists(path)) {
            try {
                CameraModConfig parsed = GSON.fromJson(Files.readString(path), CameraModConfig.class);
                if (parsed != null) {
                    // 迁移：5 是引入 AUTO 前的旧默认值， persisted 5 一律视为
                    // 「从未显式选择」，升级为 AUTO（真正想用 5 的用户重选一次即可）。
                    if (parsed.irisHdrTarget == 5) parsed.irisHdrTarget = -100;
                    return parsed;
                }
            } catch (Exception e) {
                CameraMod.LOGGER.warn("[CameraMod] Failed to read config, using defaults: {}", e.toString());
            }
        }
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            CameraMod.LOGGER.warn("[CameraMod] Failed to save config: {}", e.toString());
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(CameraMod.MOD_ID + ".json");
    }
}
