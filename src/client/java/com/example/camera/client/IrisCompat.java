package com.example.camera.client;

import net.fabricmc.loader.api.FabricLoader;

public class IrisCompat {
    public static boolean isIrisLoaded = FabricLoader.getInstance().isModLoaded("iris");

    public static int getHdrTextureId() {
        if (!isIrisLoaded) return -1;
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object irisApi = irisApiClass.getMethod("getInstance").invoke(null);
            boolean shaderPackInUse = (boolean) irisApiClass.getMethod("isShaderPackInUse").invoke(irisApi);
            if (!shaderPackInUse) return -1;

            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            Object pipelineManager = irisClass.getMethod("getPipelineManager").invoke(null);
            Object pipeline = pipelineManager.getClass().getMethod("getPipeline").invoke(pipelineManager);
            if (pipeline == null) return -1;

            Object renderTargets = pipeline.getClass().getMethod("getRenderTargets").invoke(pipeline);
            if (renderTargets == null) return -1;

            // 破案了，CMP 的真 HDR 在 colortex5 里！
            int[] hdrTargets = {5, 4, 14, 13};
            for (int target : hdrTargets) {
                try {
                    Object colortex = renderTargets.getClass().getMethod("getColortex", int.class).invoke(renderTargets, target);
                    if (colortex != null) {
                        int id = (int) colortex.getClass().getMethod("getId").invoke(colortex);
                        System.out.println("[CameraMod] 成功锁定真 HDR 通道: colortex" + target + " (ID: " + id + ")");
                        return id;
                    }
                } catch (Exception ignored) {}
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}