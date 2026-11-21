package com.example.camera.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Classic 取景器样式：参考佳能 EOS 6D 的机顶/背屏读数。
 *
 * <p>布局沿用最初版本的 OVF 黑色边框（15% 边距），边框带内以绿色数码管数字
 * 显示佳能式读数：左上曝光模式字母（A+/P/Tv/Av/M），第一行快门（1/80、0"5、
 * 2"）、光圈（F5.6）、ISO；第二行 ±3EV 曝光补偿标尺（1/3 档刻度 + 指针）；
 * 第三行白平衡（AWB / K 值）。取景区中央为 AF 合焦框。动效由弹簧推进
 * （EV 指针、合焦脉冲），与 Neon 样式一致。</p>
 *
 * <p>本类只读 {@link HudSnapshot}，不触碰相机 NBT。</p>
 */
public final class ClassicHud {

    private ClassicHud() {
    }

    private static final int GREEN_BRIGHT = 0xFF86FF86;
    private static final int GREEN = 0xFF54E854;
    private static final int GREEN_DIM = 0x9E3ECF3E;
    private static final int WHITE_DIM = 0x66FFFFFF;
    private static final int WHITE = 0xD8FFFFFF;
    /** EV 标尺指针弹簧（动效保留）。 */
    private static final Spring evPointer = new Spring(0f, 190f, 22.5f);
    /** 合焦框脉冲相位。 */
    private static float time = 0f;
    private static long lastFrameNano = System.nanoTime();

    public static void reset() {
        evPointer.snap(0f);
        time = 0f;
        lastFrameNano = System.nanoTime();
    }

    public static void render(DrawContext ctx, MinecraftClient client, HudSnapshot s) {
        if (ctx == null || client == null || s == null || client.getWindow() == null) {
            return;
        }
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        TextRenderer tr = client.textRenderer;

        // 推进动画（真实帧间隔）。
        float dt = Math.min(Math.max((System.nanoTime() - lastFrameNano) / 1_000_000_000f, 0f), 0.1f);
        lastFrameNano = System.nanoTime();
        time += dt;
        evPointer.setTarget(Math.max(-3f, Math.min(3f, s.evOffset)));
        evPointer.update(dt);

        // 模式附加效果（夜景/人像/电影），Neon/Classic 共用。
        HudEffects.render(ctx, client, s, w, h);

        // ---- OVF 黑色边框（最初版本布局）----
        int barX = (int) (w * 0.15f);
        int barY = (int) (h * 0.15f);
        int black = 0xFF000000;
        ctx.fill(0, 0, barX, h, black);
        ctx.fill(w - barX, 0, w, h, black);
        ctx.fill(barX, 0, w - barX, barY, black);
        ctx.fill(barX, h - barY, w - barX, h, black);

        // ---- 取景区：四角括号 + 中央 AF 框 ----
        renderBrackets(ctx, barX, barY, w - barX, h - barY);
        renderAfBox(ctx, s, w / 2, h / 2);

        // ---- 顶带：live 标签 + 测光模式 + 红色呼吸点 ----
        int topMidY = (barY - 9) / 2;
        if (s.liveLabel != null && !s.liveLabel.isEmpty()) {
            drawFont(ctx, tr, s.liveLabel, barX + 10, topMidY, 1f, WHITE_DIM, false);
        }
        String meter = net.minecraft.text.Text.translatable(
                com.example.camera.client.metering.Metering.currentMode.nameKey).getString();
        int meterW = tr.getWidth(meter);
        drawFont(ctx, tr, meter, w - barX - 10 - meterW - 10, topMidY, 1f, GREEN_DIM, false);
        float pulse = 0.6f + 0.4f * (0.5f + 0.5f * (float) Math.sin(time * 2.4f));
        fillCircle(ctx, w - barX - 10 - meterW - 7, topMidY + 4, 2.4f, withAlpha(0xFF5A5A, pulse));

        // ---- 底带三行：参数行 / EV 标尺 / 白平衡 ----
        boolean tall = barY >= 58;
        float glyph = tall ? 11f : 9f;
        int row1 = h - barY + 6;
        int row2 = row1 + (int) glyph + 4;
        int row3 = row2 + 13;

        renderParamRow(ctx, tr, s, barX, w - barX, row1, glyph);
        renderEvScale(ctx, barX + 20, w - barX - 20, row2);
        renderWbRow(ctx, tr, s, barX, w - barX, row3);

        // 键位提示（很暗的一行，居中于底带最后一行）。
        if (s.hint != null && !s.hint.isEmpty()) {
            int tw = tr.getWidth(s.hint);
            drawFont(ctx, tr, s.hint, (w - tw) / 2f, h - 10, 1f, 0x40FFFFFF, false);
        }
    }

    /** 第一行：模式字母 + 快门 + 光圈 + ISO（佳能布局，绿色数码管数字）。 */
    private static void renderParamRow(DrawContext ctx, TextRenderer tr, HudSnapshot s,
                                       int left, int right, int y, float glyph) {
        int midY = y + Math.round(glyph / 2f) - 4;

        // 模式字母（MC 字体，放大）。
        drawFont(ctx, tr, s.modeLetter, left + 10, midY - 4, 2.1f, GREEN_BRIGHT, false);
        float x = left + 10 + tr.getWidth(s.modeLetter) * 2.1f + 14;

        // 快门：整串数码管（1/80、0"5、2"）。
        x += SegmentDigits.draw(ctx, s.shutterText, x, y, glyph, GREEN) + 12;

        // 光圈：F 用字体，数字用数码管。
        String ap = s.apertureText;   // F5.6
        if (ap.startsWith("F")) {
            drawFont(ctx, tr, "F", x, midY - 3, 1.35f, GREEN, false);
            x += tr.getWidth("F") * 1.35f + 2;
            x += SegmentDigits.draw(ctx, ap.substring(1), x, y, glyph, GREEN) + 12;
        }

        // ISO：右对齐（标签 + 数码管数字）。
        float isoDigitsW = SegmentDigits.width(s.isoText, glyph);
        float badgeW = tr.getWidth("ISO");
        float total = badgeW + 4 + isoDigitsW;
        float rx = right - 10 - total;
        drawFont(ctx, tr, "ISO", rx, midY - 3, 1f, GREEN, false);
        SegmentDigits.draw(ctx, s.isoText, rx + badgeW + 4, y, glyph, GREEN_BRIGHT);
    }

    /**
     * 第二行：±3EV 曝光补偿标尺（最初版本「自动曝光转换」标尺的绿色重制版）。
     * 整数位显示数字，1/3 档画短刻度，指针位置由弹簧平滑。
     */
    private static void renderEvScale(DrawContext ctx, int left, int right, int y) {
        int cx = (left + right) / 2;
        int pxPerEv = Math.max(18, Math.min(30, (right - left) / 9));
        int tickColor = GREEN_DIM;
        int numberColor = GREEN;

        // 整数 -3..3 数字。
        for (int i = -3; i <= 3; i++) {
            String label = (i > 0 ? "+" : "") + i;
            float gw = SegmentDigits.width(label, 8f);
            SegmentDigits.draw(ctx, label, cx + i * pxPerEv - gw / 2f, y, 8f, numberColor);
        }

        // 1/3 档短刻度。
        int tickY = y + 10;
        for (int i = -9; i <= 9; i++) {
            if (i % 3 == 0) continue;
            int tx = cx + i * pxPerEv / 3;
            ctx.fill(tx, tickY, tx + 1, tickY + 4, tickColor);
        }

        // 指针（弹簧平滑后的 EV 位置，画成小三角楔）。
        int pointerY = tickY + 6;
        int px = Math.round(cx + evPointer.value * pxPerEv);
        ctx.fill(px - 4, pointerY, px + 4, pointerY + 1, GREEN_BRIGHT);
        ctx.fill(px - 3, pointerY + 1, px + 3, pointerY + 2, GREEN_BRIGHT);
        ctx.fill(px - 2, pointerY + 2, px + 2, pointerY + 3, GREEN_BRIGHT);
        ctx.fill(px - 1, pointerY + 3, px + 1, pointerY + 4, GREEN_BRIGHT);
    }

    /** 第三行：白平衡（AWB / K 值）+ 右侧 AF 状态。 */
    private static void renderWbRow(DrawContext ctx, TextRenderer tr, HudSnapshot s,
                                    int left, int right, int y) {
        if (s.wbAuto) {
            drawFont(ctx, tr, "AWB", left + 10, y, 1.2f, GREEN_BRIGHT, false);
        } else {
            String digits = String.valueOf(s.wbValue);
            float dw = SegmentDigits.width(digits, 9f);
            float kw = tr.getWidth("K");
            float x = left + 10;
            x += SegmentDigits.draw(ctx, digits, x, y + 1, 9f, GREEN) + 2;
            drawFont(ctx, tr, "K", x, y + 1, 1f, GREEN, false);
        }

        // AF 状态：对焦中闪烁，合焦常亮绿点。
        if (s.afState == 1) {
            if ((System.currentTimeMillis() / 300) % 2 == 0) {
                drawFont(ctx, tr, "AF", right - 10 - tr.getWidth("AF"), y, 1f, GREEN_DIM, false);
            }
        } else if (s.afState == 2) {
            float pulse = 0.7f + 0.3f * (float) Math.sin(time * 6f);
            String label = "AF";
            int lw = tr.getWidth(label);
            drawFont(ctx, tr, label, right - 10 - lw - 8, y, 1f, GREEN_BRIGHT, false);
            fillCircle(ctx, right - 10 - 3, y + 4, 2.6f, withAlpha(0x54E854, pulse));
        }
    }

    /** 取景区四角括号（白色，真实取景器的亮框线）。 */
    private static void renderBrackets(DrawContext ctx, int x0, int y0, int x1, int y1) {
        int len = 22;
        int color = 0x8CFFFFFF;
        // 左上
        ctx.fill(x0, y0, x0 + len, y0 + 1, color);
        ctx.fill(x0, y0, x0 + 1, y0 + len, color);
        // 右上
        ctx.fill(x1 - len, y0, x1, y0 + 1, color);
        ctx.fill(x1 - 1, y0, x1, y0 + len, color);
        // 左下
        ctx.fill(x0, y1 - 1, x0 + len, y1, color);
        ctx.fill(x0, y1 - len, x0 + 1, y1, color);
        // 右下
        ctx.fill(x1 - len, y1 - 1, x1, y1, color);
        ctx.fill(x1 - 1, y1 - len, x1, y1, color);
    }

    /** 中央 AF 框：空闲白括号，对焦中闪烁，合焦后绿色脉冲。 */
    private static void renderAfBox(DrawContext ctx, HudSnapshot s, int cx, int cy) {
        int half = 13;
        int len = 6;
        int color;
        if (s.afState == 2) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(time * 6f);
            color = withAlpha(0x54E854, pulse);
        } else if (s.afState == 1) {
            color = (System.currentTimeMillis() / 250) % 2 == 0 ? WHITE : 0x50FFFFFF;
        } else {
            color = 0x90FFFFFF;
        }

        // 四角短括号。
        ctx.fill(cx - half, cy - half, cx - half + len, cy - half + 1, color);
        ctx.fill(cx - half, cy - half, cx - half + 1, cy - half + len, color);
        ctx.fill(cx + half - len, cy - half, cx + half, cy - half + 1, color);
        ctx.fill(cx + half - 1, cy - half, cx + half, cy - half + len, color);
        ctx.fill(cx - half, cy + half - 1, cx - half + len, cy + half, color);
        ctx.fill(cx - half, cy + half - len, cx - half + 1, cy + half, color);
        ctx.fill(cx + half - len, cy + half - 1, cx + half, cy + half, color);
        ctx.fill(cx + half - 1, cy + half - len, cx + half, cy + half, color);

        // 合焦后中心亮一个点。
        if (s.afState == 2) {
            ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);
        }
    }

    // ==================== 绘制工具 ====================

    private static void drawFont(DrawContext ctx, TextRenderer tr, String text,
                                 float x, float y, float scale, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0f);
        m.scale(scale, scale, 1f);
        ctx.drawText(tr, text, 0, 0, color, shadow);
        m.pop();
    }

    private static void fillCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        if (r <= 0f) {
            return;
        }
        int ir = (int) Math.ceil(r);
        for (int dy = -ir; dy <= ir; dy++) {
            float span = (float) Math.sqrt(Math.max(0f, r * r - (float) dy * dy));
            int x1 = Math.round(cx - span);
            int x2 = Math.round(cx + span);
            if (x2 <= x1) x2 = x1 + 1;
            int y1 = Math.round(cy + dy);
            ctx.fill(x1, y1, x2, y1 + 1, color);
        }
    }

    private static int withAlpha(int rgb, float a) {
        int alpha = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
