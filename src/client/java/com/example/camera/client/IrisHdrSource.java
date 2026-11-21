package com.example.camera.client;

import com.example.camera.CameraMod;
import com.example.camera.client.config.CameraModConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the shader pack's pre-tonemap colour target at the start of Iris' final
 * pass. Iris has no public API for this, so every field is resolved lazily and
 * any failure makes the caller fall back to the vanilla main target.
 *
 * <p>目标选择的根治方案：不同光影包把「tonemap 前的场景」放在不同 colortex ——
 * Eclipse 在 colortex5（TAA buffer），Complementary 在 colortex3（其 final.glsl
 * 只采样 colortex3）。两包（以及多数 OptiFine 系包）的共有结构是：<b>final 阶段
 * 程序里 texture(colortexN) 的采样目标就是场景缓冲</b>。因此默认模式 AUTO 会解析
 * 当前光影包的 final 程序源码并自动选它；配置里的 {@code irisHdrTarget} 可手动
 * 指定（0-15），{@code -1} 关闭 Iris 通路。</p>
 *
 * <p>穷举检测（一键摸底）：{@code -Dcameramod.targetDump=true} 时，每次 HDR 捕获
 * 会把 colortex0-15 各采一块 480×270 中心裁剪，拼成 4×4 联览图并输出逐目标统计，
 * 人工一眼即可确认该用哪个 N。</p>
 */
public final class IrisHdrSource {
    /** 配置语义：-100 = AUTO（从光影包 final 程序自动检测），-1 = 关闭，0-15 = 手动。 */
    public static final int TARGET_AUTO = -100;
    public static final int TARGET_DISABLED = -1;
    private static final int MAX_TARGET = 16;
    /** 兜底：AUTO 检测失败时的历史默认（Eclipse）。 */
    private static final int LEGACY_DEFAULT_TARGET = 5;

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

    /** AUTO 检测结果缓存（-2 = 未检测）。 */
    private static int detectedTarget = -2;
    private static String detectedSource = "";

    private static boolean targetSheetDumped;

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
        // JVM property wins when explicitly set; otherwise the config file.
        String prop = System.getProperty("cameramod.irisHdrTarget");
        if (prop != null) {
            try {
                return Integer.parseInt(prop);
            } catch (NumberFormatException ignored) {
            }
        }
        return CameraModConfig.get().irisHdrTarget;
    }

    /** True when Iris is present and its internal classes could be resolved. */
    public static boolean isAvailable() {
        return resolve();
    }

    public static Source find(Object finalPassRenderer, int expectedWidth, int expectedHeight) {
        int preferred = configuredTarget();
        if (preferred == TARGET_DISABLED) return null;
        if (preferred == TARGET_AUTO) {
            preferred = detectSceneTarget();
        }
        if (!resolve()) return null;

        try {
            Object targets = renderTargetsField.get(finalPassRenderer);
            if (targets == null) return null;

            Set<Integer> altReads = altReadTargets(finalPassRenderer);
            if (preferred >= 0) {
                // 首选目标接受任意格式：包作者把场景放在哪就是哪（Complementary
                // 的 pre-tonemap 场景甚至在 RGBA8 里），float 只约束穷举猜测。
                String reject = tryTarget(targets, altReads, preferred, expectedWidth, expectedHeight, false);
                if (reject == null) {
                    Source source = tryTargetSource(targets, altReads, preferred, expectedWidth, expectedHeight);
                    if (source != null) return source;
                }
                CameraMod.LOGGER.warn("[CameraMod] 目标 colortex{} 不可用（{}），回退穷举扫描", preferred, reject);
            }

            // 手动目标不可用或 AUTO 检测失败：在所有浮点目标里找最"像画面"的。
            int count = Math.min((int) getTargetCount.invoke(targets), MAX_TARGET);
            Source best = null;
            double bestScore = 0;
            for (int i = 0; i < count; i++) {
                if (i == preferred) continue;
                if (tryTarget(targets, altReads, i, expectedWidth, expectedHeight, true) != null) continue;
                Source candidate = tryTargetSource(targets, altReads, i, expectedWidth, expectedHeight);
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

    // ------------------------------------------------------------------
    // AUTO：解析当前光影包 final 程序的 colortex 采样
    // ------------------------------------------------------------------

    private static int detectSceneTarget() {
        if (detectedTarget != -2) return detectedTarget;
        detectedTarget = -1;
        try {
            Path pack = resolveShaderPackPath();
            if (pack == null) {
                CameraMod.LOGGER.warn("[CameraMod] AUTO: 未找到已启用的光影包，回退穷举扫描");
                return detectedTarget;
            }
            String text = readFinalPrograms(pack);
            if (text.isEmpty()) {
                CameraMod.LOGGER.warn("[CameraMod] AUTO: 光影包 {} 未找到 final 程序，回退穷举扫描", pack.getFileName());
                return detectedTarget;
            }
            int[] freq = new int[MAX_TARGET];
            Matcher m = Pattern.compile("(?:texture2D|texture)\\s*\\(\\s*colortex(\\d+)").matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n < MAX_TARGET) freq[n]++;
            }
            int best = -1;
            for (int i = 0; i < MAX_TARGET; i++) {
                if (freq[i] > (best >= 0 ? freq[best] : 0)) best = i;
            }
            if (best >= 0) {
                detectedTarget = best;
                detectedSource = pack.getFileName().toString();
                CameraMod.LOGGER.info("[CameraMod] AUTO: 光影包 {} 的 final 程序采样 colortex{}（出现 {} 次）-> 作为 HDR 源",
                        detectedSource, best, freq[best]);
            }
        } catch (Throwable t) {
            CameraMod.LOGGER.warn("[CameraMod] AUTO 检测失败，回退穷举扫描: {}", t.toString());
        }
        return detectedTarget;
    }

    /** 从 config/iris.properties 找到当前启用的光影包（目录或 zip）。 */
    private static Path resolveShaderPackPath() throws Exception {
        MinecraftClientRunDir r = runDir();
        Properties props = new Properties();
        Path propsFile = r.dir.resolve("config/iris.properties");
        if (!Files.exists(propsFile)) return null;
        try (var in = Files.newInputStream(propsFile)) {
            props.load(in);
        }
        String name = props.getProperty("shaderPack");
        if (name == null || name.isBlank() || name.equals("OFF") || name.equals("(internal)")) return null;

        Path p = Path.of(name);
        if (p.isAbsolute() && Files.exists(p)) return p;
        Path underShaders = r.dir.resolve("shaderpacks").resolve(name);
        if (Files.exists(underShaders)) return underShaders;
        return null;
    }

    /** 收集光影包里 final 阶段的全部源码（目录/zip 皆可；跳过 placebo 变体目录）。 */
    private static String readFinalPrograms(Path packRoot) throws Exception {
        StringBuilder sb = new StringBuilder();
        Pattern finalFile = Pattern.compile("^shaders/(world[-0-9]+/)?(program/)?final[^/]*\\.(fsh|glsl|vsh)$");
        if (Files.isDirectory(packRoot)) {
            try (var walk = Files.walk(packRoot)) {
                walk.filter(f -> {
                    String rel = packRoot.relativize(f).toString().replace('\\', '/');
                    return finalFile.matcher(rel).matches();
                }).forEach(f -> {
                    try {
                        sb.append(Files.readString(f)).append('\n');
                    } catch (Exception ignored) {
                    }
                });
            }
        } else {
            try (ZipFile zip = new ZipFile(packRoot.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (finalFile.matcher(e.getName()).matches()) {
                        sb.append(new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                          .append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private record MinecraftClientRunDir(Path dir) {}

    private static MinecraftClientRunDir runDir() {
        return new MinecraftClientRunDir(
                net.minecraft.client.MinecraftClient.getInstance().runDirectory.toPath().toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // 穷举检测：把 colortex0-15 中心裁剪拼成 4×4 联览图
    // ------------------------------------------------------------------

    /** 由 AutoTest 或 -Dcameramod.targetDumpDir 指定输出目录；null 时禁用。 */
    public static void setTargetDumpDir(Path dir) {
        if (dir != null) {
            System.setProperty("cameramod.targetDump", "true");
            System.setProperty("cameramod.targetDumpDir", dir.toAbsolutePath().toString());
        } else {
            System.clearProperty("cameramod.targetDump");
            System.clearProperty("cameramod.targetDumpDir");
        }
        targetSheetDumped = false;
    }

    public static void maybeDumpTargets(Object finalPassRenderer) {
        if (targetSheetDumped) return;
        if (!"true".equals(System.getProperty("cameramod.targetDump"))) return;
        targetSheetDumped = true;
        try {
            Object targets = renderTargetsField.get(finalPassRenderer);
            if (targets == null) return;

            int tileW = 480, tileH = 270;
            var sheet = new net.minecraft.client.texture.NativeImage(tileW * 4, tileH * 4, false);
            StringBuilder stats = new StringBuilder();
            int count = Math.min((int) getTargetCount.invoke(targets), MAX_TARGET);

            for (int i = 0; i < MAX_TARGET; i++) {
                int tileCol = i % 4, tileRow = i / 4;
                double mean = 0, varSum = 0, sumSq = 0;
                long pxCount = 0;
                boolean valid = i < count;

                if (valid) {
                    try {
                        Object target = getTarget.invoke(targets, i);
                        int tw = (int) getWidth.invoke(target);
                        int th = (int) getHeight.invoke(target);
                        int tex = (int) getMainTexture.invoke(target);

                        // 中心 960×540 区域，RGBA8 读回后隔行抽取到 480×270。
                        int srcW = 960, srcH = 540;
                        ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(srcW * srcH * 4);
                        if (readRgbaRegion(tex, (tw - srcW) / 2, (th - srcH) / 2, srcW, srcH, buf)) {
                            int x0 = tileCol * tileW, y0 = tileRow * tileH;
                            for (int ty = 0; ty < tileH; ty++) {
                                int srcRow = (srcH - 1 - ty * 2) * srcW * 4;   // glReadPixels 自下而上
                                for (int tx = 0; tx < tileW; tx++) {
                                    int k = srcRow + tx * 2 * 4;
                                    int r = buf.get(k) & 0xFF, g = buf.get(k + 1) & 0xFF, b = buf.get(k + 2) & 0xFF;
                                    sheet.setColor(x0 + tx, y0 + ty,
                                            (0xFF << 24) | (b << 16) | (g << 8) | r);
                                    double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                                    mean += lum; sumSq += lum * lum; pxCount++;
                                }
                            }
                        } else {
                            valid = false;
                        }
                    } catch (Throwable ignored) {
                        valid = false;
                    }
                }
                if (!valid) {
                    // 空格：暗灰底
                    int x0 = tileCol * tileW, y0 = tileRow * tileH;
                    for (int ty = 0; ty < tileH; ty += 1) {
                        for (int tx = 0; tx < tileW; tx += 4) {
                            sheet.setColor(x0 + tx, y0 + ty, 0xFF222222);
                        }
                    }
                }
                if (pxCount > 0) {
                    mean /= pxCount;
                    varSum = sumSq / pxCount - mean * mean;
                }
                stats.append(String.format("colortex%-2d %s mean=%.3f var=%.4f%n",
                        i, valid ? "ok" : "--", mean, Math.max(0, varSum)));
            }

            Path out = dumpOutDir();
            Files.createDirectories(out);
            File file = out.resolve("targets_sheet.png").toFile();
            sheet.writeTo(file);
            CameraMod.LOGGER.info("[CameraMod] 穷举检测联览图: {}（左上=colortex0，行优先）\n{}",
                    file.getAbsolutePath(), stats);
        } catch (Throwable t) {
            CameraMod.LOGGER.warn("[CameraMod] 穷举检测失败: {}", t.toString());
        }
    }

    private static Path dumpOutDir() {
        String prop = System.getProperty("cameramod.targetDumpDir");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        return runDir().dir.resolve("target-scan");
    }

    /** 读纹理指定区域的 RGBA8（用于联览图，允许 >1 的值被钳制——仅供目视）。 */
    private static boolean readRgbaRegion(int texture, int x, int y, int w, int h, ByteBuffer dst) {
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
            GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, dst);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
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

    /** 返回 null 表示可用（{@code requireFloat=false} 时 8-bit 目标也接受），否则给出拒绝原因。 */
    private static String tryTarget(Object targets, Set<Integer> altReads, int index, int w, int h,
                                    boolean requireFloat) {
        try {
            Object target = getTarget.invoke(targets, index);
            if (target == null) return "目标不存在";

            String format = String.valueOf(getInternalFormat.invoke(target));
            if (requireFloat && !isFloatFormat(format)) return "非浮点格式 " + format;

            int width = (int) getWidth.invoke(target);
            int height = (int) getHeight.invoke(target);
            if (width != w || height != h) return "尺寸 " + width + "x" + height + " != " + w + "x" + h;

            int main = (int) getMainTexture.invoke(target);
            int alt = (int) getAltTexture.invoke(target);
            int texture = altReads.contains(index) ? alt : main;
            if (texture == 0) return "纹理 id=0";
            return null;   // 可用
        } catch (Throwable t) {
            return "异常 " + t;
        }
    }

    private static Source tryTargetSource(Object targets, Set<Integer> altReads, int index, int w, int h) {
        try {
            Object target = getTarget.invoke(targets, index);
            int width = (int) getWidth.invoke(target);
            int height = (int) getHeight.invoke(target);
            int main = (int) getMainTexture.invoke(target);
            int alt = (int) getAltTexture.invoke(target);
            int texture = altReads.contains(index) ? alt : main;
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
