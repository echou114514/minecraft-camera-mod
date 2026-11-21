package com.example.camera.client.hud;

/**
 * 阻尼弹簧（固定子步长积分，保证不同帧率下表现一致）。
 * 移植自 HTML 原型的 {@code Spring}：a = (-k*(v-target) - c*velocity) / m。
 * Neon 与 Classic 两套取景器 HUD 共用。
 */
public final class Spring {
    // 包内可见：Neon/Classic 两套 HUD 直接读 value 绘制。
    float value;
    private float target;
    private float velocity;
    private final float stiffness;
    private final float damping;
    private final float mass;

    public Spring(float value, float stiffness, float damping) {
        this.value = value;
        this.target = value;
        this.stiffness = stiffness;
        this.damping = damping;
        this.mass = 1f;
    }

    public void setTarget(float t) {
        this.target = t;
    }

    /** 立即归位到某个值并清零速度。 */
    public void snap(float v) {
        this.value = v;
        this.target = v;
        this.velocity = 0f;
    }

    public void update(float dt) {
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
