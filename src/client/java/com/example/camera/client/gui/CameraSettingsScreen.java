package com.example.camera.client.gui;

import com.example.camera.client.AutoExposure;
import com.example.camera.client.ExposureMode;
import com.example.camera.client.hud.SegmentDigits;
import com.example.camera.client.metering.Metering;
import com.example.camera.data.LensSpec;
import com.example.camera.item.CameraBodyItem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class CameraSettingsScreen extends Screen {

    private static final int MAX_PANEL_W = 500;
    private static final int MAX_PANEL_H = 200;
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

    /** Panel size adapts to the window so GUI scale 4 / small windows fit. */
    private int panelW = MAX_PANEL_W;
    private int panelH = MAX_PANEL_H;

    private ItemStack target;
    private ExposureMode mode;
    private int iso, shutter, wb, tone;
    private int evCompStep = 0;
    private int autoEvStep = 0;
    private float focus, aperture;
    private float minAperture = 0.95f;
    private boolean isoAuto, shutterAuto, apertureAuto, wbAuto, toneAuto, focusAuto;
    private boolean evAuto = true;

    private int selected = R_ISO;

    // 根据焦距计算最近对焦距离 (焦距越长，最近对焦距离越远)
    private float getMinFocusDistance() {
        int focal = 50;
        if (target != null) {
            LensSpec spec = CameraBodyItem.getLensSpec(target);
            focal = spec.focalLength;
        }
        return Math.max(0.5f, focal / 100.0f);
    }

    public static void open(ItemStack stack) {
        MinecraftClient.getInstance().setScreen(new CameraSettingsScreen(stack));
    }

    public CameraSettingsScreen(ItemStack stack) {
        super(Text.translatable("gui.camera-mod.title"));
        this.target = stack;
        loadFromStack(stack);
    }

    private void loadFromStack(ItemStack stack) {
        NbtCompound nbt = CameraBodyItem.getSettings(stack);

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
        evAuto = CameraBodyItem.isEvAuto(nbt);

        isoAuto = nbt.getBoolean("isoAuto");
        shutterAuto = nbt.getBoolean("shutterAuto");
        apertureAuto = nbt.getBoolean("apertureAuto");
        wbAuto = nbt.getBoolean("wbAuto");
        toneAuto = nbt.getBoolean("toneAuto");
        focusAuto = nbt.getBoolean("focusAuto");

        LensSpec spec = CameraBodyItem.getLensSpec(stack);
        minAperture = spec.maxAperture;
        if (aperture < minAperture) aperture = minAperture;
    }

    /**
     * Overwrites only the fields this screen manages on top of the *current*
     * settings, so keys the screen does not know about (hudMode, future
     * additions) survive closing the panel.
     */
    private void syncToNbt() {
        if (target == null || !CameraBodyItem.isCamera(target)) return;
        NbtCompound nbt = CameraBodyItem.getSettings(target);
        nbt.putString("mode", mode.name);
        nbt.putInt("iso", iso);
        nbt.putInt("shutter", shutter);
        nbt.putFloat("aperture", aperture);
        nbt.putInt("wb", wb);
        nbt.putInt("tone", tone);
        nbt.putFloat("focus", focus);
        nbt.putInt("evCompStep", evCompStep);
        nbt.putInt("autoEvStep", autoEvStep);
        nbt.putBoolean("evAuto", evAuto);
        nbt.putBoolean("isoAuto", isoAuto);
        nbt.putBoolean("shutterAuto", shutterAuto);
        nbt.putBoolean("apertureAuto", apertureAuto);
        nbt.putBoolean("wbAuto", wbAuto);
        nbt.putBoolean("toneAuto", toneAuto);
        nbt.putBoolean("focusAuto", focusAuto);
        CameraBodyItem.setSettings(target, nbt);
    }

    /** The stack reference can go stale after inventory syncs; re-grab the main hand. */
    private void refreshTarget() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        ItemStack main = client.player.getMainHandStack();
        if (CameraBodyItem.isCamera(main) && (target == null || main.getItem() == target.getItem())) {
            target = main;
        }
    }

    /** Mode switches force their canonical auto flags; opening the panel never does. */
    private void applyModeRules() {
        switch (mode) {
            case A_PLUS -> { isoAuto = true; shutterAuto = true; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case P -> { shutterAuto = true; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case TV -> { shutterAuto = false; apertureAuto = true; wbAuto = true; toneAuto = true; }
            case AV -> { shutterAuto = true; apertureAuto = false; wbAuto = true; toneAuto = true; }
            case M, B -> { shutterAuto = false; apertureAuto = false; wbAuto = true; toneAuto = true; }
        }
    }

    @Override
    public void removed() {
        syncToNbt();
        super.removed();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        refreshTarget();

        panelW = Math.min(MAX_PANEL_W, this.width - 20);
        panelH = Math.min(MAX_PANEL_H, this.height - 20);
        int w = this.width;
        int h = this.height;
        int px = (w - panelW) / 2;
        int py = (h - panelH) / 2;

        // 佳能 6D 背屏：近全黑底 + 白色读数 + 细分区框。
        ctx.fill(px, py, px + panelW, py + panelH, 0xF2050505);
        drawFrame(ctx, px, py, panelW, panelH);

        int innerX = px + 12;
        int innerW = panelW - 24;
        int curY = py + 10;

        // ---- 行 1（佳能顶行）：模式字母 | 快门 | F 值 | ISO ----
        int colMode = Math.round(innerW * 0.17f);
        int colShutter = Math.round(innerW * 0.27f);
        int colAperture = Math.round(innerW * 0.27f);
        int colIso = innerW - colMode - colShutter - colAperture;
        int rowH1 = 44;
        drawModeBig(ctx, innerX, curY, colMode, rowH1);
        drawSegmentValue(ctx, innerX + colMode, curY, colShutter, rowH1,
                Metering.formatShutterCanon(shutter), R_SHUTTER, autoStr(shutterAuto));
        drawApertureValue(ctx, innerX + colMode + colShutter, curY, colAperture, rowH1,
                R_APERTURE, autoStr(apertureAuto));
        drawIsoValue(ctx, innerX + colMode + colShutter + colAperture, curY, colIso, rowH1,
                R_ISO, autoStr(isoAuto));
        curY += rowH1 + 6;

        // ---- 行 2：曝光补偿标尺（-3..0..+3，1/3 档刻度）----
        drawEvBar(ctx, innerX, curY, innerW, 40, evCompStep, R_EV, evAuto);
        curY += 40 + 6;

        // ---- 行 3：白平衡 | 色调 | 对焦 ----
        int rowH3 = 30;
        int colW3 = innerW / 3;
        drawBoxedValue(ctx, innerX, curY, colW3, rowH3,
                wbAuto ? "AWB" : wb + "K", R_WB, autoStr(wbAuto));
        drawBoxedValue(ctx, innerX + colW3, curY, colW3, rowH3,
                Text.translatable("gui.camera-mod.tone", fmtSigned(tone)).getString(),
                R_TONE, autoStr(toneAuto));
        String focusStr = Float.isInfinite(focus) ? "∞" : String.format("%.1fm", focus);
        drawBoxedValue(ctx, innerX + 2 * colW3, curY, innerW - 2 * colW3, rowH3,
                Text.translatable("gui.camera-mod.focus", focusStr).getString(),
                R_FOCUS, autoStr(focusAuto));
        curY += rowH3 + 6;

        // ---- 行 4：测光模式 | 自动 EV 偏移 ----
        int colW4 = innerW / 2;
        drawBoxedValue(ctx, innerX, curY, colW4, rowH3,
                Metering.currentMode.displayName(), R_METER, "");
        drawBoxedValue(ctx, innerX + colW4, curY, innerW - colW4, rowH3,
                "A-EV " + fmtAutoEv(autoEvStep), R_AUTOEV, "");
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

    /** 记录区域并画细分区框（佳能屏幕上的图标格）。 */
    private void drawRegionBox(DrawContext ctx, int index, int x, int y, int w, int h) {
        regions[index][0] = x;
        regions[index][1] = y;
        regions[index][2] = w;
        regions[index][3] = h;
        int c = 0x4DFFFFFF;
        ctx.fill(x, y, x + w, y + 1, c);
        ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c);
        ctx.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void drawAutoMark(DrawContext ctx, int x, int y, int w, String autoMark, int selColor) {
        if (!autoMark.isEmpty()) {
            ctx.drawText(textRenderer, autoMark, x + w - 10, y + 2,
                    autoMark.equals("A") ? 0xFF55FF55 : 0xFFAAAAAA, false);
        }
    }

    /** 行 1 的大号模式字母（A+/P/Tv/Av/M），点击循环切换。 */
    private void drawModeBig(DrawContext ctx, int x, int y, int w, int h) {
        drawRegionBox(ctx, R_MODE, x, y, w, h);
        boolean sel = (selected == R_MODE);
        int color = sel ? COLOR_SEL : COLOR;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(3f, 3f, 1f);
        int textW = textRenderer.getWidth(mode.name);
        int textH = 9;
        ctx.drawText(textRenderer, mode.name,
                x / 3 + (w / 3 - textW) / 2, y / 3 + (h / 3 - textH) / 2, color, false);
        m.pop();
    }

    /** 数码管数字值（快门等），水平垂直居中 + 右侧 A/M。 */
    private void drawSegmentValue(DrawContext ctx, int x, int y, int w, int h,
                                  String value, int index, String autoMark) {
        drawRegionBox(ctx, index, x, y, w, h);
        boolean sel = (selected == index);
        float gh = 15f;
        float tw = SegmentDigits.width(value, gh);
        SegmentDigits.draw(ctx, value, x + (w - tw) / 2f, y + (h - gh) / 2f, gh,
                sel ? COLOR_SEL : COLOR);
        drawAutoMark(ctx, x, y, w, autoMark, COLOR_SEL);
    }

    /** 光圈单元：F 用字体 + 数码管数字。 */
    private void drawApertureValue(DrawContext ctx, int x, int y, int w, int h,
                                   int index, String autoMark) {
        drawRegionBox(ctx, index, x, y, w, h);
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        String ap = Metering.formatAperture(aperture);   // F5.6
        String digits = ap.substring(1);
        float gh = 15f;
        float fw = textRenderer.getWidth("F") * 1.3f;
        float dw = SegmentDigits.width(digits, gh);
        float total = fw + 2 + dw;
        float sx = x + (w - total) / 2f;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(sx, y + (h - 9) / 2f - 3, 0f);
        m.scale(1.3f, 1.3f, 1f);
        ctx.drawText(textRenderer, "F", 0, 0, color, false);
        m.pop();
        SegmentDigits.draw(ctx, digits, sx + fw + 2, y + (h - gh) / 2f, gh, color);
        drawAutoMark(ctx, x, y, w, autoMark, COLOR_SEL);
    }

    /** ISO 单元：ISO 徽标 + 数码管数字。 */
    private void drawIsoValue(DrawContext ctx, int x, int y, int w, int h,
                              int index, String autoMark) {
        drawRegionBox(ctx, index, x, y, w, h);
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        String digits = String.valueOf(iso);
        float gh = 15f;
        float badgeW = textRenderer.getWidth("ISO") * 1.1f;
        float dw = SegmentDigits.width(digits, gh);
        float total = badgeW + 4 + dw;
        float sx = x + (w - total) / 2f;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(sx, y + (h - 9) / 2f - 2, 0f);
        m.scale(1.1f, 1.1f, 1f);
        ctx.drawText(textRenderer, "ISO", 0, 0, color, false);
        m.pop();
        SegmentDigits.draw(ctx, digits, sx + badgeW + 4, y + (h - gh) / 2f, gh, color);
        drawAutoMark(ctx, x, y, w, autoMark, COLOR_SEL);
    }

    /** 行 3/4 的小分区：文字 + 细框 + 右侧 A/M。 */
    private void drawBoxedValue(DrawContext ctx, int x, int y, int w, int h,
                                String value, int index, String autoMark) {
        drawRegionBox(ctx, index, x, y, w, h);
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(1.4f, 1.4f, 1f);
        int textW = textRenderer.getWidth(value);
        ctx.drawText(textRenderer, value,
                (int) ((x + 8) / 1.4f), (int) ((y + h / 2f - 5) / 1.4f), color, false);
        m.pop();
        drawAutoMark(ctx, x, y, w, autoMark, COLOR_SEL);
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
        // Clicking empty space keeps the current selection so the scroll wheel
        // keeps working.
        return super.mouseClicked(mx, my, button);
    }

    /** 该区域在当前模式下是否允许切换 A/M */
    private boolean canToggleAuto(int idx) {
        return switch (idx) {
            case R_ISO -> mode.isoSwitchable();
            case R_SHUTTER -> mode.shutterSwitchable();
            case R_APERTURE -> mode.apertureSwitchable();
            case R_WB, R_TONE -> mode.wbSwitchable();
            case R_EV -> true;
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
            close();
            return true;
        }
        if (selected < 0) return super.keyPressed(keyCode, scanCode, modifiers);

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
            case R_EV -> evAuto = !evAuto;
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
                evAuto = false;
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
        // Values written outside this table (e.g. the HUD's 1/48 cinema
        // shutter) snap to the nearest stop instead of jumping to index 0.
        int idx = AutoExposure.nearestIndex(arr, v);
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

    /**
     * 曝光补偿标尺（佳能式）：整数位数码管数字，1/3 档短刻度，指针楔，
     * 右侧 A/M 切换。选中时高亮。
     */
    private void drawEvBar(DrawContext ctx, int x, int y, int w, int h,
                           int step, int index, boolean auto) {
        drawRegionBox(ctx, index, x, y, w, h);
        boolean sel = (selected == index);
        int color = sel ? COLOR_SEL : COLOR;

        int cx = x + w / 2;
        int pxPerEv = Math.max(18, Math.min(28, (w - 40) / 9));
        int numberY = y + 6;
        int tickY = numberY + 11;

        for (int i = -3; i <= 3; i++) {
            String label = (i > 0 ? "+" : "") + i;
            float gw = SegmentDigits.width(label, 8f);
            SegmentDigits.draw(ctx, label, cx + i * pxPerEv - gw / 2f, numberY, 8f, color);
        }
        for (int i = -9; i <= 9; i++) {
            if (i % 3 == 0) continue;
            int tx = cx + i * pxPerEv / 3;
            ctx.fill(tx, tickY, tx + 1, tickY + 4, 0x70FFFFFF);
        }

        String evAutoMark = auto ? "A" : "M";
        ctx.drawText(textRenderer, evAutoMark, x + w - 10, y + 2,
                auto ? 0xFF55FF55 : 0xFFAAAAAA, false);

        double ev = step / 3.0;
        int pointerX = cx + (int) Math.round(ev * pxPerEv);
        int pointerY = tickY + 6;
        ctx.fill(pointerX - 3, pointerY, pointerX + 3, pointerY + 1, color);
        ctx.fill(pointerX - 2, pointerY + 1, pointerX + 2, pointerY + 2, color);
        ctx.fill(pointerX - 1, pointerY + 2, pointerX + 1, pointerY + 3, color);
    }

    @Override
    public boolean shouldPause() { return false; }
}
