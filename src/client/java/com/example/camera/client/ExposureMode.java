package com.example.camera.client;

public enum ExposureMode {
    A_PLUS("A+"), P("P"), TV("Tv"), AV("Av"), M("M"), B("B");

    public final String name;

    ExposureMode(String name) { this.name = name; }

    public ExposureMode next() {
        ExposureMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public ExposureMode prev() {
        ExposureMode[] v = values();
        return v[(ordinal() - 1 + v.length) % v.length];
    }

    public boolean isoSwitchable() { return this != A_PLUS; }
    public boolean shutterSwitchable() { return this == TV; }
    public boolean apertureSwitchable() { return this == AV; }
    public boolean wbSwitchable() { return this != A_PLUS; }
    public boolean evCompSwitchable() { return this != M && this != B; }
}