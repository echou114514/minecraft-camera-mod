package com.example.camera.client.metering;

public enum MeteringMode {
    EVALUATIVE("hud.camera-mod.metering.evaluative", 1.00),
    CENTER_WEIGHTED("hud.camera-mod.metering.center", 0.55),
    PARTIAL("hud.camera-mod.metering.partial", 0.30),
    SPOT("hud.camera-mod.metering.spot", 0.12);

    /** Translation key of the display name. */
    public final String nameKey;
    public final double spread;

    MeteringMode(String nameKey, double spread) {
        this.nameKey = nameKey;
        this.spread = spread;
    }

    public String displayName() {
        return net.minecraft.text.Text.translatable(nameKey).getString();
    }

    public MeteringMode next() {
        MeteringMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public MeteringMode prev() {
        MeteringMode[] v = values();
        return v[(ordinal() - 1 + v.length) % v.length];
    }
}
