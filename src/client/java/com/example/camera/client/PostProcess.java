package com.example.camera.client;

import com.example.camera.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import item.Jishen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

import java.util.Random;

public class PostProcess {
    private static final int NOISE_FRAMES = 8;
    private static Identifier[] lumaTextures;
    private static Identifier[] chromaTextures;
    private static int noiseW = 0;
    private static int noiseH = 0;
    private static int chromaW = 0;
    private static int chromaH = 0;
    private static boolean initialized = false;
    private static int frameCounter = 0;

    private static int currentWb = 5500;
    private static int currentSceneTemp = 5500;
    private static int currentIso = 100;

    public static void updateWhiteBalance(int wb, int sceneTemp) {
        currentWb = wb;
        currentSceneTemp = sceneTemp;
    }

    public static void updateIso(int iso) {
        currentIso = iso;
    }

    public static void initTextures() {
        if (initialized) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        if (client.getWindow() != null) {
            noiseW = client.getWindow().getScaledWidth();
            noiseH = client.getWindow().getScaledHeight();
        } else {
            noiseW = 1920;
            noiseH = 1080;
        }

        long total = (long) noiseW * noiseH;
        if (total > 4_000_000L) {
            double scale = Math.sqrt(4_000_000.0 / total);
            noiseW = (int) (noiseW * scale);
            noiseH = (int) (noiseH * scale);
        }

        lumaTextures = new Identifier[NOISE_FRAMES];
        chromaTextures = new Identifier[NOISE_FRAMES];

        for (int f = 0; f < NOISE_FRAMES; f++) {
            NativeImage lumaImg = new NativeImage(noiseW, noiseH, false);
            Random lr = new Random(f * 12345L);
            for (int y = 0; y < noiseH; y++) {
                for (int x = 0; x < noiseW; x++) {
                    int v = lr.nextBoolean() ? 0 : 255;
                    int a = lr.nextInt(256);
                    lumaImg.setColor(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
            NativeImageBackedTexture lumaTex = new NativeImageBackedTexture(lumaImg);
            Identifier lid = Identifier.of("camera-mod", "noise_luma_" + f);
            client.getTextureManager().registerTexture(lid, lumaTex);
            lumaTextures[f] = lid;

            // 色度噪声在 1/3 分辨率生成，再靠 GPU 双线性放大：模拟去马赛克后
            // 低频、成团的彩色噪点，而不是逐像素的彩点。
            if (chromaW == 0 || chromaH == 0) {
                chromaW = Math.max(1, (noiseW + 2) / 3);
                chromaH = Math.max(1, (noiseH + 2) / 3);
            }
            NativeImage chromaImg = new NativeImage(chromaW, chromaH, false);
            Random cr = new Random(f * 67890L);
            for (int y = 0; y < chromaH; y++) {
                for (int x = 0; x < chromaW; x++) {
                    int c = cr.nextInt(6);
                    int r_, g_, b_;
                    switch (c) {
                        case 0  -> { r_ = 255; g_ = 0;   b_ = 0;   }
                        case 1  -> { r_ = 0;   g_ = 255; b_ = 0;   }
                        case 2  -> { r_ = 0;   g_ = 0;   b_ = 255; }
                        case 3  -> { r_ = 255; g_ = 255; b_ = 0;   }
                        case 4  -> { r_ = 255; g_ = 0;   b_ = 255; }
                        default -> { r_ = 0;   g_ = 255; b_ = 255; }
                    }
                    chromaImg.setColor(x, y, 0xFF000000 | (r_ << 16) | (g_ << 8) | b_);
                }
            }
            NativeImageBackedTexture chromaTex = new NativeImageBackedTexture(chromaImg);
            Identifier cid = Identifier.of("camera-mod", "noise_chroma_" + f);
            client.getTextureManager().registerTexture(cid, chromaTex);
            chromaTextures[f] = cid;
        }
        initialized = true;
    }

    public static void render(DrawContext ctx, MinecraftClient client) {
        if (client.player == null) return;
        ItemStack body = client.player.getMainHandStack();
        if (!Jishen.isCamera(body)) return;

        NbtCompound nbt = Jishen.getSettings(body);
        int tone = nbt.getInt("tone");

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();

        renderWhiteBalance(ctx, w, h);
        renderTone(ctx, w, h, tone);
    }

    public static void renderNoise(DrawContext ctx, MinecraftClient client) {
        if (!initialized) initTextures();
        if (!initialized || client.player == null) return;

        int curW = ctx.getScaledWindowWidth();
        int curH = ctx.getScaledWindowHeight();
        if (curW != noiseW || curH != noiseH) {
            initialized = false;
            initTextures();
            if (!initialized) return;
        }

        ItemStack body = client.player.getMainHandStack();
        if (!Jishen.isCamera(body)) return;
        if (!Jishen.hasLens(body)) return;

        int iso = currentIso;
        boolean fullFrame = Jishen.isFullFrame(body);
        double luma = computeLumaNoise(iso, fullFrame);
        double chroma = computeChromaNoise(iso, fullFrame);

        // 即便 ISO 100 也铺极淡的一层颗粒：把 8 位天色渐变里的条带打散，
        // 夜景那种「一圈一圈/一条一条」的假色阶就不会出现。
        luma = Math.max(luma, 0.004);

        int scaledW = ctx.getScaledWindowWidth();
        int scaledH = ctx.getScaledWindowHeight();
        int frame = frameCounter++ % NOISE_FRAMES;

        RenderSystem.enableBlend();

        RenderSystem.setShaderColor(1f, 1f, 1f, (float) luma);
        ctx.drawTexture(lumaTextures[frame], 0, 0, 0, 0, scaledW, scaledH, scaledW, scaledH);

        if (chroma > 0.0001) {
            RenderSystem.setShaderColor(1f, 1f, 1f, (float) chroma);
            // 色度贴图只有 1/3 分辨率，UV 归一化必须用目标宽高，否则会平铺。
            ctx.drawTexture(chromaTextures[frame], 0, 0, 0, 0, scaledW, scaledH, scaledW, scaledH);
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private static void renderWhiteBalance(DrawContext ctx, int w, int h) {
        int diff = currentWb - currentSceneTemp;
        if (Math.abs(diff) < 600) return;

        if (diff > 0) {
            double t = Math.min(1.0, (diff - 600) / 4400.0);
            int a = (int) (t * 0.18 * 255);
            ctx.fill(0, 0, w, h, (a << 24) | 0xFF8800);
        } else {
            double t = Math.min(1.0, (-diff - 600) / 2900.0);
            int a = (int) (t * 0.18 * 255);
            ctx.fill(0, 0, w, h, (a << 24) | 0x0088FF);
        }
    }

    private static void renderTone(DrawContext ctx, int w, int h, int tone) {
        if (tone == 0) return;
        if (tone > 0) {
            int a = (int) (tone / 4.0 * 0.12 * 255);
            ctx.fill(0, 0, w, h, (a << 24) | 0xFF00FF);
        } else {
            int a = (int) (-tone / 4.0 * 0.12 * 255);
            ctx.fill(0, 0, w, h, (a << 24) | 0x00FF00);
        }
    }

    /** 全画幅 / 半画幅裁切系数：小底同 ISO 更噪。 */
    public static double cropFactor(boolean fullFrame) {
        return fullFrame ? 1.0 : 1.5;
    }

    /**
     * Snapmatica 的 ISO→亮度噪声表（单位：8 位灰阶）。100 时干净，之后按 1/3
     * 档快速上升；再乘画幅裁切系数实现「等效 ISO」。
     */
    private static double isoToNoiseSigma(int iso) {
        if (iso <= 100) return 0.0;
        if (iso <= 200) return 1.5;
        if (iso <= 400) return 3.0;
        if (iso <= 800) return 6.0;
        if (iso <= 1600) return 11.0;
        if (iso <= 3200) return 18.0;
        if (iso <= 6400) return 28.0;
        if (iso <= 12800) return 42.0;
        return 60.0;
    }

    /** 亮度噪声幅度（0..1），HUD 和成片共用。 */
    public static double computeLumaNoise(int iso, boolean fullFrame) {
        // 0.65：叠加贴图自身的随机 alpha 会再放大一次幅度，这里校准到
        // 「ISO 3200 全画幅约 ±3 个 8 位灰阶」的观感。
        return (isoToNoiseSigma(iso) / 255.0) * Math.max(0.2, cropFactor(fullFrame)) * 0.65;
    }

    /**
     * 色度噪声幅度：低 ISO 几乎没有，高 ISO 逐渐接近亮度噪声。成片里按 3×3
     * 粗网格生成，模拟去马赛克带来的空间相关性。
     */
    public static double computeChromaNoise(int iso, boolean fullFrame) {
        double luma = computeLumaNoise(iso, fullFrame);
        double stops = Math.max(0.0, Math.log(iso / 100.0) / Math.log(2.0));
        double ratio = Math.min(0.80, 0.15 + 0.085 * stops);
        return luma * ratio;
    }
}