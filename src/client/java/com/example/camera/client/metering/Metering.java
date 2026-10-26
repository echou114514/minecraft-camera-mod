package com.example.camera.client.metering;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class Metering {
    private static final double MAX_DIST = 80.0;
    private static final int INTERVAL_TICKS = 1;
    private static final double EMA_ALPHA = 0.3;

    private static final int GRID_X = 15;
    private static final int GRID_Y = 10;
    private static final int TOTAL_POINTS = GRID_X * GRID_Y;
    private static final double[][] GRID;

    static {
        GRID = new double[TOTAL_POINTS][2];
        int i = 0;
        for (int y = 0; y < GRID_Y; y++) {
            for (int x = 0; x < GRID_X; x++) {
                GRID[i][0] = (x / (double) (GRID_X - 1)) * 2 - 1;
                GRID[i][1] = (y / (double) (GRID_Y - 1)) * 2 - 1;
                i++;
            }
        }
    }

    public static MeteringMode currentMode = MeteringMode.EVALUATIVE;
    private static final double[] lightValues = new double[TOTAL_POINTS];
    private static double smoothedEV = 0.0;
    private static boolean initialized = false;
    private static int tickCounter = 0;

    public static double getSceneEV() { return smoothedEV; }

    public static void tick(MinecraftClient client, double fov) {
        if (++tickCounter < INTERVAL_TICKS) return;
        tickCounter = 0;
        if (client.player == null || client.world == null) {
            smoothedEV = 0.0;
            initialized = false;
            return;
        }
        double raw = computeSceneEV(client, fov);
        if (!initialized) {
            smoothedEV = raw;
            initialized = true;
        } else {
            smoothedEV = smoothedEV * (1 - EMA_ALPHA) + raw * EMA_ALPHA;
        }
    }

    private static double computeSceneEV(MinecraftClient client, double fov) {
        World world = client.world;
        Entity cam = client.getCameraEntity();
        if (cam == null) cam = client.player;

        Vec3d eye = cam.getEyePos();
        double spread = currentMode.spread;

        long timeOfDay = world.getTimeOfDay() % 24000L;
        double daylightFactor;
        if (timeOfDay < 12000L) daylightFactor = 1.0;
        else if (timeOfDay < 13000L) daylightFactor = 1.0 - (timeOfDay - 12000L) / 1000.0;
        else if (timeOfDay < 22800L) daylightFactor = 0.0;
        else daylightFactor = (timeOfDay - 22800L) / 1200.0;

        for (int idx = 0; idx < GRID.length; idx++) {
            double[] pt = GRID[idx];
            double px = pt[0] * spread;
            double py = pt[1] * spread;

            float yaw = cam.getYaw() + (float) (px * fov * 0.5);
            float pitch = cam.getPitch() + (float) (py * fov * 0.5);

            Vec3d dir = Vec3d.fromPolar(pitch, yaw);
            Vec3d end = eye.add(dir.multiply(MAX_DIST));

            BlockHitResult hit = world.raycast(new RaycastContext(
                    eye, end,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.ANY,
                    cam));

            if (hit.getType() == HitResult.Type.MISS) {
                lightValues[idx] = 15.0 * daylightFactor;
            } else {
                BlockPos airPos = hit.getBlockPos().offset(hit.getSide());
                int sky = world.getLightLevel(LightType.SKY, airPos);
                int block = world.getLightLevel(LightType.BLOCK, airPos);
                lightValues[idx] = Math.max(sky * daylightFactor, block);
            }
        }

        double s = 0;
        for (double v : lightValues) s += v;
        double avgLight = s / lightValues.length;

        return -8.0 + avgLight * (21.0 / 15.0);
    }

    public static double shutterToSeconds(int shutterValue) {
        if (shutterValue > 0) return 1.0 / shutterValue;
        return -shutterValue / 100.0;
    }

    public static String formatShutter(int v) {
        if (v > 1) return "1/" + v;
        if (v == 1) return "1s";
        double sec = -v / 100.0;
        if (sec == (int) sec) return ((int) sec) + "s";
        return String.format("%.1fs", sec);
    }

    public static double cameraEV(float aperture, int shutterValue, int iso) {
        double t = shutterToSeconds(shutterValue);
        double ev = Math.log(aperture * aperture / t) / Math.log(2);
        ev -= Math.log(iso / 100.0) / Math.log(2);
        return ev;
    }

    public static double exposureDiff(float aperture, int shutterValue, int iso) {
        return cameraEV(aperture, shutterValue, iso) - smoothedEV;
    }
}