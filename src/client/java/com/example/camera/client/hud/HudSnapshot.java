package com.example.camera.client.hud;

/**
 * Per-frame data the game hands to {@code CameraHud}. The HUD only reads this;
 * it never touches the camera NBT or the exposure pipeline directly.
 *
 * <p>All index/array based fields are cheap to fill every frame and should be
 * treated as read-only by the HUD.</p>
 */
public final class HudSnapshot {
    /** Mode labels shown in the left column (top to bottom). */
    public static final String[] MODE_NAMES = { "自动", "照片", "人像", "夜景", "电影" };

    /** Parameter labels (bottom rail). 焦距 is only shown for zoom lenses. */
    public static final String[] PARAM_NAMES = { "曝光", "ISO", "快门", "白平衡", "对焦", "焦距" };

    /** Top-left "live" badge, e.g. {@code "4K · 60"}. */
    public String liveLabel = "8K · 60";
    /** Top-right exposure readout, e.g. {@code "ƒ1.8 · 1/120 · ISO200 · +0.0EV"}. */
    public String readout = "";

    /** Selected mode, 0..MODE_NAMES.length-1. */
    public int modeIndex = 0;
    /** Selected parameter, 0..paramCount-1. */
    public int paramIndex = 0;
    /** Number of visible parameters (5 normally, 6 when the lens is a zoom). */
    public int paramCount = 5;

    /** Rail indicator position 0..1 for each parameter (0 = min, 1 = max). */
    public final float[] paramFill = new float[PARAM_NAMES.length];
    /** Formatted value under each parameter, e.g. {@code "ISO 400"}. null hides it. */
    public final String[] paramValues = new String[PARAM_NAMES.length];

    /** 自动: the rail should show AUTO for the exposure parameters. */
    public boolean autoMode = false;
    /** 电影: draw letterbox bars + flat/Log look. */
    public boolean cinemaBars = false;
    /** 人像: draw the soft background-vignette. */
    public boolean portrait = false;
    /** 夜景: draw the low-light tint. */
    public boolean night = false;

    /** Bottom hint line. */
    public String hint = "↑ ↓ 切换模式  ·  [ ] 切换参数  ·  ← → 调整数值";
}
