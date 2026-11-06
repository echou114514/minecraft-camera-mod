package com.example.camera.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * 相机取景器 HUD。
 *
 * <p>视觉参考 {@code refrence/new_hud.html}：顶部状态栏、左侧模式列表（弹簧跟随 + 非线性衰减）、
 * 底部参数轨（未选中圆点 / 选中展开成细轨 + 发光指示块）、底部提示，以及各模式附加效果
 * （人像径向暗角、夜景冷色压暗、电影上下黑边）。</p>
 *
 * <p>与游戏的交互只有三个静态入口：{@link #render}、{@link #tick}、{@link #reset}。
 * 所有动画状态都是静态字段，{@code render} 负责按快照更新弹簧目标并绘制，{@code tick} 负责用真实
 * 帧间隔推进弹簧。本类不读取相机 NBT，也不修改任何渲染/分辨率设置。</p>
 */
public final class CameraHud {

    private CameraHud() {
    }

    // ==================== 设计常量 ====================
    // 全部以「1080p 高度」为设计基准。渲染时把坐标系整体归一化到设计空间，
    // 因此下面的数字可以直接照抄 HTML 原型，无需再乘任何缩放系数。

    /** 设计基准高度。 */
    private static final float DESIGN_HEIGHT = 1080f;
    /**
     * 设计单位 → GUI 像素。原型是 1080p + GUI scale 3（此时 GUI 画布约 360
     * 单位高），所以 1 设计单位 ≈ 1/3 GUI 像素；取 0.45 让整块 HUD 比原版字
     * 稍大一点，并随 GUI scale 设置同步缩放。
     */
    private static final float DESIGN_TO_GUI = 0.45f;
    /** Minecraft 字体一个字形大致的高度（设计单位），用于垂直居中换算。 */
    private static final float TEXT_GLYPH = 9f;

    /** 顶部栏距上边缘。 */
    private static final float TOP_MARGIN = 26f;
    /** 顶部栏距左右边缘。 */
    private static final float TOP_SIDE = 30f;

    /** 左侧模式列表左基线。 */
    private static final float MODE_LEFT = 54f;
    /** 模式行距。 */
    private static final float MODE_SPACING = 46f;
    /** 模式文字相对 MC 基础字体的放大倍数（约 9px -> 19px）。 */
    private static final float MODE_TEXT_SCALE = 2.1f;

    /** 底部参数之间的间距。 */
    private static final float PARAM_GAP = 18f;
    /** 选中参数展开后的轨道宽 / 高。 */
    private static final float RAIL_ACTIVE_W = 150f;
    private static final float RAIL_ACTIVE_H = 3f;
    /** 未选中参数圆点直径。 */
    private static final float RAIL_IDLE = 7f;
    /** 参数轨中心距屏幕底部。 */
    private static final float RAIL_BOTTOM = 82f;
    /** 提示文字距屏幕底部。 */
    private static final float HINT_BOTTOM = 22f;

    /** 可见参数上限。 */
    private static final int N_PARAMS = HudSnapshot.PARAM_NAMES.length;

    /** 模式名辉光的四向偏移（设计单位）。 */
    private static final float[][] GLOW_OFFSETS = {
            { -1.4f, 0f }, { 1.4f, 0f }, { 0f, -1.4f }, { 0f, 1.4f }
    };

    // ==================== 纹理 ====================
    private static final Identifier VIGNETTE_ID = Identifier.of("camera-mod", "hud_vignette");
    /**
     * 512 px 而不是 128 px：径向渐变被拉伸到整屏时，8 位 alpha 的量化台阶会变成
     * 一圈圈可见的同心环。提高分辨率 + 生成时加抖动把台阶打散（见
     * {@link #ensureVignette}）。
     */
    private static final int VIGNETTE_SIZE = 512;
    /** 4×4 Bayer 抖动矩阵，用于打散 alpha 渐变的色阶。 */
    private static final int[][] BAYER4 = {
            { 0, 8, 2, 10 },
            { 12, 4, 14, 6 },
            { 3, 11, 1, 9 },
            { 15, 7, 13, 5 }
    };

    // ==================== 动画状态 ====================
    // 左侧模式列表：value 直接是「模式索引」，弹簧参数取自 HTML 的 Spring(0,190,22.5,1)。
    private static final Spring mode = new Spring(0f, 190f, 22.5f);

    // 每个参数一组弹簧：轨道宽度 / 高度 / 指示块透明度 / 数值透明度。
    private static final Spring[] railW = new Spring[N_PARAMS];
    private static final Spring[] railH = new Spring[N_PARAMS];
    private static final Spring[] indAlpha = new Spring[N_PARAMS];
    private static final Spring[] valAlpha = new Spring[N_PARAMS];

    /** 累计动画时间（秒），用于红色呼吸圆点。 */
    private static float time = 0f;

    static {
        for (int i = 0; i < N_PARAMS; i++) {
            // 轨道展开用稍软的弹簧；透明度用更快、更硬的弹簧，接近 CSS 的 eased fade。
            railW[i] = new Spring(RAIL_IDLE, 170f, 22f);
            railH[i] = new Spring(RAIL_IDLE, 170f, 22f);
            indAlpha[i] = new Spring(0f, 260f, 26f);
            valAlpha[i] = new Spring(0f, 260f, 26f);
        }
    }

    // ==================== 对外 API ====================

    /**
     * 每帧在 {@code HudRenderCallback} 里调用。
     *
     * <p>内部自行 push/pop {@link MatrixStack}，不污染外部矩阵；同时把当前快照的目标值写进弹簧
     * （弹簧的推进在 {@link #tick(float)} 里完成）。</p>
     */
    public static void render(DrawContext ctx, MinecraftClient client, HudSnapshot s) {
        if (ctx == null || client == null || s == null) {
            return;
        }
        if (client.getWindow() == null) {
            return;
        }

        int scaledW = client.getWindow().getScaledWidth();
        int scaledH = client.getWindow().getScaledHeight();
        if (scaledW <= 0 || scaledH <= 0) {
            return;
        }

        // HUD 按 Minecraft 的 GUI 坐标绘制（不再跟物理分辨率走），所以它会随
        // 「界面大小」设置一起放大/缩小，和原版热键栏同一套尺度。设计常数来自
        // 1080p + GUI scale 3 的原型，因此 1 设计单位 ≈ 1/3 * 1.2 GUI 像素。
        float ui = DESIGN_TO_GUI;
        float dw = scaledW / ui;
        float dh = scaledH / ui;

        applyTargets(s);

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(ui, ui, 1f);

        // 先画模式附加效果（作用于世界之上、HUD 之下），再画 HUD 本体。
        renderEffects(ctx, client, s, dw, dh);
        renderTopBar(ctx, client, s, dw);
        renderModes(ctx, client, dw, dh);
        renderParams(ctx, client, s, dw, dh);
        renderHint(ctx, client, s, dw, dh);

        m.pop();
    }

    /**
     * 每个客户端 tick 调用，用真实帧间隔推进所有弹簧。
     *
     * @param deltaSeconds 距上一 tick 的秒数（已由调用方算好）
     */
    public static void tick(float deltaSeconds) {
        // 夹住 dt，避免卡顿后一帧跳变。
        float dt = Math.min(Math.max(deltaSeconds, 0f), 0.05f);
        if (dt <= 0f) {
            return;
        }
        time += dt;
        mode.update(dt);
        for (int i = 0; i < N_PARAMS; i++) {
            railW[i].update(dt);
            railH[i].update(dt);
            indAlpha[i].update(dt);
            valAlpha[i].update(dt);
        }
    }

    /** 退出/进入相机模式时清空动画状态，避免下次进入时残留上一次的位置。 */
    public static void reset() {
        time = 0f;
        mode.snap(0f);
        for (int i = 0; i < N_PARAMS; i++) {
            railW[i].snap(RAIL_IDLE);
            railH[i].snap(RAIL_IDLE);
            indAlpha[i].snap(0f);
            valAlpha[i].snap(0f);
        }
    }

    // ==================== 目标值 ====================

    /** 依据快照设置所有弹簧的目标值（不推进）。 */
    private static void applyTargets(HudSnapshot s) {
        mode.setTarget(s.modeIndex);

        int n = visibleParams(s);
        for (int i = 0; i < N_PARAMS; i++) {
            boolean active = i < n && i == s.paramIndex;
            railW[i].setTarget(active ? RAIL_ACTIVE_W : RAIL_IDLE);
            railH[i].setTarget(active ? RAIL_ACTIVE_H : RAIL_IDLE);
            indAlpha[i].setTarget(active ? 1f : 0f);
            // 只有选中项且确实有值时才显示数值文字。
            boolean hasValue = active && s.paramValues[i] != null && !s.paramValues[i].isEmpty();
            valAlpha[i].setTarget(hasValue ? 1f : 0f);
        }
    }

    private static int visibleParams(HudSnapshot s) {
        return Math.max(1, Math.min(s.paramCount, N_PARAMS));
    }

    // ==================== 顶部状态栏 ====================

    private static void renderTopBar(DrawContext ctx, MinecraftClient client, HudSnapshot s, float dw) {
        TextRenderer tr = client.textRenderer;
        float y = TOP_MARGIN;

        // 红色呼吸圆点（0.72 ~ 1.0 的透明度脉动）+ 柔光。
        float pulse = 0.72f + 0.28f * (float) (0.5 + 0.5 * Math.sin(time * 2.4f));
        float dotX = TOP_SIDE + 3f;
        float dotY = y + TEXT_GLYPH * 0.5f;
        fillCircle(ctx, dotX, dotY, 5.5f, withAlpha(0xFF5A5A, 0.28f * pulse));
        fillCircle(ctx, dotX, dotY, 3f, withAlpha(0xFF5A5A, pulse));

        // 左上 live 标签。
        drawTracked(ctx, tr, s.liveLabel, TOP_SIDE + 6f + 8f, y, withAlpha(0xFFFFFF, 0.5f), false, 1.2f);

        // 右上曝光读数，右对齐。
        if (s.readout != null && !s.readout.isEmpty()) {
            float tw = trackedWidth(tr, s.readout, 0.9f);
            drawTracked(ctx, tr, s.readout, dw - TOP_SIDE - tw, y, withAlpha(0xFFFFFF, 0.5f), false, 0.9f);
        }
    }

    // ==================== 左侧模式列表 ====================

    private static void renderModes(DrawContext ctx, MinecraftClient client, float dw, float dh) {
        TextRenderer tr = client.textRenderer;
        float sv = mode.value;          // 弹簧平滑后的模式索引
        float centerY = dh * 0.5f;

        for (int i = 0; i < HudSnapshot.MODE_NAMES.length; i++) {
            float d = i - sv;
            float ad = Math.abs(d);

            // 非线性衰减：越远越小、越淡、越往左缩（blur 无法真做，用透明度近似）。
            float scale = 1f / (1f + 0.32f * d * d);
            float opacity = 1f / (1f + 1.1f * d * d);
            float x = MODE_LEFT - Math.min(ad, 2.5f) * 7f;

            float textScale = MODE_TEXT_SCALE * scale;
            // transform-origin 为左侧中心：左右不位移，上下按缩放同步扩张。
            float yTop = centerY + d * MODE_SPACING - (TEXT_GLYPH * textScale) * 0.5f;

            // 最近项的柔和白色辉光（近似 text-shadow），越远越弱。
            float glow = 1f / (1f + 2.4f * d * d);
            if (glow > 0.05f) {
                int glowColor = withAlpha(0xFFFFFF, 0.16f * glow);
                for (float[] off : GLOW_OFFSETS) {
                    drawTextScaled(ctx, tr, HudSnapshot.MODE_NAMES[i], x + off[0], yTop + off[1],
                            textScale, glowColor, false);
                }
            }

            drawTextScaled(ctx, tr, HudSnapshot.MODE_NAMES[i], x, yTop, textScale,
                    withAlpha(0xFFFFFF, opacity), true);
        }
    }

    // ==================== 底部参数栏 ====================

    private static void renderParams(DrawContext ctx, MinecraftClient client, HudSnapshot s, float dw, float dh) {
        TextRenderer tr = client.textRenderer;
        int n = visibleParams(s);

        // 轨道总宽随选中项展开/收起而变化，因此先算总宽再整体居中。
        float totalW = PARAM_GAP * (n - 1);
        for (int i = 0; i < n; i++) {
            totalW += railW[i].value;
        }

        float cursor = (dw - totalW) * 0.5f;
        float cy = dh - RAIL_BOTTOM;

        for (int i = 0; i < n; i++) {
            float w = railW[i].value;
            float h = railH[i].value;
            boolean active = i == s.paramIndex;
            float x = cursor;
            cursor += w + PARAM_GAP;

            float y = cy - h * 0.5f;

            // 先铺一层选中轨道的淡蓝柔光，再画轨道本体（胶囊形，展开过程中自然从圆点过渡到细线）。
            if (active) {
                fillCapsule(ctx, x - 3f, y - 3f, w + 6f, h + 6f, withAlpha(0x78C8FF, 0.18f));
            }
            fillCapsule(ctx, x, y, w, h,
                    active ? withAlpha(0xFFFFFF, 0.16f) : withAlpha(0xFFFFFF, 0.30f));

            // 发光指示块：位置直接取 paramFill（0..1），透明度用弹簧淡入。
            float ia = clamp01(indAlpha[i].value);
            if (ia > 0.01f) {
                float fill = clamp01(s.paramFill[i]);
                float indW = 4f;
                float indH = 14f;
                float ix = x + fill * (w - indW);
                float iy = cy - indH * 0.5f;
                fillCapsule(ctx, ix - 1.5f, iy - 2f, indW + 3f, indH + 4f,
                        withAlpha(0x8CD2FF, 0.45f * ia));
                fillCapsule(ctx, ix, iy, indW, indH, withAlpha(0xFFFFFF, ia));
            }

            // 值文字：仅选中项淡入显示，格式与设计稿一致「参数名 数值」。
            float va = clamp01(valAlpha[i].value);
            String val = s.paramValues[i];
            if (va > 0.01f && val != null && !val.isEmpty()) {
                boolean isAuto = val.trim().equalsIgnoreCase("AUTO");
                int color = isAuto ? withAlpha(0xFFCC66, 0.9f * va) : withAlpha(0xFFFFFF, 0.85f * va);
                String label = HudSnapshot.PARAM_NAMES[i] + " " + val;
                float tw = tr.getWidth(label);
                drawTextScaled(ctx, tr, label, x + (w - tw) * 0.5f, cy + 13f, 1.05f, color, true);
            }
        }
    }

    // ==================== 底部提示 ====================

    private static void renderHint(DrawContext ctx, MinecraftClient client, HudSnapshot s, float dw, float dh) {
        if (s.hint == null || s.hint.isEmpty()) {
            return;
        }
        TextRenderer tr = client.textRenderer;
        float tw = tr.getWidth(s.hint);
        float y = dh - HINT_BOTTOM - TEXT_GLYPH;
        drawTextScaled(ctx, tr, s.hint, (dw - tw) * 0.5f, y, 1.0f, withAlpha(0xFFFFFF, 0.25f), true);
    }

    // ==================== 模式附加效果 ====================

    private static void renderEffects(DrawContext ctx, MinecraftClient client, HudSnapshot s, float dw, float dh) {
        int w = Math.round(dw);
        int h = Math.round(dh);

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
            int bar = Math.round(dh * 0.12f);
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
        // 1920/128 = 15 会让暗角贴图在屏幕上平铺成 15×8 个圆形（这才是
        // 「大量圆形半透明波纹」的真凶）。
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

    // ==================== 绘制工具 ====================

    /** 以给定缩放绘制一行文字：先平移到 (x,y)，再整体缩放（保持左上角对齐的文本缩放）。 */
    private static void drawTextScaled(DrawContext ctx, TextRenderer tr, String text,
                                       float x, float y, float scale, int color, boolean shadow) {
        if (text == null || text.isEmpty() || scale <= 0f) {
            return;
        }
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0f);
        m.scale(scale, scale, 1f);
        ctx.drawText(tr, text, 0, 0, color, shadow);
        m.pop();
    }

    /** 逐字符绘制以加入额外字距（模仿 HTML 的 letter-spacing）。 */
    private static void drawTracked(DrawContext ctx, TextRenderer tr, String text,
                                    float x, float y, int color, boolean shadow, float extra) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float cx = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if (!" ".equals(ch)) {
                ctx.drawText(tr, ch, Math.round(cx), Math.round(y), color, shadow);
            }
            cx += tr.getWidth(ch) + extra;
        }
    }

    /** 计算 {@link #drawTracked} 对应字符串的总宽度。 */
    private static float trackedWidth(TextRenderer tr, String text, float extra) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float w = 0f;
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            w += tr.getWidth(ch);
            count++;
        }
        return w + extra * Math.max(0, count - 1);
    }

    /** 用逐行填充近似一个实心圆。 */
    private static void fillCircle(DrawContext ctx, float cx, float cy, float r, int color) {
        if (r <= 0f) {
            return;
        }
        int ir = (int) Math.ceil(r);
        for (int dy = -ir; dy <= ir; dy++) {
            float span = (float) Math.sqrt(Math.max(0f, r * r - (float) dy * dy));
            int x1 = Math.round(cx - span);
            int x2 = Math.round(cx + span);
            if (x2 <= x1) {
                x2 = x1 + 1;
            }
            int y1 = Math.round(cy + dy);
            ctx.fill(x1, y1, x2, y1 + 1, color);
        }
    }

    /** 胶囊形：窄时近似圆点，宽时是两端圆角的细轨。 */
    private static void fillCapsule(DrawContext ctx, float x, float y, float w, float h, int color) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        float r = h * 0.5f;
        if (w <= h) {
            fillCircle(ctx, x + w * 0.5f, y + h * 0.5f, Math.min(w, h) * 0.5f, color);
            return;
        }
        ctx.fill(Math.round(x + r), Math.round(y), Math.round(x + w - r), Math.round(y + h), color);
        fillCircle(ctx, x + r, y + h * 0.5f, r, color);
        fillCircle(ctx, x + w - r, y + h * 0.5f, r, color);
    }

    /** 组装带 alpha 的 ARGB 颜色。 */
    private static int withAlpha(int rgb, float a) {
        int alpha = Math.round(clamp01(a) * 255f);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ==================== 弹簧 ====================

    /**
     * 阻尼弹簧（固定子步长积分，保证不同帧率下表现一致）。
     * 移植自 HTML 原型的 {@code Spring}：a = (-k*(v-target) - c*velocity) / m。
     */
    private static final class Spring {
        private float value;
        private float target;
        private float velocity;
        private final float stiffness;
        private final float damping;
        private final float mass;

        Spring(float value, float stiffness, float damping) {
            this.value = value;
            this.target = value;
            this.stiffness = stiffness;
            this.damping = damping;
            this.mass = 1f;
        }

        void setTarget(float t) {
            this.target = t;
        }

        /** 立即归位到某个值并清零速度。 */
        void snap(float v) {
            this.value = v;
            this.target = v;
            this.velocity = 0f;
        }

        void update(float dt) {
            if (dt <= 0f) {
                return;
            }
            int steps = Math.max(1, (int) Math.ceil(dt / 0.008f));
            float h = dt / steps;
            for (int i = 0; i < steps; i++) {
                float a = (-stiffness * (value - target) - damping * velocity) / mass;
                velocity += a * h;
                value += velocity * h;
            }
            // 收敛判定，避免长尾抖动。
            if (Math.abs(velocity) < 0.0015f && Math.abs(value - target) < 0.0008f) {
                value = target;
                velocity = 0f;
            }
        }
    }
}
