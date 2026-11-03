package com.example.camera.client;

import com.example.camera.client.dof.DepthOfField;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class HighResCapture {
    public static final int FULL_W = 7680;
    public static final int FULL_H = 4320;

    private static FloatBuffer cachedColorBuf = null;
    private static FloatBuffer cachedDepthBuf = null;
    private static FloatBuffer cachedDofBuf = null;
    private static ByteBuffer cachedPixelBuffer = null;

    public static float snapshotExposureEV = 0f;
    public static int snapshotWb = 5500;
    public static int snapshotSceneTemp = 5500;
    public static int snapshotTone = 0;
    public static int snapshotIso = 100;
    public static float snapshotSensorHeight = 24.0f;

    public static float snapshotFocusDistance = 10f;
    public static float snapshotFocalLength = 50f;
    public static float snapshotAperture = 2.8f;
    public static int snapshotShutter = 125;

    public static int snapshotMeteringMode = 1;
    public static int snapshotExposureProgram = 1;
    public static int snapshotExposureMode = 1;
    public static boolean snapshotWbAuto = true;
    public static String snapshotLensName = "";
    public static String snapshotCamName = "";
    public static String snapshotDateStr = "";

    public static float cameraNear = 0.05f;
    public static float cameraFar = 512f;

    private enum State { IDLE, WAITING, CAPTURING }
    private static State state = State.IDLE;
    private static int waitFrames = 0;
    private static int restoreWidth;
    private static int restoreHeight;
    private static boolean resolutionChanged;
    private static boolean restorePending;
    private static boolean captureCompleted;

    public static boolean isCapturing() { return state == State.CAPTURING; }
    public static boolean isActive() { return state == State.CAPTURING; }
    public static boolean isWaiting() { return state == State.WAITING; }
    public static boolean consumeCompletion() {
        boolean completed = captureCompleted;
        captureCompleted = false;
        return completed;
    }

    public static boolean request(MinecraftClient client) {
        if (state != State.IDLE || restorePending) return false;
        if (!RenderSystem.isOnRenderThread()) return false;

        var window = client.getWindow();
        restoreWidth = window.getFramebufferWidth();
        restoreHeight = window.getFramebufferHeight();
        if (FULL_W > RenderSystem.maxSupportedTextureSize() || FULL_H > RenderSystem.maxSupportedTextureSize()) {
            return false;
        }

        try {
            resolutionChanged = true;
            window.setFramebufferWidth(FULL_W);
            window.setFramebufferHeight(FULL_H);
            client.onResolutionChanged();

            Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer.textureWidth != FULL_W || framebuffer.textureHeight != FULL_H) {
                restoreResolution(client);
                return false;
            }
            state = State.WAITING;
            waitFrames = 1;
            return true;
        } catch (Throwable t) {
            state = State.IDLE;
            restorePending = true;
            System.err.println("[CameraMod] 高分辨率目标创建失败，取消拍摄: " + t);
            return false;
        }
    }

    public static void restoreAfterRender(MinecraftClient client) {
        if (restorePending || (resolutionChanged && state == State.IDLE)) restoreResolution(client);
    }

    public static void afterFrame(MinecraftClient client) {
        if (state == State.WAITING) {
            if (--waitFrames <= 0) state = State.CAPTURING;
            return;
        }
        if (state != State.CAPTURING) return;

        Framebuffer fb = client.getFramebuffer();
        if (fb.textureWidth != FULL_W || fb.textureHeight != FULL_H) {
            state = State.IDLE;
            restorePending = true;
            return;
        }

        try {
            if (cachedColorBuf == null) {
                cachedColorBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H * 4);
                cachedDepthBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H);
                cachedDofBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H * 4);
                cachedPixelBuffer = BufferUtils.createByteBuffer(FULL_W * FULL_H * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
            } else {
                cachedColorBuf.clear();
                cachedDepthBuf.clear();
                cachedDofBuf.clear();
                cachedPixelBuffer.clear();
            }

            fb.beginRead();
            GL11.glReadPixels(0, 0, FULL_W, FULL_H, GL11.GL_RGBA, GL11.GL_FLOAT, cachedColorBuf);
            GL11.glReadPixels(0, 0, FULL_W, FULL_H, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, cachedDepthBuf);
            fb.endRead();

            long dofStart = System.currentTimeMillis();
            applyDepthOfField(cachedColorBuf, cachedDepthBuf, cachedDofBuf, FULL_W, FULL_H,
                    snapshotFocusDistance, snapshotFocalLength, snapshotAperture, cameraNear, cameraFar);
            System.out.println("[CameraMod] 景深耗时: " + (System.currentTimeMillis() - dofStart) + "ms");

            float exposureFactor = (float) Math.pow(2, snapshotExposureEV);
            float wbAlpha = 0f, wbColorR = 0f, wbColorG = 0f, wbColorB = 0f;
            int wbDiff = snapshotWb - snapshotSceneTemp;
            if (Math.abs(wbDiff) >= 600) {
                if (wbDiff > 0) { double t = Math.min(1.0, (wbDiff - 600) / 4400.0); wbAlpha = (float)(t*0.18); wbColorR=1f; wbColorG=136f/255f; wbColorB=0f; }
                else { double t = Math.min(1.0, (-wbDiff - 600) / 2900.0); wbAlpha = (float)(t*0.18); wbColorR=0f; wbColorG=136f/255f; wbColorB=1f; }
            }

            float toneAlpha = 0f, toneColorR = 0f, toneColorG = 0f, toneColorB = 0f;
            if (snapshotTone > 0) { toneAlpha = (float)(snapshotTone/4.0*0.12); toneColorR=1f; toneColorG=0f; toneColorB=1f; }
            else if (snapshotTone < 0) { toneAlpha = (float)(-snapshotTone/4.0*0.12); toneColorR=0f; toneColorG=1f; toneColorB=0f; }

            for (int y = 0; y < FULL_H; y++) {
                for (int x = 0; x < FULL_W; x++) {
                    int srcY = FULL_H - 1 - y;
                    int src = (srcY * FULL_W + x) * 4;

                    float rf = cachedDofBuf.get(src) * exposureFactor;
                    float gf = cachedDofBuf.get(src + 1) * exposureFactor;
                    float bf = cachedDofBuf.get(src + 2) * exposureFactor;

                    if (wbAlpha > 0) { rf = rf*(1-wbAlpha) + wbColorR*wbAlpha; gf = gf*(1-wbAlpha) + wbColorG*wbAlpha; bf = bf*(1-wbAlpha) + wbColorB*wbAlpha; }
                    if (toneAlpha > 0) { rf = rf*(1-toneAlpha) + toneColorR*toneAlpha; gf = gf*(1-toneAlpha) + toneColorG*toneAlpha; bf = bf*(1-toneAlpha) + toneColorB*toneAlpha; }

                    cachedPixelBuffer.putFloat(rf);
                    cachedPixelBuffer.putFloat(gf);
                    cachedPixelBuffer.putFloat(bf);
                }
            }
            cachedPixelBuffer.flip();

            File dir = new File(client.runDirectory, "screenshots");
            dir.mkdirs();
            String name = "camera_20mp_" + System.currentTimeMillis() + ".tiff";
            File file = new File(dir, name);

            TiffWriter.writeRgb32fBuffer(file, cachedPixelBuffer, FULL_W, FULL_H,
                    snapshotIso, snapshotShutter, snapshotAperture, snapshotFocalLength, snapshotExposureEV,
                    snapshotWb, snapshotMeteringMode, snapshotExposureProgram, snapshotExposureMode,
                    snapshotWbAuto, snapshotLensName, snapshotCamName, snapshotDateStr);

            if (client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal("[相机] 20MP TIFF: " + name), false);
            }
            System.gc();

        } catch (Throwable t) {
            System.err.println("[CameraMod] 保存失败: " + t.getMessage());
            t.printStackTrace();
        } finally {
            state = State.IDLE;
            waitFrames = 0;
            restorePending = true;
            captureCompleted = true;
        }
    }

    private static void restoreResolution(MinecraftClient client) {
        if (!resolutionChanged) return;
        try {
            client.getWindow().setFramebufferWidth(restoreWidth);
            client.getWindow().setFramebufferHeight(restoreHeight);
            client.onResolutionChanged();
        } catch (Throwable t) {
            System.err.println("[CameraMod] 恢复窗口渲染尺寸失败: " + t);
        } finally {
            resolutionChanged = false;
            restorePending = false;
            state = State.IDLE;
            waitFrames = 0;
        }
    }

    private static void applyDepthOfField(FloatBuffer src, FloatBuffer depth, FloatBuffer dst, int w, int h, float focusDist, float focalMm, float aperture, float near, float far) {
        int n = w * h;
        float[] coc = new float[n];
        float[] cocTemp = new float[n];

        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            float z = depth.get(i);
            float zNdc = z * 2f - 1f;
            float linear = (z < 1.0f) ? 2f * near * far / (far + near - zNdc * (far - near)) : far;
            float c = DepthOfField.computeCocPixels(linear, focusDist, focalMm, aperture, h, snapshotSensorHeight);
            coc[i] = DepthOfField.clampCoc(c, h);
        });

        // 7x7 羽化窗口，抹平硬边
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sum = 0; int count = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = x + dx;
                    if (sx >= 0 && sx < w) { sum += coc[y * w + sx]; count++; }
                }
                cocTemp[y * w + x] = sum / count;
            }
        });
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sum = 0; int count = 0;
                for (int dy = -3; dy <= 3; dy++) {
                    int sy = y + dy;
                    if (sy >= 0 && sy < h) { sum += cocTemp[sy * w + x]; count++; }
                }
                coc[y * w + x] = sum / count;
            }
        });

        int SAMPLES = 16;
        float GOLDEN = 2.399963f;

        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int i4 = i * 4;

                float radius = Math.abs(coc[i]);
                if (radius < 1f) {
                    dst.put(i4, src.get(i4)); dst.put(i4+1, src.get(i4+1)); dst.put(i4+2, src.get(i4+2)); dst.put(i4+3, src.get(i4+3));
                    continue;
                }

                float centerW = Math.max(0.02f, 1.0f / radius);
                float r = src.get(i4) * centerW;
                float g = src.get(i4+1) * centerW;
                float b = src.get(i4+2) * centerW;
                float wSum = centerW;

                for (int s = 1; s <= SAMPLES; s++) {
                    float t = (float) s / SAMPLES;
                    float sr = radius * (float) Math.sqrt(t);
                    float st = s * GOLDEN;
                    int sx = x + Math.round((float) (Math.cos(st) * sr));
                    int sy = y + Math.round((float) (Math.sin(st) * sr));

                    if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue;
                    int si = sy * w + sx;
                    int si4 = si * 4;

                    float sCoc = coc[si];
                    float sCocAbs = Math.abs(sCoc);
                    float dx = sx - x;
                    float dy = sy - y;
                    float d2 = dx * dx + dy * dy;

                    float sampleW = 1.0f;
                    if (sCoc < 0f && sCocAbs * sCocAbs < d2) {
                        float leak = (sCocAbs * sCocAbs) / (d2 + 1.0f);
                        sampleW = Math.max(0.15f, leak * 0.85f); // 增大泄漏权重，软糯边缘
                    }

                    r += src.get(si4) * sampleW;
                    g += src.get(si4+1) * sampleW;
                    b += src.get(si4+2) * sampleW;
                    wSum += sampleW;
                }

                float inv = 1f / wSum;
                dst.put(i4, r * inv); dst.put(i4+1, g * inv); dst.put(i4+2, b * inv); dst.put(i4+3, src.get(i4+3));
            }
        });

        src.rewind();
        dst.rewind();
    }
}
