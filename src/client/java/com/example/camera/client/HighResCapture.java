package com.example.camera.client;

import com.example.camera.client.dof.DepthOfField;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.stream.IntStream;

public class HighResCapture {
    public static final int FULL_W = 7680;
    public static final int FULL_H = 4320;

    private static final float MIDDLE_GRAY = 0.15f;
    private static final int SRGB_LUT_SIZE = 4096;
    private static final float[] SRGB_LUT = buildSrgbLut();
    private static final float[] SRGB_BYTE_LUT = buildSrgbByteLut();

    private static FloatBuffer cachedColorBuf = null;
    private static FloatBuffer cachedDepthBuf = null;
    private static FloatBuffer cachedDofBuf = null;
    private static ByteBuffer cachedPixelBuffer = null;
    private static ByteBuffer cachedLdrBuffer = null;

    public static float snapshotExposureEV = 0f;
    public static float snapshotMeterEV = 0f;
    public static int snapshotWb = 5500;
    public static int snapshotSceneTemp = 5500;
    public static int snapshotTone = 0;
    public static int snapshotIso = 100;
    public static float snapshotSensorHeight = 24.0f;
    public static boolean snapshotFullFrame = true;

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
    private static boolean restoreHudHidden;

    private static boolean hdrCaptured;
    private static boolean depthCaptured;
    private static boolean linearScene = true;
    private static int captureAttempts;
    private static boolean restoreRenderHand = true;
    private static Field renderHandField;
    /** True while the CPU post-processing worker owns the cached buffers. */
    private static volatile boolean processing;

    /**
     * Number of frames to let the world render at full resolution before the
     * pixels are read. Temporal antialiasing keeps blending the new 8K frame
     * with the upscaled history buffer, so a capture taken on the first frame
     * after the resize looks soft. A handful of frames is enough for the
     * history to wash out without making the shutter feel slow.
     */
    private static int settleFrames() {
        return Math.max(1, Integer.getInteger("cameramod.settleFrames", 4));
    }

    public static boolean isCapturing() { return state == State.CAPTURING; }
    public static boolean isActive() { return state == State.CAPTURING; }
    public static boolean isWaiting() { return state == State.WAITING; }
    public static boolean isBusy() { return state != State.IDLE || restorePending || processing; }
    public static boolean consumeCompletion() {
        boolean completed = captureCompleted;
        captureCompleted = false;
        return completed;
    }

    public static boolean request(MinecraftClient client) {
        if (state != State.IDLE || restorePending || processing) return false;
        if (!RenderSystem.isOnRenderThread()) return false;

        var window = client.getWindow();
        restoreWidth = window.getFramebufferWidth();
        restoreHeight = window.getFramebufferHeight();
        if (FULL_W > RenderSystem.maxSupportedTextureSize() || FULL_H > RenderSystem.maxSupportedTextureSize()) {
            return false;
        }

        try {
            resolutionChanged = true;
            hdrCaptured = false;
            depthCaptured = false;
            window.setFramebufferWidth(FULL_W);
            window.setFramebufferHeight(FULL_H);
            restoreHudHidden = client.options.hudHidden;
            client.options.hudHidden = true;
            hideHeldItem(client);
            client.onResolutionChanged();

            Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer.textureWidth != FULL_W || framebuffer.textureHeight != FULL_H) {
                restoreResolution(client);
                return false;
            }
            state = State.WAITING;
            waitFrames = settleFrames();
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

    /**
     * Called from the optional Iris mixin right before the pack's final pass.
     * At this point Iris has finished all composite passes, so the configured
     * target holds the scene in linear light before exposure and tonemapping.
     */
    public static void onIrisFinalPass(Object finalPassRenderer) {
        if (state != State.CAPTURING || hdrCaptured) return;
        ensureBuffers();
        IrisHdrSource.Source source = IrisHdrSource.find(finalPassRenderer, FULL_W, FULL_H);
        if (source == null) return;

        cachedColorBuf.clear();
        if (!IrisHdrSource.readColor(source, cachedColorBuf)) return;
        hdrCaptured = true;
        linearScene = true;

        cachedDepthBuf.clear();
        depthCaptured = IrisHdrSource.readDepth(source, cachedDepthBuf);
        System.out.println("[CameraMod] Iris HDR: colortex" + source.target
                + (depthCaptured ? " + depth" : " 深度不可用(" + IrisHdrSource.lastStatus() + ")")
                + " " + source.width + "x" + source.height);
    }

    public static void afterFrame(MinecraftClient client) {
        if (state == State.WAITING) {
            if (--waitFrames <= 0) {
                ensureBuffers();
                captureAttempts = 0;
                state = State.CAPTURING;
            }
            return;
        }
        if (state != State.CAPTURING) return;

        Framebuffer fb = client.getFramebuffer();
        if (fb.textureWidth != FULL_W || fb.textureHeight != FULL_H) {
            state = State.IDLE;
            restorePending = true;
            return;
        }

        // Iris resizes its own targets on the frame after the window changed.
        // Give the HDR source a couple of extra frames before falling back to
        // the LDR main target.
        if (!hdrCaptured && IrisHdrSource.configuredTarget() >= 0
                && IrisHdrSource.isAvailable() && captureAttempts < 2) {
            captureAttempts++;
            return;
        }

        // Reads must stay on the render thread, but the 8K DOF/tonemap pass can
        // take many seconds. Running it here would starve the keep-alive and the
        // integrated server drops the player ("服务器断开连接"), so the CPU work
        // is handed to a worker thread once the buffers are filled.
        try {
            ensureBuffers();
            cachedColorBuf.clear();
            cachedDepthBuf.clear();
            cachedDofBuf.clear();
            cachedPixelBuffer.clear();

            if (!hdrCaptured) {
                if (IrisHdrSource.configuredTarget() >= 0 && IrisHdrSource.isAvailable()) {
                    System.out.println("[CameraMod] Iris HDR 未就绪，回退到主目标");
                }
                readMainTarget(client, fb);
            }
            if (!depthCaptured) {
                cachedDepthBuf.clear();
                depthCaptured = GlReadback.readDepthFloat(fb.fbo, FULL_W, FULL_H, cachedDepthBuf)
                        && GlReadback.hasDepthVariation(cachedDepthBuf);
                System.out.println("[CameraMod] 主目标深度: " + (depthCaptured ? "可读" : "无效或读取失败"));
            }
            if (depthCaptured) {
                System.out.println("[CameraMod] " + describeDepth(cachedDepthBuf));
            }
        } catch (Throwable t) {
            System.err.println("[CameraMod] 像素读取失败: " + t.getMessage());
            t.printStackTrace();
            state = State.IDLE;
            waitFrames = 0;
            restorePending = true;
            captureCompleted = true;
            hdrCaptured = false;
            depthCaptured = false;
            return;
        }

        final MinecraftClient captureClient = client;
        final boolean captureDepth = depthCaptured;
        final boolean captureLinear = linearScene;
        processing = true;
        state = State.IDLE;
        waitFrames = 0;
        restorePending = true;
        captureCompleted = true;
        hdrCaptured = false;
        depthCaptured = false;

        Thread worker = new Thread(() -> cameraMod$processCapture(captureClient, captureDepth, captureLinear),
                "camera-mod-capture");
        worker.setDaemon(true);
        worker.start();
    }

    /** CPU-only 8K post-processing, safe to run off the render thread. */
    private static void cameraMod$processCapture(MinecraftClient client, boolean hasDepth, boolean linear) {
        try {
            long dofStart = System.currentTimeMillis();
            if (hasDepth) {
                applyDepthOfField(cachedColorBuf, cachedDepthBuf, cachedDofBuf, FULL_W, FULL_H,
                        snapshotFocusDistance, snapshotFocalLength, snapshotAperture, cameraNear, cameraFar);
            } else {
                copyColor(cachedColorBuf, cachedDofBuf);
            }
            System.out.println("[CameraMod] 景深耗时: " + (System.currentTimeMillis() - dofStart) + "ms");

            processToDisplay(cachedDofBuf, cachedPixelBuffer, linear);
            cachedPixelBuffer.position(0);

            File dir = new File(client.runDirectory, "screenshots");
            dir.mkdirs();
            String name = "camera_20mp_" + System.currentTimeMillis() + ".tiff";
            File file = new File(dir, name);

            TiffWriter.writeRgb32fBuffer(file, cachedPixelBuffer, FULL_W, FULL_H,
                    snapshotIso, snapshotShutter, snapshotAperture, snapshotFocalLength, snapshotExposureEV,
                    snapshotWb, snapshotMeteringMode, snapshotExposureProgram, snapshotExposureMode,
                    snapshotWbAuto, snapshotLensName, snapshotCamName, snapshotDateStr);

            System.out.println("[CameraMod] 已保存: " + file.getAbsolutePath());
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(cameraMod$fileMessage(file, name), false);
                }
            });
            System.gc();
        } catch (Throwable t) {
            System.err.println("[CameraMod] 保存失败: " + t.getMessage());
            t.printStackTrace();
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[相机] 保存失败: " + t.getMessage()), false);
                }
            });
        } finally {
            processing = false;
        }
    }

    /**
     * Builds the chat line that reports the finished capture. The file name is
     * clickable and opens the TIFF with the system's default viewer, matching
     * how vanilla reports screenshots.
     */
    private static Text cameraMod$fileMessage(File file, String name) {
        MutableText link = Text.literal(name).styled(style -> style
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.literal(file.getAbsolutePath()))));
        return Text.literal("[相机] 20MP TIFF · screenshots/ ").append(link);
    }

    private static String describeDepth(FloatBuffer depth) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int nonFar = 0;
        int samples = 0;
        for (int i = 0; i < depth.capacity(); i += 4096) {
            float v = depth.get(i);
            if (!Float.isFinite(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
            if (v < 0.999999f) nonFar++;
            samples++;
        }
        if (samples == 0) return "深度缓冲为空";
        return String.format("深度范围 %.4f..%.4f, 非远平面采样 %d/%d", min, max, nonFar, samples);
    }

    private static void readMainTarget(MinecraftClient client, Framebuffer fb) {
        linearScene = false;
        if (cachedLdrBuffer == null) {
            cachedLdrBuffer = BufferUtils.createByteBuffer(FULL_W * FULL_H * 4);
        }
        cachedLdrBuffer.clear();
        if (!GlReadback.readRgbaBytes(fb.fbo, FULL_W, FULL_H, cachedLdrBuffer)) return;

        IntStream.range(0, FULL_H).parallel().forEach(y -> {
            for (int x = 0; x < FULL_W; x++) {
                int i = (y * FULL_W + x) * 4;
                float r = SRGB_BYTE_LUT[cachedLdrBuffer.get(i) & 0xFF];
                float g = SRGB_BYTE_LUT[cachedLdrBuffer.get(i + 1) & 0xFF];
                float b = SRGB_BYTE_LUT[cachedLdrBuffer.get(i + 2) & 0xFF];
                cachedColorBuf.put(i, r);
                cachedColorBuf.put(i + 1, g);
                cachedColorBuf.put(i + 2, b);
                cachedColorBuf.put(i + 3, 1f);
            }
        });
    }

    private static void copyColor(FloatBuffer src, FloatBuffer dst) {
        int n = FULL_W * FULL_H * 4;
        IntStream.range(0, FULL_H).parallel().forEach(y -> {
            int start = y * FULL_W * 4;
            int end = start + FULL_W * 4;
            for (int i = start; i < end; i++) {
                dst.put(i, src.get(i));
            }
        });
    }

    /**
     * Applies exposure, white balance and tonemapping. HDR input is scene
     * linear, so the key maps the log-average luminance to middle grey and the
     * ISO/shutter/aperture offset is measured against the metered scene. LDR
     * input is already tonemapped, so it only gets the exposure offset.
     */
    private static void processToDisplay(FloatBuffer src, ByteBuffer dst, boolean linear) {
        float totalStops = clamp(-snapshotMeterEV + snapshotExposureEV, -16f, 8f);
        float exposure = (float) Math.pow(2, totalStops);
        if (linear) {
            double key = logAverageLuma(src);
            double base = MIDDLE_GRAY / Math.max(key, 1e-6);
            exposure *= (float) Math.min(1e6, base);
            System.out.printf("[CameraMod] 曝光: key=%.4f base=%.4f meterEV=%.2f evComp=%.2f total=%.2f stops%n",
                    key, base, snapshotMeterEV, snapshotExposureEV, totalStops);
        } else {
            exposure = (float) Math.pow(2, clamp(totalStops, -5f, 5f));
        }

        float wbT = clamp((snapshotWb - snapshotSceneTemp) / 5500f, -1f, 1f);
        float rGain = 1f + 0.25f * wbT;
        float gGain = 1f;
        float bGain = 1f - 0.25f * wbT;

        float toneT = clamp(snapshotTone / 4f, -1f, 1f);
        rGain *= 1f + 0.08f * toneT;
        gGain *= 1f - 0.08f * toneT;
        bGain *= 1f + 0.08f * toneT;

        double noiseSigma = PostProcess.computeLumaNoise(snapshotIso, snapshotFullFrame);
        double chromaSigma = PostProcess.computeChromaNoise(snapshotIso, snapshotFullFrame);
        boolean addNoise = noiseSigma > 0.0001;
        boolean addChroma = chromaSigma > 0.0001;

        final float exp = exposure;
        final float rg = rGain;
        final float gg = gGain;
        final float bg = bGain;
        final float ns = (float) noiseSigma;
        final float cs = (float) chromaSigma;

        IntStream.range(0, FULL_H).parallel().forEach(y -> {
            int srcY = FULL_H - 1 - y;
            int srcRow = srcY * FULL_W * 4;
            int dstRow = y * FULL_W * 12;
            for (int x = 0; x < FULL_W; x++) {
                int s = srcRow + x * 4;
                float r = src.get(s) * exp * rg;
                float g = src.get(s + 1) * exp * gg;
                float b = src.get(s + 2) * exp * bg;
                if (!Float.isFinite(r)) r = 0;
                if (!Float.isFinite(g)) g = 0;
                if (!Float.isFinite(b)) b = 0;

                if (addNoise) {
                    float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    if (luma < 0) luma = 0;
                    float n = hashNoise(x, y) * 2f * ns * luma;
                    r += n;
                    g += n;
                    b += n;

                    if (addChroma) {
                        // 3×3 粗网格：同一块内的色度噪声相同，模拟去马赛克后的
                        // 低频彩色噪点；三项在亮度上相加为 0，不改变曝光。
                        int bx = (x / 3) * 3 + 17;
                        int by = (y / 3) * 3 + 29;
                        float cr = hashNoise(bx, by);
                        float cb = hashNoise(bx + 71, by + 53);
                        float amp = cs * luma;
                        r += cr * amp;
                        g -= (cr + cb) * 0.5f * amp;
                        b += cb * amp;
                    }
                }

                if (linear) {
                    r = aces(r);
                    g = aces(g);
                    b = aces(b);
                }
                int d = dstRow + x * 12;
                dst.putFloat(d, srgbEncode(r));
                dst.putFloat(d + 4, srgbEncode(g));
                dst.putFloat(d + 8, srgbEncode(b));
            }
        });
    }

    private static double logAverageLuma(FloatBuffer src) {
        final int step = 4;
        double sum = 0;
        long count = 0;
        for (int y = 0; y < FULL_H; y += step) {
            int row = y * FULL_W * 4;
            for (int x = 0; x < FULL_W; x += step) {
                int i = row + x * 4;
                double luma = 0.2126 * src.get(i) + 0.7152 * src.get(i + 1) + 0.0722 * src.get(i + 2);
                if (!Double.isFinite(luma) || luma < 1e-6) continue;
                sum += Math.log(luma);
                count++;
            }
        }
        if (count == 0) return MIDDLE_GRAY;
        return Math.exp(sum / count);
    }

    private static float hashNoise(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f - 0.5f;
    }

    private static float hash01(int x, int y) {
        int h = x * 73856093 ^ y * 19349663;
        h ^= (h >>> 13);
        h *= 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    private static float aces(float x) {
        if (x <= 0f) return 0f;
        final float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
        float v = (x * (a * x + b)) / (x * (c * x + d) + e);
        return clamp(v, 0f, 1f);
    }

    private static float srgbEncode(float v) {
        if (!(v > 0f)) return 0f;
        if (v >= 1f) return 1f;
        int index = (int) (v * SRGB_LUT_SIZE + 0.5f);
        return SRGB_LUT[index];
    }

    private static float[] buildSrgbLut() {
        float[] lut = new float[SRGB_LUT_SIZE + 1];
        for (int i = 0; i <= SRGB_LUT_SIZE; i++) {
            float v = i / (float) SRGB_LUT_SIZE;
            lut[i] = v <= 0.0031308f ? 12.92f * v : (float) (1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
        }
        return lut;
    }

    private static float[] buildSrgbByteLut() {
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            lut[i] = v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
        }
        return lut;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static void ensureBuffers() {
        if (cachedColorBuf == null) {
            cachedColorBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H * 4);
            cachedDepthBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H);
            cachedDofBuf = BufferUtils.createFloatBuffer(FULL_W * FULL_H * 4);
            cachedPixelBuffer = BufferUtils.createByteBuffer(FULL_W * FULL_H * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
            cachedLdrBuffer = BufferUtils.createByteBuffer(FULL_W * FULL_H * 4);
        }
    }

    private static void hideHeldItem(MinecraftClient client) {
        try {
            if (renderHandField == null) {
                renderHandField = client.gameRenderer.getClass().getDeclaredField("renderHand");
                renderHandField.setAccessible(true);
            }
            restoreRenderHand = renderHandField.getBoolean(client.gameRenderer);
        } catch (Throwable ignored) {
            restoreRenderHand = true;
        }
        client.gameRenderer.setRenderHand(false);
    }

    private static void restoreHeldItem(MinecraftClient client) {
        try {
            client.gameRenderer.setRenderHand(restoreRenderHand);
        } catch (Throwable t) {
            System.err.println("[CameraMod] 恢复手持物品渲染失败: " + t);
        }
    }

    private static void restoreResolution(MinecraftClient client) {
        if (!resolutionChanged) return;
        try {
            client.getWindow().setFramebufferWidth(restoreWidth);
            client.getWindow().setFramebufferHeight(restoreHeight);
            client.options.hudHidden = restoreHudHidden;
            restoreHeldItem(client);
            client.onResolutionChanged();
        } catch (Throwable t) {
            System.err.println("[CameraMod] 恢复窗口渲染尺寸失败: " + t);
        } finally {
            resolutionChanged = false;
            restorePending = false;
            state = State.IDLE;
            waitFrames = 0;
            hdrCaptured = false;
            depthCaptured = false;
        }
    }

    private static void applyDepthOfField(FloatBuffer src, FloatBuffer depth, FloatBuffer dst, int w, int h, float focusDist, float focalMm, float aperture, float near, float far) {
        int n = w * h;
        float[] coc = new float[n];
        float[] cocTemp = new float[n];

        IntStream.range(0, n).parallel().forEach(i -> {
            float z = depth.get(i);
            float zNdc = z * 2f - 1f;
            float linear = (z < 1.0f) ? 2f * near * far / (far + near - zNdc * (far - near)) : far;
            float c = DepthOfField.computeCocPixels(linear, focusDist, focalMm, aperture, h, snapshotSensorHeight);
            coc[i] = DepthOfField.softKnee(DepthOfField.clampCoc(c, h));
        });

        // 7x7 羽化窗口，抹平硬边
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sum = 0; int count = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = x + dx;
                    if (sx >= 0 && sx < w) { sum += coc[y * w + sx]; count++; }
                }
                cocTemp[y * w + x] = sum / count;
            }
        });
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sum = 0; int count = 0;
                for (int dy = -3; dy <= 3; dy++) {
                    int sy = y + dy;
                    if (sy >= 0 && sy < h) { sum += cocTemp[sy * w + x]; count++; }
                }
                coc[y * w + x] = sum / count;
            }
        });

        float GOLDEN = 2.399963f;

        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int i4 = i * 4;

                float radius = Math.abs(coc[i]);
                if (radius < 1f) {
                    dst.put(i4, src.get(i4)); dst.put(i4+1, src.get(i4+1)); dst.put(i4+2, src.get(i4+2)); dst.put(i4+3, src.get(i4+3));
                    continue;
                }

                // 均匀圆盘采样 + 每像素随机旋转。固定角度的螺旋采样会在高反差
                // 边缘留下同心圆环；逐像素旋转把环打散成不相关的颗粒。
                float rot = hash01(x, y) * 6.2831853f;
                float r = src.get(i4);
                float g = src.get(i4+1);
                float b = src.get(i4+2);
                float wSum = 1f;

                int samples = radius < 4f ? 8 : (radius < 16f ? 12 : 16);
                for (int s = 0; s < samples; s++) {
                    float t = (s + 0.5f) / samples;
                    float sr = radius * (float) Math.sqrt(t);
                    float st = s * GOLDEN + rot;
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
                        sampleW = Math.max(0.15f, leak * 0.85f);
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
