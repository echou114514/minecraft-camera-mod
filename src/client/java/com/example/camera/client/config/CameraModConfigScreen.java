package com.example.camera.client.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/**
 * Minimal button-cycling config screen reachable through Mod Menu. Each row
 * button advances its value (wrapping); every change is persisted to
 * {@code config/camera-mod.json} immediately.
 */
public class CameraModConfigScreen extends Screen {
    private static final float[] MM_PER_BLOCK_PRESETS = {100f, 250f, 375f, 500f, 1000f};

    private final CameraModConfig cfg = CameraModConfig.get();
    private final Screen parent;

    private ButtonWidget hudButton;
    private ButtonWidget styleButton;
    private ButtonWidget meteringButton;
    private ButtonWidget settleButton;
    private ButtonWidget mmButton;
    private ButtonWidget targetButton;

    public CameraModConfigScreen(Screen parent) {
        super(Text.translatable("config.camera-mod.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = Math.min(260, this.width - 20);
        int x = this.width / 2 - w / 2;
        int y = Math.max(30, this.height / 2 - 90);
        int spacing = 24;

        hudButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.hideVanillaHud", boolText()), b -> {
                    cfg.hideVanillaHud = !cfg.hideVanillaHud;
                    cfg.save();
                }).dimensions(x, y, w, 20).build());

        styleButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.uiStyle", uiStyleText()), b -> {
                    cfg.uiStyle = cfg.isClassicUi() ? "Neon" : "Classic";
                    cfg.save();
                }).dimensions(x, y += spacing, w, 20).build());

        meteringButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.meteringIntervalTicks", String.valueOf(cfg.meteringIntervalTicks)), b -> {
                    cfg.meteringIntervalTicks = cfg.meteringIntervalTicks % 5 + 1;
                    cfg.save();
                }).dimensions(x, y += spacing, w, 20).build());

        settleButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.settleFrames", String.valueOf(cfg.settleFrames)), b -> {
                    cfg.settleFrames = cfg.settleFrames % 8 + 1;
                    cfg.save();
                }).dimensions(x, y += spacing, w, 20).build());

        mmButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.mmPerBlock", formatMm(cfg.mmPerBlock)), b -> {
                    int idx = (nearestPreset(cfg.mmPerBlock) + 1) % MM_PER_BLOCK_PRESETS.length;
                    cfg.mmPerBlock = MM_PER_BLOCK_PRESETS[idx];
                    cfg.save();
                }).dimensions(x, y += spacing, w, 20).build());

        targetButton = addDrawableChild(ButtonWidget
                .builder(label("config.camera-mod.irisHdrTarget", targetText()), b -> {
                    // AUTO(-100) -> 0..7 -> off(-1) -> AUTO
                    if (cfg.irisHdrTarget == CameraModTarget.AUTO) cfg.irisHdrTarget = 0;
                    else if (cfg.irisHdrTarget >= 0 && cfg.irisHdrTarget < 7) cfg.irisHdrTarget++;
                    else if (cfg.irisHdrTarget == 7) cfg.irisHdrTarget = -1;
                    else cfg.irisHdrTarget = CameraModTarget.AUTO;
                    cfg.save();
                }).dimensions(x, y += spacing, w, 20).build());

        addDrawableChild(ButtonWidget
                .builder(Text.translatable("gui.done"), b -> close())
                .dimensions(x, y + spacing + 10, w, 20).build());
    }

    private String uiStyleText() {
        return cfg.isClassicUi() ? "Classic" : "Neon";
    }

    private Text label(String key, String value) {
        return Text.translatable(key).append(": ").append(
                Text.literal(value).formatted(Formatting.AQUA));
    }

    private String boolText() {
        return cfg.hideVanillaHud ? "ON" : "OFF";
    }

    private String targetText() {
        if (cfg.irisHdrTarget == CameraModTarget.AUTO) return "AUTO";
        if (cfg.irisHdrTarget < 0) return "-1 (off)";
        return "colortex" + cfg.irisHdrTarget;
    }

    /** -100 = AUTO（自动检测），-1 = 关闭，0-15 = 手动。 */
    static final class CameraModTarget {
        static final int AUTO = -100;
    }

    private static String formatMm(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static int nearestPreset(float v) {
        int best = 0;
        for (int i = 1; i < MM_PER_BLOCK_PRESETS.length; i++) {
            if (Math.abs(MM_PER_BLOCK_PRESETS[i] - v) < Math.abs(MM_PER_BLOCK_PRESETS[best] - v)) best = i;
        }
        return best;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        // Refresh row labels to the current values.
        hudButton.setMessage(label("config.camera-mod.hideVanillaHud", boolText()));
        styleButton.setMessage(label("config.camera-mod.uiStyle", uiStyleText()));
        meteringButton.setMessage(label("config.camera-mod.meteringIntervalTicks", String.valueOf(cfg.meteringIntervalTicks)));
        settleButton.setMessage(label("config.camera-mod.settleFrames", String.valueOf(cfg.settleFrames)));
        mmButton.setMessage(label("config.camera-mod.mmPerBlock", formatMm(cfg.mmPerBlock)));
        targetButton.setMessage(label("config.camera-mod.irisHdrTarget", targetText()));
    }

    @Override
    public void close() {
        if (client != null && parent != null) {
            client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
