package com.example.camera.client;

import com.example.camera.client.dof.DepthOfField;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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
    private static Framebuffer tileFb = null;
    private static boolean inWorldRender = false;

    public static void setInWorldRender(boolean v) { inWorldRender = v; }
    public static boolean isInWorldRender() { return inWorldRender; }
    public static boolean isCapturing() { return state == State.CAPTURING; }
    public static boolean isActive() { return state == State.CAPTURING; }
    public static boolean isWaiting() { return state == State.WAITING; }

    public static void request() {
        if (state != State.IDLE) return;
        state = State.WAITING;
        waitFrames = 3;
        if (tileFb == null) {
            try { tileFb = new HdrFramebuffer(FULL_W, FULL_H); } catch (Throwable t) { reset(); }
        }
    }

    public static Framebuffer getTileFramebuffer() { return tileFb; }

    public static void afterFrame(MinecraftClient client) {
        if (state == State.WAITING) {
            if (--waitFrames <= 0) state = State.CAPTURING;
            return;
        }
        if (state != State.CAPTURING) return;

        Framebuffer fb = tileFb;
        if (fb == null) { reset(); return; }

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

            // 获取真 HDR 通道 (CMP 是 colortex5)
            int irisTexId = IrisCompat.getHdrTextureId();
            if (irisTexId != -1) {
                int tempFbo = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, tempFbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, irisTexId, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
                    int winW = client.getWindow().getFramebufferWidth();
                    int winH = client.getWindow().getFramebufferHeight();
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, tempFbo);
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
                    GL30.glBlitFramebuffer(0, 0, winW, winH, 0, 0, FULL_W, FULL_H, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                }
                GL30.glDeleteFramebuffers(tempFbo);
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

            final float MAX_HDR_VALUE = 32.0f;

            for (int y = 0; y < FULL_H; y++) {
                for (int x = 0; x < FULL_W; x++) {
                    int srcY = FULL_H - 1 - y;
                    int src = (srcY * FULL_W + x) * 4;

                    float rf = cachedDofBuf.get(src) * exposureFactor;
                    float gf = cachedDofBuf.get(src + 1) * exposureFactor;
                    float bf = cachedDofBuf.get(src + 2) * exposureFactor;

                    if (irisTexId != -1) {
                        // 截断到 32.0 但不做归一化
                        rf = Math.min(rf, MAX_HDR_VALUE);
                        gf = Math.min(gf, MAX_HDR_VALUE);
                        bf = Math.min(bf, MAX_HDR_VALUE);

                        // 暗部/中间调正常 Gamma，高光对数滚降，保留溢出值
                        if (rf > 1.0f) rf = 1.0f + (float) Math.log10(rf); else rf = (float) Math.pow(rf, 1.0 / 2.2);
                        if (gf > 1.0f) gf = 1.0f + (float) Math.log10(gf); else gf = (float) Math.pow(gf, 1.0 / 2.2);
                        if (bf > 1.0f) bf = 1.0f + (float) Math.log10(bf); else bf = (float) Math.pow(bf, 1.0 / 2.2);
                    } else {
                        if (wbAlpha > 0) { rf = rf*(1-wbAlpha) + wbColorR*wbAlpha; gf = gf*(1-wbAlpha) + wbColorG*wbAlpha; bf = bf*(1-wbAlpha) + wbColorB*wbAlpha; }
                        if (toneAlpha > 0) { rf = rf*(1-toneAlpha) + toneColorR*toneAlpha; gf = gf*(1-toneAlpha) + toneColorG*toneAlpha; bf = bf*(1-toneAlpha) + toneColorB*toneAlpha; }
                    }

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
        }
        reset();
    }

    private static void reset() { state = State.IDLE; waitFrames = 0; }

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