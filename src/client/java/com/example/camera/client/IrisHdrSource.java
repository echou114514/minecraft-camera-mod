package com.example.camera.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Set;

/**
 * Reads the shader pack's pre-tonemap colour target at the start of Iris' final
 * pass. Iris has no public API for this, so every field is resolved lazily and
 * any failure makes the caller fall back to the vanilla main target.
 *
 * Eclipse keeps the scene in colortex5 (the TAA buffer) and applies exposure
 * plus tonemapping in composite10, which writes colortex7. Other packs put the
 * same data somewhere else, so the target can be overridden with
 * -Dcameramod.irisHdrTarget=N. A value of -1 disables the Iris path entirely.
 */
public final class IrisHdrSource {
    private static final int DEFAULT_TARGET = 5;
    private static final int MAX_SCAN_TARGET = 7;

    private static boolean resolved;
    private static Field renderTargetsField;
    private static Field finalPassField;
    private static Field stageReadsFromAltField;
    private static Method getTarget;
    private static Method getTargetCount;
    private static Method getMainTexture;
    private static Method getAltTexture;
    private static Method getWidth;
    private static Method getHeight;
    private static Method getInternalFormat;
    private static Method getDepthTexture;

    private static int colorFbo = -1;
    private static int depthFbo = -1;
    private static String lastStatus = "";

    public static final class Source {
        public final int target;
        public final int texture;
        public final int depthTexture;
        public final int width;
        public final int height;

        Source(int target, int texture, int depthTexture, int width, int height) {
            this.target = target;
            this.texture = texture;
            this.depthTexture = depthTexture;
            this.width = width;
            this.height = height;
        }
    }

    private IrisHdrSource() {
    }

    public static int configuredTarget() {
        return Integer.getInteger("cameramod.irisHdrTarget", DEFAULT_TARGET);
    }

    /** True when Iris is present and its internal classes could be resolved. */
    public static boolean isAvailable() {
        return resolve();
    }

    public static Source find(Object finalPassRenderer, int expectedWidth, int expectedHeight) {
        int preferred = configuredTarget();
        if (preferred < 0) return null;
        if (!resolve()) return null;

        try {
            Object targets = renderTargetsField.get(finalPassRenderer);
            if (targets == null) return null;

            Set<Integer> altReads = altReadTargets(finalPassRenderer);
            Source source = tryTarget(targets, altReads, preferred, expectedWidth, expectedHeight);
            if (source != null) return source;

            // The configured target does not exist or is not a floating point
            // buffer in this pack. Look for the most image-like float buffer we
            // can find, which is a reasonable guess for packs we do not know.
            int count = Math.min((int) getTargetCount.invoke(targets), MAX_SCAN_TARGET + 1);
            Source best = null;
            double bestScore = 0;
            for (int i = 0; i < count; i++) {
                if (i == preferred) continue;
                Source candidate = tryTarget(targets, altReads, i, expectedWidth, expectedHeight);
                if (candidate == null) continue;
                double score = sampleScore(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> altReadTargets(Object finalPassRenderer) {
        try {
            Object pass = finalPassField.get(finalPassRenderer);
            if (pass == null) return Collections.emptySet();
            Object value = stageReadsFromAltField.get(pass);
            if (value instanceof Set<?> set) {
                return (Set<Integer>) set;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptySet();
    }

    private static Source tryTarget(Object targets, Set<Integer> altReads, int index, int w, int h) {
        try {
            Object target = getTarget.invoke(targets, index);
            if (target == null) return null;

            String format = String.valueOf(getInternalFormat.invoke(target));
            if (!isFloatFormat(format)) return null;

            int width = (int) getWidth.invoke(target);
            int height = (int) getHeight.invoke(target);
            if (width != w || height != h) return null;

            int main = (int) getMainTexture.invoke(target);
            int alt = (int) getAltTexture.invoke(target);
            int texture = altReads.contains(index) ? alt : main;
            if (texture == 0) return null;

            int depth = 0;
            try {
                depth = (int) getDepthTexture.invoke(targets);
            } catch (Throwable ignored) {
            }
            return new Source(index, texture, depth, width, height);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFloatFormat(String name) {
        return name.contains("16F") || name.contains("32F") || name.contains("11F")
                || name.equals("RGBA16") || name.equals("RGB16");
    }

    private static double sampleScore(Source source) {
        int size = Math.min(32, Math.min(source.width, source.height));
        int x = Math.max(0, (source.width - size) / 2);
        int y = Math.max(0, (source.height - size) / 2);
        FloatBuffer sample = org.lwjgl.BufferUtils.createFloatBuffer(size * size * 4);
        if (!readColorRegion(source.texture, x, y, size, size, sample)) return 0;

        double sum = 0;
        double sumSq = 0;
        int count = size * size;
        for (int i = 0; i < count; i++) {
            int p = i * 4;
            double luma = 0.2126 * sample.get(p) + 0.7152 * sample.get(p + 1) + 0.0722 * sample.get(p + 2);
            if (Double.isNaN(luma) || Double.isInfinite(luma)) luma = 0;
            luma = Math.max(0, Math.min(64, luma));
            sum += luma;
            sumSq += luma * luma;
        }
        double mean = sum / count;
        return Math.max(0, sumSq / count - mean * mean);
    }

    public static boolean readColor(Source source, FloatBuffer dst) {
        return readColorRegion(source.texture, 0, 0, source.width, source.height, dst);
    }

    /** Human readable result of the last {@link #readDepth} call, for logging. */
    public static String lastStatus() {
        return lastStatus;
    }

    public static boolean readDepth(Source source, FloatBuffer dst) {
        if (source.depthTexture == 0) {
            lastStatus = "无深度纹理";
            return false;
        }
        ensureFramebuffers();

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depthFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, source.depthTexture, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                lastStatus = "深度 FBO 不完整: 0x" + Integer.toHexString(status)
                        + " tex=" + source.depthTexture;
                return false;
            }
            int depthWidth = GL45.glGetTextureLevelParameteri(source.depthTexture, 0, GL11.GL_TEXTURE_WIDTH);
            int depthHeight = GL45.glGetTextureLevelParameteri(source.depthTexture, 0, GL11.GL_TEXTURE_HEIGHT);
            if (depthWidth != source.width || depthHeight != source.height) {
                lastStatus = "深度尺寸 " + depthWidth + "x" + depthHeight + " 屏幕 " + source.width + "x" + source.height;
                return false;
            }
            boolean ok = GlReadback.readDepthFloat(depthFbo, source.width, source.height, dst);
            if (ok && !GlReadback.hasDepthVariation(dst)) {
                lastStatus = "深度无变化（全为常数）";
                return false;
            }
            lastStatus = ok ? "ok" : "glReadPixels 异常";
            return ok;
        } catch (Throwable t) {
            lastStatus = "深度异常: " + t;
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private static boolean readColorRegion(int texture, int x, int y, int w, int h, FloatBuffer dst) {
        if (texture == 0 || w <= 0 || h <= 0) return false;
        ensureFramebuffers();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, colorFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            return GlReadback.readRgbaFloat(colorFbo, x, y, w, h, dst);
        } catch (Throwable ignored) {
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private static void ensureFramebuffers() {
        if (colorFbo == -1) colorFbo = GL30.glGenFramebuffers();
        if (depthFbo == -1) depthFbo = GL30.glGenFramebuffers();
    }

    private static boolean resolve() {
        if (resolved) return renderTargetsField != null;
        resolved = true;
        try {
            Class<?> finalPass = Class.forName("net.irisshaders.iris.pipeline.FinalPassRenderer");
            Class<?> passClass = Class.forName("net.irisshaders.iris.pipeline.FinalPassRenderer$Pass");
            Class<?> targetsClass = Class.forName("net.irisshaders.iris.targets.RenderTargets");
            Class<?> targetClass = Class.forName("net.irisshaders.iris.targets.RenderTarget");

            renderTargetsField = finalPass.getDeclaredField("renderTargets");
            renderTargetsField.setAccessible(true);
            finalPassField = finalPass.getDeclaredField("finalPass");
            finalPassField.setAccessible(true);
            stageReadsFromAltField = passClass.getDeclaredField("stageReadsFromAlt");
            stageReadsFromAltField.setAccessible(true);

            getTarget = targetsClass.getMethod("get", int.class);
            getTargetCount = targetsClass.getMethod("getRenderTargetCount");
            getDepthTexture = targetsClass.getMethod("getDepthTexture");

            getMainTexture = targetClass.getMethod("getMainTexture");
            getAltTexture = targetClass.getMethod("getAltTexture");
            getWidth = targetClass.getMethod("getWidth");
            getHeight = targetClass.getMethod("getHeight");
            getInternalFormat = targetClass.getMethod("getInternalFormat");
        } catch (Throwable t) {
            renderTargetsField = null;
        }
        return renderTargetsField != null;
    }
}
