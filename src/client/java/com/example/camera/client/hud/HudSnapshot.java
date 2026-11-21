package com.example.camera.client.hud;

/**
 * Per-frame data the game hands to {@code CameraHud}. The HUD only reads this;
 * it never touches the camera NBT or the exposure pipeline directly.
 *
 * <p>Strings are resolved from translation keys once per tick by the snapshot
 * filler; the HUD treats them as read-only. All index/array based fields are
 * cheap to fill and read.</p>
 */
public final class HudSnapshot {
    public static final int MODE_COUNT = 5;
    public static final int PARAM_COUNT = 6;

    /** Translation keys for the mode labels (top to bottom). */
    public static final String[] MODE_KEYS = {
            "hud.camera-mod.mode.0", "hud.camera-mod.mode.1", "hud.camera-mod.mode.2",
            "hud.camera-mod.mode.3", "hud.camera-mod.mode.4"
    };

    /** Translation keys for the parameter labels (bottom rail). 焦距 shows only for zoom lenses. */
    public static final String[] PARAM_KEYS = {
            "hud.camera-mod.param.0", "hud.camera-mod.param.1", "hud.camera-mod.param.2",
            "hud.camera-mod.param.3", "hud.camera-mod.param.4", "hud.camera-mod.param.5"
    };

    /** Top-left "live" badge, e.g. {@code "全画幅 · 8K"}. */
    public String liveLabel = "8K";
    /** Top-right exposure readout, e.g. {@code "ƒ1.8 · 1/120 · ISO200 · +0.0EV"}. */
    public String readout = "";

    /** Mode labels resolved from {@link #MODE_KEYS}. */
    public final String[] modeNames = new String[MODE_COUNT];
    /** Parameter labels resolved from {@link #PARAM_KEYS}. */
    public final String[] paramNames = new String[PARAM_COUNT];

    /** Selected mode, 0..MODE_COUNT-1. */
    public int modeIndex = 0;
    /** Selected parameter, 0..paramCount-1. */
    public int paramIndex = 0;
    /** Number of visible parameters (5 normally, 6 when the lens is a zoom). */
    public int paramCount = 5;

    /** Rail indicator position 0..1 for each parameter (0 = min, 1 = max). */
    public final float[] paramFill = new float[PARAM_COUNT];
    /** Formatted value under each parameter, e.g. {@code "ISO 400"}. null hides it. */
    public final String[] paramValues = new String[PARAM_COUNT];

    /** 自动: the rail should show AUTO for the exposure parameters. */
    public boolean autoMode = false;
    /** 电影: draw letterbox bars + flat/Log look. */
    public boolean cinemaBars = false;
    /** 人像: draw the soft background-vignette. */
    public boolean portrait = false;
    /** 夜景: draw the low-light tint. */
    public boolean night = false;

    /** Bottom hint line. */
    public String hint = "";

    // ---- Classic（佳能式）取景器的原始读数，由快照填充端按 tick 解析 ----
    /** 曝光模式字母：A+ / P / Tv / Av / M（B 暂不开放）。 */
    public String modeLetter = "P";
    /** 佳能格式快门：1/80、0"5、2"。 */
    public String shutterText = "1/125";
    /** 佳能格式光圈：F5.6。 */
    public String apertureText = "F5.6";
    /** ISO 数值文本。 */
    public String isoText = "100";
    /** 白平衡自动？ */
    public boolean wbAuto = true;
    /** 白平衡数值（手动时的开尔文）。 */
    public int wbValue = 5500;
    /** 当前 EV 偏移（测光误差 + 手动补偿），驱动 Classic 的标尺指针。 */
    public float evOffset = 0f;
    /** AF 状态：0 空闲，1 对焦中，2 合焦确认。 */
    public int afState = 0;
}
