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

            NativeImage chromaImg = new NativeImage(noiseW, noiseH, false);
            Random cr = new Random(f * 67890L);
            for (int y = 0; y < noiseH; y++) {
                for (int x = 0; x < noiseW; x++) {
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
                    int a = cr.nextInt(256);
                    chromaImg.setColor(x, y, (a << 24) | (r_ << 16) | (g_ << 8) | b_);
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
        if (!body.isOf(ModItems.CAMERA_BODY)) return;

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
        if (!body.isOf(ModItems.CAMERA_BODY)) return;
        if (!client.player.getOffHandStack().isOf(ModItems.LENS)) return;

        int iso = currentIso;
        double luma   = computeLumaNoise(iso, true);
        double chroma = computeChromaNoise(iso, true);
        if (luma <= 0 && chroma <= 0) return;

        int scaledW = ctx.getScaledWindowWidth();
        int scaledH = ctx.getScaledWindowHeight();
        int frame = frameCounter++ % NOISE_FRAMES;

        RenderSystem.enableBlend();

        if (luma > 0) {
            RenderSystem.setShaderColor(1f, 1f, 1f, (float) luma);
            ctx.drawTexture(lumaTextures[frame], 0, 0, 0, 0, scaledW, scaledH, noiseW, noiseH);
        }
        if (chroma > 0) {
            RenderSystem.setShaderColor(1f, 1f, 1f, (float) chroma);
            ctx.drawTexture(chromaTextures[frame], 0, 0, 0, 0, scaledW, scaledH, noiseW, noiseH);
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

    public static double computeLumaNoise(int iso, boolean fullFrame) {
        double k = fullFrame ? 1.0 : 0.5;
        if (iso <= 600 * k)   return 0.00000;
        if (iso <= 800 * k)   return 0.00210;
        if (iso <= 1600 * k)  return 0.00630;
        if (iso <= 3200 * k)  return 0.01260;
        if (iso <= 6400 * k)  return 0.02240;
        if (iso <= 12800 * k) return 0.03430;
        return 0.04900;
    }

    public static double computeChromaNoise(int iso, boolean fullFrame) {
        double k = fullFrame ? 1.0 : 0.5;
        if (iso < 6400 * k)   return 0.000;
        if (iso <= 12800 * k) return 0.070;
        return 0.176;
    }
}