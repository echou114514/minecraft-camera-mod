package com.example.camera.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * 模式附加效果（夜景冷色压暗 / 人像暗角 / 电影黑边），Neon 与 Classic 共用。
 * 坐标为缩放后的窗口像素（GUI 尺度）。
 */
public final class HudEffects {

    private HudEffects() {
    }

    private static final Identifier VIGNETTE_ID = Identifier.of("camera-mod", "hud_vignette");
    /**
     * 512 px 而不是 128 px：径向渐变被拉伸到整屏时，8 位 alpha 的量化台阶会变成
     * 一圈圈可见的同心环。提高分辨率 + 生成时加抖动把台阶打散（见 {@link #ensureVignette}）。
     */
    private static final int VIGNETTE_SIZE = 512;
    /** 4×4 Bayer 抖动矩阵，用于打散 alpha 渐变的色阶。 */
    private static final int[][] BAYER4 = {
            { 0, 8, 2, 10 },
            { 12, 4, 14, 6 },
            { 3, 11, 1, 9 },
            { 15, 7, 13, 5 }
    };

    /** 按当前模式渲染附加效果（作用于世界之上、HUD 之下）。 */
    public static void render(DrawContext ctx, MinecraftClient client, HudSnapshot s, int w, int h) {
        // 夜景：整体冷色压暗 + 稍强的暗角，营造低光长曝氛围。
        if (s.night) {
            ctx.fill(0, 0, w, h, withAlpha(0x0A1A2E, 0.30f));
            drawVignette(ctx, client, w, h, 0.55f);
        }

        // 人像：柔和的背景虚化暗角（径向渐变贴图）。
        if (s.portrait) {
            drawVignette(ctx, client, w, h, 0.62f);
        }

        // 电影：很淡的灰绿平灰叠加（近似 Log）+ 上下黑色档条。
        if (s.cinemaBars) {
            ctx.fill(0, 0, w, h, withAlpha(0x2E332B, 0.07f));
            int bar = Math.round(h * 0.12f);
            ctx.fill(0, 0, w, bar, 0xF8000000);
            ctx.fill(0, h - bar, w, h, 0xF8000000);
        }
    }

    /** 把径向渐变暗角贴图铺满全屏，{@code alphaMul} 控制强度。 */
    private static void drawVignette(DrawContext ctx, MinecraftClient client, int w, int h, float alphaMul) {
        ensureVignette(client);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alphaMul);
        // 最后两个参数是 UV 归一化用的「纹理尺寸」。必须传目标宽高，否则
        // 1920/128 = 15 会让暗角贴图在屏幕上平铺成 15×8 个圆形。
        ctx.drawTexture(VIGNETTE_ID, 0, 0, 0, 0, w, h, w, h);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** 懒加载生成一张 512×512 的径向渐变贴图（中心透明 → 边缘变黑）。 */
    private static void ensureVignette(MinecraftClient client) {
        if (client.getTextureManager().getOrDefault(VIGNETTE_ID, null) != null) {
            return;
        }
        NativeImage img = new NativeImage(VIGNETTE_SIZE, VIGNETTE_SIZE, false);
        float inner = 0.38f;    // 38% 半径以内透明，向外加深
        for (int y = 0; y < VIGNETTE_SIZE; y++) {
            for (int x = 0; x < VIGNETTE_SIZE; x++) {
                float nx = ((x + 0.5f) / VIGNETTE_SIZE) * 2f - 1f;
                float ny = ((y + 0.5f) / VIGNETTE_SIZE) * 2f - 1f;
                float r = (float) Math.sqrt(nx * nx + ny * ny);
                float t = clamp01((r - inner) / (1f - inner));
                t = t * t * (3f - 2f * t);     // smoothstep，边缘过渡更柔和
                // 抖动一个 alpha 台阶，避免 8 位渐变拉伸后出现同心圆环。
                float dither = (BAYER4[y & 3][x & 3] / 16f) - 0.5f;
                float a = t * 0.62f + dither / 255f;
                int alpha = Math.max(0, Math.min(255, Math.round(a * 255f)));
                img.setColor(x, y, alpha << 24);    // 黑色，仅 alpha 有渐变
            }
        }
        client.getTextureManager().registerTexture(VIGNETTE_ID, new NativeImageBackedTexture(img));
    }

    /** 预热暗角贴图：首次抬相机时不再在渲染线程里同步生成 512×512 渐变。 */
    public static void prewarm(MinecraftClient client) {
        if (client != null && client.getTextureManager() != null) {
            ensureVignette(client);
        }
    }

    private static int withAlpha(int rgb, float a) {
        int alpha = Math.round(clamp01(a) * 255f);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
