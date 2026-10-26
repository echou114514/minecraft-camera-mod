package com.example.camera.client.gui;

import com.example.camera.ModItems;
import com.example.camera.client.AutoExposure;
import com.example.camera.client.ExposureMode;
import com.example.camera.client.metering.Metering;
import data.LensSpec;
import item.Jishen;
import item.Jingtou;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class CameraSettingsScreen extends Screen {

    private static final int PANEL_W = 500;
    private static final int PANEL_H = 200;
    private static final int COLOR = 0xFFFFFFFF;
    private static final int COLOR_SEL = 0xFFFFAA00;
    private static final int LINE_COLOR = 0x55FFFFFF;

    private static final int R_SHUTTER = 0;
    private static final int R_APERTURE = 1;
    private static final int R_ISO = 2;
    private static final int R_EV = 3;
    private static final int R_WB = 4;
    private static final int R_TONE = 5;
    private static final int R_FOCUS = 6;
    private static final int R_MODE = 7;
    private static final int R_METER = 8;
    private static final int R_AUTOEV = 9;

    private static final int TOTAL_REGIONS = 10;
    private final int[][] regions = new int[TOTAL_REGIONS][4];

    private final ItemStack target;
    private ExposureMode mode;
    private int iso, shutter, wb, tone;
    private int evCompStep = 0;
    private int autoEvStep = 0;
    private float focus, aperture;
    private float minAperture = 0.95f;
    private boolean isoAuto, shutterAuto, apertureAuto, wbAuto, toneAuto, focusAuto;

    private int selected = R_ISO;

    // 根据焦距计算最近对焦距离 (焦距越长，最近对焦距离越远)
    private float getMinFocusDistance() {
        int focal = 50; // 默认值
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getOffHandStack().isOf(ModItems.LENS)) {
            LensSpec spec = Jingtou.getSpec(client.player.getOffHandStack());
            focal = spec.focalLength;
        }
        return Math.max(0.5f, focal / 100.0f);
    }

    public static void open(ItemStack stack) {
        MinecraftClient.getInstance().setScreen(new CameraSettingsScreen(stack));
    }

    public CameraSettingsScreen(ItemStack stack) {
        super(Text.literal("相机设置"));
        this.target = stack;
        NbtCompound nbt = Jishen.getSettings(stack);

        String modeStr = nbt.getString("mode");
        mode = ExposureMode.M;
        for (ExposureMode m : ExposureMode.values()) {
            if (m.name.equals(modeStr)) { mode = m; break; }
        }

        iso = nbt.getInt("iso");
        shutter = nbt.getInt("shutter");
        wb = nbt.getInt("wb");
        tone = nbt.getInt("tone");
        focus = nbt.getFloat("focus");
        aperture = nbt.getFloat("aperture");
        evCompStep = nbt.getInt("evCompStep");
        autoEvStep = nbt.getInt("autoEvStep");

        isoAuto = nbt.getBoolean("isoAuto");
        shutterAuto = nbt.getBoolean("shutterAuto");
        apertureAuto = nbt.getBoolean("apertureAuto");
        wbAuto = nbt.getBoolean("wbAuto");
        toneAuto = nbt.getBoolean("toneAuto");
        focusAuto = nbt.getBoolean("focusAuto");

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getOffHandStack().isOf(ModItems.LENS)) {
            LensSpec spec = Jingtou.getSpec(client.player.getOffHandStack());
            minAperture = spec.maxAperture;
        }
        if (aperture < minAperture) aperture = minAperture;

        applyModeRules();
    }

    private void applyModeRules() {
        switch (mode) {
            case A_PLUS -> { isoAuto = true; shutterAuto = true; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case P -> { shutterAuto = true; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case TV -> { shutterAuto = false; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case AV -> { shutterAuto = true; apertureAuto = false; wbAuto = true; toneAuto = true; }
            case M, B -> { shutterAuto = false; apertureAuto = false; wbAuto = true; toneAuto = true; }
        }
    }

    private void syncToNbt() {
        NbtCompound nbt = Jishen.defaultSettings();
        nbt.putString("mode", mode.name);
        nbt.putInt("iso", iso);
        nbt.putInt("shutter", shutter);
        nbt.putFloat("aperture", aperture);
        nbt.putInt("wb", wb);
        nbt.putInt("tone", tone);
        nbt.putFloat("focus", focus);
        nbt.putInt("evCompStep", evCompStep);
        nbt.putInt("autoEvStep", autoEvStep);
        nbt.putBoolean("isoAuto", isoAuto);
        nbt.putBoolean("shutterAuto", shutterAuto);
        nbt.putBoolean("apertureAuto", apertureAuto);
        nbt.putBoolean("wbAuto", wbAuto);
        nbt.putBoolean("toneAuto", toneAuto);
        nbt.putBoolean("focusAuto", focusAuto);
        Jishen.setSettings(target, nbt);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int w = this.width;
        int h = this.height;
        int px = (w - PANEL_W) / 2;
        int py = (h - PANEL_H) / 2;

        ctx.fill(px, py, px + PANEL_W, py + PANEL_H, 0xFF000000);
        drawFrame(ctx, px, py, PANEL_W, PANEL_H);

        int innerX = px + 15;
        int innerW = PANEL_W - 30;
        int curY = py + 12;

        int colW = innerW / 3;
        drawValue(ctx, innerX,            curY, colW, 30, "快门 " + Metering.formatShutter(shutter), R_SHUTTER,  autoStr(shutterAuto));
        drawValue(ctx, innerX + colW,     curY, colW, 30, "f/" + fmtAp(aperture),                    R_APERTURE, autoStr(apertureAuto));
        drawValue(ctx, innerX + 2 * colW, curY, colW, 30, "ISO " + (isoAuto ? "AUTO" : String.valueOf(iso)), R_ISO, autoStr(isoAuto));
        curY += 30;
        drawHLine(ctx, innerX, curY, innerX + innerW);
        curY += 8;

        drawEvBar(ctx, innerX, curY, innerW, 42, evCompStep, R_EV);
        curY += 42;
        drawHLine(ctx, innerX, curY, innerX + innerW);
        curY += 8;

        int colW3 = innerW / 3;
        drawValue(ctx, innerX,             curY, colW3, 30, wbAuto ? "AWB" : wb + "K",                 R_WB,    autoStr(wbAuto));
        drawValue(ctx, innerX + colW3,     curY, colW3, 30, "色调 " + fmtSigned(tone),                  R_TONE,  autoStr(toneAuto));
        String focusStr = Float.isInfinite(focus) ? "∞" : String.format("%.1fm", focus);
        drawValue(ctx, innerX + 2 * colW3, curY, colW3, 30, "对焦 " + focusStr,   R_FOCUS, autoStr(focusAuto));
        curY += 30;
        drawHLine(ctx, innerX, curY, innerX + innerW);
        curY += 8;

        int colW4a = innerW / 4;
        int colW4b = innerW / 4;
        int colW4c = innerW - colW4a - colW4b;
        drawValue(ctx, innerX,                   curY, colW4a, 30, mode.name,                        R_MODE,   "");
        drawValue(ctx, innerX + colW4a,          curY, colW4b, 30, Metering.currentMode.displayName, R_METER,  "");
        drawValue(ctx, innerX + colW4a + colW4b, curY, colW4c, 30, "A-EV " + fmtAutoEv(autoEvStep),  R_AUTOEV, "");
    }

    private void drawFrame(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        ctx.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        ctx.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        ctx.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
    }

    private void drawHLine(DrawContext ctx, int x1, int y, int x2) {
        ctx.fill(x1, y, x2, y + 1, LINE_COLOR);
    }

    private String autoStr(boolean auto) { return auto ? "A" : "M"; }
    private String fmtSigned(int v) { return (v >= 0 ? "+" : "") + v; }
    private String fmtAutoEv(int step) {
        float v = step / 3.0f;
        return (v >= 0 ? "+" : "") + String.format("%.1f", v);
    }
    private static String fmtAp(float a) {
        return a == (int) a ? String.valueOf((int) a) : String.format("%.1f", a);
    }

    private void drawValue(DrawContext ctx, int x, int y, int w, int h,
                           String value, int index, String autoMark) {
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        regions[index][0] = x;
        regions[index][1] = y;
        regions[index][2] = w;
        regions[index][3] = h;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(2f, 2f, 1f);

        int sx = x / 2;
        int sy = y / 2;
        int sw = w / 2;

        int textW = textRenderer.getWidth(value);
        int textX = sx + (sw - textW) / 2;
        int textY = sy + (h / 2 - 8) / 2;

        ctx.drawText(textRenderer, value, textX, textY, color, false);

        if (!autoMark.isEmpty()) {
            ctx.drawText(textRenderer, autoMark, sx + sw - 8, sy + 2,
                    autoMark.equals("A") ? 0xFF55FF55 : 0xFFAAAAAA, false);
        }

        m.pop();
    }

    private void drawEvBar(DrawContext ctx, int x, int y, int w, int h,
                           int step, int index) {
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        regions[index][0] = x;
        regions[index][1] = y;
        regions[index][2] = w;
        regions[index][3] = h;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(2f, 2f, 1f);
        int sx = x / 2;
        int sy = y / 2;
        int sw = w / 2;

        int cx = sx + sw / 2;
        int spacing = 24;
        for (int i = -3; i <= 3; i++) {
            String s = (i > 0 ? "+" : "") + i;
            int tw = textRenderer.getWidth(s);
            ctx.drawText(textRenderer, s, cx + i * spacing - tw / 2, sy, color, false);
        }

        int tickY = sy + 10;
        for (int i = -9; i <= 9; i++) {
            if (i % 3 == 0) continue;
            int tx = cx + i * spacing / 3;
            ctx.fill(tx, tickY, tx + 1, tickY + 4, color);
        }
        m.pop();

        double ev = step / 3.0;
        int screenCx = x + w / 2;
        int screenSpacing = 40;
        int pointerX = screenCx + (int) (ev * screenSpacing);
        int pointerY = y + 30;
        ctx.fill(pointerX - 1, pointerY, pointerX + 1, pointerY + 1, color);
        ctx.fill(pointerX - 2, pointerY + 1, pointerX + 2, pointerY + 2, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < TOTAL_REGIONS; i++) {
            int[] r = regions[i];
            if (r[2] == 0) continue;
            if (mx >= r[0] && mx <= r[0] + r[2]
                    && my >= r[1] && my <= r[1] + r[3]) {

                // 点击右侧 40 像素 → 切换 A/M
                double rightZoneStart = r[0] + r[2] - 40;
                if (mx >= rightZoneStart && canToggleAuto(i)) {
                    selected = i;
                    toggleAuto(i);
                    syncToNbt();
                    return true;
                }

                selected = i;
                return true;
            }
        }
        selected = -1;
        return super.mouseClicked(mx, my, button);
    }

    /** 该区域在当前模式下是否允许切换 A/M */
    private boolean canToggleAuto(int idx) {
        return switch (idx) {
            case R_ISO -> mode.isoSwitchable();
            case R_SHUTTER -> mode.shutterSwitchable();
            case R_APERTURE -> mode.apertureSwitchable();
            case R_WB, R_TONE -> mode.wbSwitchable();
            default -> false;
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (selected < 0) return false;
        if (vertical > 0) adjust(selected, 1);
        else if (vertical < 0) adjust(selected, -1);
        else return false;
        syncToNbt();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            syncToNbt();
            close();
            return true;
        }
        if (selected < 0) return true;

        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_DOWN) {
            adjust(selected, -1);
            syncToNbt();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP) {
            adjust(selected, 1);
            syncToNbt();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void toggleAuto(int idx) {
        switch (idx) {
            case R_APERTURE -> apertureAuto = !apertureAuto;
            case R_ISO -> isoAuto = !isoAuto;
            case R_WB -> wbAuto = !wbAuto;
            case R_TONE -> toneAuto = !toneAuto;
            case R_SHUTTER -> shutterAuto = !shutterAuto;
        }
    }

    private void adjust(int idx, int dir) {
        switch (idx) {
            case R_MODE -> {
                mode = dir > 0 ? mode.next() : mode.prev();
                applyModeRules();
            }
            case R_APERTURE -> {
                if (apertureAuto) return;
                aperture = stepArray(AutoExposure.APERTURE_STOPS, aperture, dir, minAperture);
            }
            case R_ISO -> {
                if (isoAuto) return;
                iso = stepArrayInt(AutoExposure.ISO_STOPS, iso, dir);
            }
            case R_EV -> {
                evCompStep += dir;
                if (evCompStep > 15) evCompStep = 15;
                if (evCompStep < -15) evCompStep = -15;
            }
            case R_AUTOEV -> {
                autoEvStep += dir;
                if (autoEvStep > 6) autoEvStep = 6;
                if (autoEvStep < -6) autoEvStep = -6;
            }
            case R_METER -> Metering.currentMode = dir > 0
                    ? Metering.currentMode.next() : Metering.currentMode.prev();
            case R_WB -> {
                if (wbAuto) return;
                wb += dir * 100;
                if (wb < 2000) wb = 2000;
                if (wb > 10000) wb = 10000;
            }
            case R_TONE -> {
                if (toneAuto) return;
                tone += dir;
                if (tone > 4) tone = 4;
                if (tone < -4) tone = -4;
            }
            case R_FOCUS -> {
                float minF = getMinFocusDistance();
                if (dir > 0) {
                    if (Float.isInfinite(focus)) return; // 已经是无穷大，继续往上没意义
                    // 如果已经到达或超过 100，下一次滚动直接变无穷大
                    if (focus >= 100f) {
                        focus = Float.POSITIVE_INFINITY;
                    } else {
                        // 步长随距离增加，前面精细，后面粗犷
                        if (focus < 5f) focus += 0.5f;
                        else if (focus < 20f) focus += 2f;
                        else focus += 10f;
                        if (focus > 100f) focus = 100f;
                    }
                } else {
                    if (Float.isInfinite(focus)) {
                        focus = 100f; // 从无穷大退回来
                    } else {
                        if (focus < 5f) focus -= 0.5f;
                        else if (focus < 20f) focus -= 2f;
                        else focus -= 10f;
                    }
                }
                // 限制最近距离
                if (focus < minF) focus = minF;
            }

            case R_SHUTTER -> {
                if (shutterAuto) return;
                shutter = stepArrayInt(AutoExposure.SHUTTER_STOPS, shutter, dir);
            }
        }
    }

    private static int stepArrayInt(int[] arr, int v, int dir) {
        int idx = 0;
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) { idx = i; break; }
        idx += dir;
        if (idx < 0) idx = 0;
        if (idx >= arr.length) idx = arr.length - 1;
        return arr[idx];
    }

    private static float stepArray(float[] arr, float v, int dir, float minV) {
        int idx = 0;
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) { idx = i; break; }
        idx += dir;
        if (idx < 0) idx = 0;
        if (idx >= arr.length) idx = arr.length - 1;
        float result = arr[idx];
        if (result < minV) result = minV;
        return result;
    }

    @Override
    public boolean shouldPause() { return false; }
}