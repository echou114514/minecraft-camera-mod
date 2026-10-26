package com.example.camera.client;

import com.example.camera.client.metering.Metering;

public class AutoExposure {

    public static final int[] ISO_STOPS = {
            100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000,
            10000, 12800, 16000, 20000, 25600
    };

    public static final int[] SHUTTER_STOPS = {
            4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500,
            400, 320, 250, 200, 160, 125, 100, 80, 60, 50,
            40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4,
            -30, -40, -50, -60, -80, -100, -130, -160,
            -200, -250, -320, -400, -500, -600, -800, -1000,
            -1300, -1500, -2000, -2500, -3000
    };

    public static final float[] APERTURE_STOPS = {
            0.95f, 1.0f, 1.1f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f,
            3.5f, 4.0f, 4.5f, 5.0f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f,
            16f, 18f, 20f, 22f
    };

    public static class AutoResult {
        public int iso;
        public int shutter;
        public float aperture;
    }

    /**
     * A+ / P 全自动
     * 暗光提升曝光的阶段：
     *   1. 快门 → 2× 安全快门
     *   2. ISO 100 → 3200
     *   3. 快门 → 1× 安全快门
     *   4. ISO 3200 → 12800
     *   5. 快门 → 模式上限
     *   6. ISO 12800 → 25600
     * 亮光方向反过来
     */
    public static AutoResult computeFullAuto(double sceneEV, int focalLength,
                                             float maxAperture, int slowestShutter, int manualIso) {
        AutoResult r = new AutoResult();

        float N = maxAperture;
        double N2 = N * N;

        int safeVal = Math.max(1, focalLength);
        double tSafe  = 1.0 / safeVal;
        double t2Safe = 1.0 / (safeVal * 2.0);
        double t3Safe = 1.0 / (safeVal * 3.0);
        double tSlow  = Metering.shutterToSeconds(slowestShutter);

        boolean isoAuto = (manualIso < 0);
        int isoLow  = isoAuto ? 100   : manualIso;
        int isoMid1 = isoAuto ? 3200  : isoLow;
        int isoMid2 = isoAuto ? 12800 : isoLow;
        int isoHigh = isoAuto ? 25600 : isoLow;

        double logLow  = log2(isoLow  / 100.0);
        double logMid1 = log2(isoMid1 / 100.0);
        double logMid2 = log2(isoMid2 / 100.0);
        double logHigh = log2(isoHigh / 100.0);

        double EV_A = log2(64.0 / (1.0 / 4000.0)) - logLow;   // F8, 1/4000
        double EV_B = log2(64.0 / t3Safe) - logLow;           // F8, 3×safe
        double EV_C = log2(N2 / t3Safe) - logLow;             // maxAp, 3×safe
        double EV_D = log2(N2 / t2Safe) - logLow;             // maxAp, 2×safe
        double EV_E = log2(N2 / t2Safe) - logMid1;            // maxAp, 2×safe, ISO3200
        double EV_F = log2(N2 / tSafe) - logMid1;             // maxAp, safe, ISO3200
        double EV_G = log2(N2 / tSafe) - logMid2;             // maxAp, safe, ISO12800
        double EV_H = log2(N2 / tSlow) - logMid2;             // maxAp, slow, ISO12800
        double EV_I = log2(N2 / tSlow) - logHigh;             // maxAp, slow, ISO25600

        if (sceneEV >= EV_A) {
            r.iso = isoLow; r.shutter = 4000; r.aperture = 8f;
        } else if (sceneEV >= EV_B) {
            double t = 64.0 / Math.pow(2, sceneEV + logLow);
            r.iso = isoLow; r.aperture = 8f;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_C) {
            double Nreq = Math.sqrt(Math.pow(2, sceneEV + logLow) * t3Safe);
            float ap = snapApertureValue((float) Nreq);
            r.iso = isoLow;
            r.shutter = snapShutterFromSeconds(t3Safe);
            r.aperture = Math.max(ap, N);
        } else if (sceneEV >= EV_D) {
            double t = N2 / Math.pow(2, sceneEV + logLow);
            r.iso = isoLow; r.aperture = N;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_E) {
            double isoLog = log2(N2 / t2Safe) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoMid1, snapISO((int) Math.round(isoIdeal)));
            r.shutter = snapShutterFromSeconds(t2Safe);
            r.aperture = N;
        } else if (sceneEV >= EV_F) {
            double t = N2 / Math.pow(2, sceneEV + logMid1);
            r.iso = isoMid1; r.aperture = N;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_G) {
            double isoLog = log2(N2 / tSafe) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoMid2, snapISO((int) Math.round(isoIdeal)));
            r.shutter = snapShutterFromSeconds(tSafe);
            r.aperture = N;
        } else if (sceneEV >= EV_H) {
            double t = N2 / Math.pow(2, sceneEV + logMid2);
            r.iso = isoMid2; r.aperture = N;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_I) {
            double isoLog = log2(N2 / tSlow) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoHigh, snapISO((int) Math.round(isoIdeal)));
            r.shutter = slowestShutter;
            r.aperture = N;
        } else {
            r.iso = isoHigh; r.shutter = slowestShutter; r.aperture = N;
        }

        return r;
    }

    /** Av：光圈手动锁定，快门 + ISO 自动 */
    public static AutoResult computeAv(double sceneEV, float manualAp, int focalLength,
                                       int slowestShutter, int manualIso) {
        AutoResult r = new AutoResult();
        r.aperture = manualAp;

        double N2 = manualAp * manualAp;
        int safeVal = Math.max(1, focalLength);
        double t2Safe = 1.0 / (safeVal * 2.0);
        double tSafe = 1.0 / safeVal;
        double tSlow = Metering.shutterToSeconds(slowestShutter);

        boolean isoAuto = (manualIso < 0);
        int isoLow  = isoAuto ? 100   : manualIso;
        int isoMid1 = isoAuto ? 3200  : isoLow;
        int isoMid2 = isoAuto ? 12800 : isoLow;
        int isoHigh = isoAuto ? 25600 : isoLow;

        double logLow  = log2(isoLow  / 100.0);
        double logMid1 = log2(isoMid1 / 100.0);
        double logMid2 = log2(isoMid2 / 100.0);
        double logHigh = log2(isoHigh / 100.0);

        double EV_D = log2(N2 / t2Safe) - logLow;
        double EV_E = log2(N2 / t2Safe) - logMid1;
        double EV_F = log2(N2 / tSafe) - logMid1;
        double EV_G = log2(N2 / tSafe) - logMid2;
        double EV_H = log2(N2 / tSlow) - logMid2;
        double EV_I = log2(N2 / tSlow) - logHigh;

        if (sceneEV >= EV_D) {
            double t = N2 / Math.pow(2, sceneEV + logLow);
            r.iso = isoLow;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_E) {
            double isoLog = log2(N2 / t2Safe) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoMid1, snapISO((int) Math.round(isoIdeal)));
            r.shutter = snapShutterFromSeconds(t2Safe);
        } else if (sceneEV >= EV_F) {
            double t = N2 / Math.pow(2, sceneEV + logMid1);
            r.iso = isoMid1;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_G) {
            double isoLog = log2(N2 / tSafe) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoMid2, snapISO((int) Math.round(isoIdeal)));
            r.shutter = snapShutterFromSeconds(tSafe);
        } else if (sceneEV >= EV_H) {
            double t = N2 / Math.pow(2, sceneEV + logMid2);
            r.iso = isoMid2;
            r.shutter = snapShutterFromSeconds(t);
        } else if (sceneEV >= EV_I) {
            double isoLog = log2(N2 / tSlow) - sceneEV;
            double isoIdeal = 100.0 * Math.pow(2, isoLog);
            r.iso = Math.min(isoHigh, snapISO((int) Math.round(isoIdeal)));
            r.shutter = slowestShutter;
        } else {
            r.iso = isoHigh; r.shutter = slowestShutter;
        }

        return r;
    }

    /** Tv：快门手动锁定，光圈 + ISO 自动 */
    public static AutoResult computeTv(double sceneEV, int manualShutter,
                                       float maxAperture, int manualIso) {
        AutoResult r = new AutoResult();
        r.shutter = manualShutter;

        boolean isoAuto = (manualIso < 0);
        int isoLow = isoAuto ? 100 : manualIso;

        double t = Metering.shutterToSeconds(manualShutter);
        double N = Math.sqrt(Math.pow(2, sceneEV + log2(isoLow / 100.0)) * t);
        float snapped = snapApertureValue((float) N);

        r.iso = isoLow;

        if (snapped < maxAperture) {
            r.aperture = maxAperture;
            // 光圈已最大还是太暗 → 升 ISO
            if (isoAuto) {
                double evAtBase = log2(maxAperture * maxAperture / t);
                double isoLog = evAtBase - sceneEV;
                double isoIdeal = 100.0 * Math.pow(2, isoLog);
                r.iso = Math.min(25600, snapISO((int) Math.round(isoIdeal)));
            }
        } else if (snapped > 22f) {
            r.aperture = 22f;
        } else {
            r.aperture = snapped;
        }

        return r;
    }

    /** M / B：光圈快门手动，只算自动 ISO */
    public static int computeManualIso(double sceneEV, float aperture, int shutter) {
        double t = Metering.shutterToSeconds(shutter);
        double evAtIso100 = log2(aperture * aperture / t);
        double isoLog = evAtIso100 - sceneEV;
        double isoIdeal = 100.0 * Math.pow(2, isoLog);
        return Math.min(25600, Math.max(100, snapISO((int) Math.round(isoIdeal))));
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static int snapISO(int v) {
        int best = ISO_STOPS[0];
        for (int x : ISO_STOPS) if (Math.abs(x - v) < Math.abs(best - v)) best = x;
        return best;
    }

    private static int snapShutterFromSeconds(double tSec) {
        int best = SHUTTER_STOPS[0];
        double bestDiff = Double.MAX_VALUE;
        for (int s : SHUTTER_STOPS) {
            double sec = Metering.shutterToSeconds(s);
            double d = Math.abs(sec - tSec);
            if (d < bestDiff) { bestDiff = d; best = s; }
        }
        return best;
    }

    private static float snapApertureValue(float v) {
        float best = APERTURE_STOPS[0];
        for (float a : APERTURE_STOPS) {
            if (Math.abs(a - v) < Math.abs(best - v)) best = a;
        }
        return best;
    }
}