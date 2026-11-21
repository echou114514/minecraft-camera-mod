package com.example.camera.client.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 七段数码管数字渲染器（Classic 取景器样式）。
 *
 * <p>只覆盖数字与少量符号（/ " . - +），字母（A+/P/Tv/Av/M、ISO、AWB、K）
 * 由调用方用 Minecraft 字体以同色绘制，组合出真实的机顶液晶观感。</p>
 */
public final class SegmentDigits {

    private SegmentDigits() {
    }

    /** 设计空间：一个字形高 10 单位、宽 6 单位、笔画粗 1.6 单位。 */
    private static final float H = 10f;
    private static final float W = 6f;
    private static final float T = 1.6f;
    private static final float SPACING = 2.4f;

    private static final int A = 1 << 0;   // 上横
    private static final int B = 1 << 1;   // 右上竖
    private static final int C = 1 << 2;   // 右下竖
    private static final int D = 1 << 3;   // 下横
    private static final int E = 1 << 4;   // 左下竖
    private static final int F = 1 << 5;   // 左上竖
    private static final int G = 1 << 6;   // 中横

    private static int segments(char ch) {
        return switch (ch) {
            case '0' -> A | B | C | D | E | F;
            case '1' -> B | C;
            case '2' -> A | B | G | E | D;
            case '3' -> A | B | G | C | D;
            case '4' -> F | G | B | C;
            case '5' -> A | F | G | C | D;
            case '6' -> A | F | G | E | D | C;
            case '7' -> A | B | C;
            case '8' -> A | B | C | D | E | F | G;
            case '9' -> A | B | C | D | F | G;
            case '-' -> G;
            case '+' -> 1 << 7;   // 特殊绘制：中横 + 上下短竖
            case '/' -> 1 << 8;   // 特殊绘制：对角线
            case '"' -> 1 << 9;   // 特殊绘制：右上两小点
            case '.' -> 1 << 10;  // 特殊绘制：右下小方块
            default -> 0;
        };
    }

    /** 渲染一串数码管字符，返回占用宽度（GUI 像素）。 */
    public static float draw(DrawContext ctx, String text, float x, float y, float glyphHeight, int color) {
        float s = glyphHeight / H;
        MatrixStack m = ctx.getMatrices();
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != ' ') {
                m.push();
                m.translate(cx, y, 0f);
                m.scale(s, s, 1f);
                drawGlyph(ctx, ch, color);
                m.pop();
            }
            cx += (W + SPACING) * s;
        }
        return cx - x;
    }

    /** 测量一串数码管字符的宽度（GUI 像素）。 */
    public static float width(String text, float glyphHeight) {
        float s = glyphHeight / H;
        return text.length() * (W + SPACING) * s - SPACING * s;
    }

    private static void drawGlyph(DrawContext ctx, char ch, int color) {
        int seg = segments(ch);
        if (ch == '/') {
            // 对角线：从右上到左下逐行填充。
            for (int y = 0; y <= (int) H; y++) {
                float t = y / H;
                float x = (W - T) - t * (W - T);
                ctx.fill(Math.round(x), y, Math.round(x + T), y + 1, color);
            }
            return;
        }
        if (ch == '"') {
            ctx.fill(Math.round(W - 2.2f), 0, Math.round(W - 1.6f), 3, color);
            ctx.fill(Math.round(W - 1.2f), 0, Math.round(W - 0.6f), 3, color);
            return;
        }
        if (ch == '.') {
            ctx.fill(Math.round(W - T), Math.round(H - T), (int) W, (int) H, color);
            return;
        }
        if (ch == '+') {
            // 中横 + 上下短竖。
            ctx.fill(Math.round(1.2f), Math.round(H / 2 - T / 2), Math.round(W - 1.2f), Math.round(H / 2 + T / 2), color);
            float midX = W / 2 - T / 2;
            ctx.fill(Math.round(midX), 2, Math.round(midX + T), Math.round(H / 2 - T / 2), color);
            ctx.fill(Math.round(midX), Math.round(H / 2 + T / 2), Math.round(midX + T), Math.round(H - 2), color);
            return;
        }
        if ((seg & A) != 0) ctx.fill(Math.round(0.7f), 0, Math.round(W - 0.7f), Math.round(T), color);
        if ((seg & G) != 0) ctx.fill(Math.round(0.7f), Math.round(H / 2 - T / 2), Math.round(W - 0.7f), Math.round(H / 2 + T / 2), color);
        if ((seg & D) != 0) ctx.fill(Math.round(0.7f), Math.round(H - T), Math.round(W - 0.7f), (int) H, color);
        if ((seg & F) != 0) ctx.fill(0, Math.round(0.7f), Math.round(T), Math.round(H / 2 - 0.5f), color);
        if ((seg & B) != 0) ctx.fill(Math.round(W - T), Math.round(0.7f), (int) W, Math.round(H / 2 - 0.5f), color);
        if ((seg & E) != 0) ctx.fill(0, Math.round(H / 2 + 0.5f), Math.round(T), Math.round(H - 0.7f), color);
        if ((seg & C) != 0) ctx.fill(Math.round(W - T), Math.round(H / 2 + 0.5f), (int) W, Math.round(H - 0.7f), color);
    }
}
