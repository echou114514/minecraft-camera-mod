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

    public static AutoResult computeFullAuto(double sceneEV, int focalLength,
                                             float maxAperture, int slowestShutter,
                                             int manualIso, boolean fullFrame) {
        return solve(sceneEV, focalLength, maxAperture, slowestShutter,
                manualIso, fullFrame, 0f, Integer.MIN_VALUE);
    }

    public static AutoResult computeAv(double sceneEV, float manualAp, int focalLength,
                                       int slowestShutter, int manualIso, boolean fullFrame) {
        return solve(sceneEV, focalLength, manualAp, slowestShutter,
                manualIso, fullFrame, manualAp, Integer.MIN_VALUE);
    }

    public static AutoResult computeTv(double sceneEV, int manualShutter,
                                       float maxAperture, int manualIso, boolean fullFrame) {
        return solve(sceneEV, 50, maxAperture, manualShutter,
                manualIso, fullFrame, 0f, manualShutter);
    }

    /**
     * Snapmatica 式自动曝光预算：在「ISO / 光圈 / 快门」三根轴里搜一个分数最低的
     * 组合，而不是按固定阈值分段硬编码。
     *
     * <p>目标：曝光误差优先；其次压低 ISO（噪点）；再其次优先用较慢的快门（游戏里
     * 没有机震，长曝比高感干净）；光圈尽量落在 f/4 附近（镜头最锐）。</p>
     *
     * @param fixedAperture  &gt; 0 时锁定光圈（Av 模式）
     * @param fixedShutter   {@link Integer#MIN_VALUE} 以外时锁定快门（Tv 模式）
     */
    private static AutoResult solve(double sceneEV, int focalLength, float maxAperture,
                                    int slowestShutter, int manualIso, boolean fullFrame,
                                    float fixedAperture, int fixedShutter) {
        AutoResult best = new AutoResult();
        double bestScore = Double.MAX_VALUE;

        double minShutterSec = 1.0 / 4000.0;
        double maxShutterSec = Math.max(minShutterSec, Metering.shutterToSeconds(slowestShutter));
        double preferredSec = 1.0 / Math.max(8.0, focalLength * 2.0);
        double isoCap = fullFrame ? 6400.0 : 3200.0;

        int[] isoList = manualIso > 0 ? new int[] { manualIso } : ISO_STOPS;
        float fixedAp = fixedAperture > 0f ? snapApertureValue(fixedAperture) : 0f;

        for (int iso : isoList) {
            if (manualIso <= 0 && iso > isoCap) {
                continue;
            }
            double logIso = log2(iso / 100.0);

            for (float n : APERTURE_STOPS) {
                if (n < maxAperture - 1e-4f) {
                    continue;
                }
                if (fixedAp > 0f && Math.abs(n - fixedAp) > 1e-4f) {
                    continue;
                }
                for (int shutter : SHUTTER_STOPS) {
                    if (fixedShutter != Integer.MIN_VALUE && shutter != fixedShutter) {
                        continue;
                    }
                    double t = Metering.shutterToSeconds(shutter);
                    if (t < minShutterSec - 1e-9 || t > maxShutterSec + 1e-9) {
                        continue;
                    }

                    double ev = log2(n * n / t) - logIso;
                    double err = Math.abs(ev - sceneEV);

                    double score = err * 60.0;                       // 曝光准确度最重要
                    score += logIso * 0.85;                          // 尽量低 ISO
                    if (t > preferredSec) {
                        score += log2(t / preferredSec) * 0.08;      // 慢快门几乎不扣分
                    }
                    score += Math.abs(log2(n / 4.0)) * 0.16;         // 偏好 f/4

                    if (score < bestScore) {
                        bestScore = score;
                        best.iso = iso;
                        best.aperture = n;
                        best.shutter = shutter;
                    }
                }
            }
        }

        if (bestScore == Double.MAX_VALUE) {
            best.iso = manualIso > 0 ? manualIso : 100;
            best.aperture = maxAperture;
            best.shutter = slowestShutter;
        }
        return best;
    }

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