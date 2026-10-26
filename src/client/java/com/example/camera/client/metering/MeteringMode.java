package com.example.camera.client.metering;

public enum MeteringMode {
    EVALUATIVE("评价测光", 1.00),
    CENTER_WEIGHTED("中央重点", 0.55),
    PARTIAL("局部测光", 0.30),
    SPOT("点测光", 0.12);

    public final String displayName;
    public final double spread;

    MeteringMode(String displayName, double spread) {
        this.displayName = displayName;
        this.spread = spread;
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