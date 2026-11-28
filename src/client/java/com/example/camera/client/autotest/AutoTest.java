package com.example.camera.client.autotest;

import com.example.camera.CameraMod;
import com.example.camera.ModItems;
import com.example.camera.client.CameraModClient;
import com.example.camera.client.HighResCapture;
import com.example.camera.client.config.CameraModConfig;
import com.example.camera.client.hud.ClassicHud;
import com.example.camera.client.hud.HudSnapshot;
import com.example.camera.item.CameraBodyItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Lifecycle;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.Difficulty;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.nbt.NbtCompound;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无人值守自动测试（`-Dcameramod.autoTest=<outDir|true>` 启用）。
 *
 * <p>流程：进入标题画面后创建一个不重名的 uuid 命名世界（第二次运行复用，
 * 目录名带固定前缀），等待加载并按「fps/tps/mspt 从波动到小范围浮动」判定
 * 稳定，然后依次截图手持样式、Neon/Classic 两套 HUD，并按 Iris/DH 开关组合
 * 实际导出多档位参数（普通模式、多快门、色温等）的 8K TIFF。所有产物写入
 * 指定目录，**下次启动时先清空**。Iris 开关走反射；DH 的存在性依据 mixin/
 * 日志行判定并从中提取构建进度百分比，开关走 DH API 反射（失败则跳过并记录）。</p>
 */
public final class AutoTest {

    private AutoTest() {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WORLD_PREFIX = System.getProperty("cameramod.autoTest.prefix", "cameramod-auto-test");
    private static final int EXPORTS = Math.max(0, Integer.getInteger("cameramod.autoTest.exports", 6));
    private static final int MIN_SETTLE_TICKS = 20 * 10;      // 加入世界后至少 10s
    private static final int SAMPLE_INTERVAL = 20;            // 1s 一个样本
    private static final int SAMPLE_WINDOW = 8;

    private enum Phase { INIT, WAIT_TITLE, CREATE, LOADING, SETTLE, STABILIZE, SEQUENCE, DONE, QUIT, FAILED }

    private static Phase phase = Phase.INIT;
    private static int waitTicks;
    private static int settleTicks;
    private static int sampleTimer;
    private static boolean worldActionTaken;
    private static String worldName = "";
    private static File outDir;
    private static File shotsDir;
    private static File exportsDir;
    private static File metricsFile;
    private static final List<String> metricSamples = new ArrayList<>();
    private static final List<double[]> sampleWindow = new ArrayList<>();
    private static final Map<String, Object> report = new LinkedHashMap<>();

    private static boolean irisPresent;
    private static boolean dhPresent;
    private static Object irisApiConfig;   // IrisApi.v0 getConfig()，Iris 1.8+ 官方开关
    private static Method irisStateMethod; // areShadersEnabled()
    private static Method irisApplyMethod; // setShadersEnabledAndApply(boolean)
    private static boolean irisTogglable;
    private static Object dhEntry;         // DH Config$Client.quickEnableRendering
    private static Method dhGetMethod;
    private static Method dhSetMethod;
    private static boolean dhTogglable;
    private static String dhParseProgress = "";
    private static String comboTag = "";

    private record Combo(String tag, boolean iris, boolean dh) {}

    private static final List<Combo> combos = new ArrayList<>();
    private static int comboIndex;
    private static int stepIndex;
    private static int stepWait;
    private static int quitTicks;
    private static final java.util.List<String> EXPORT_FORMATS =
            java.util.List.of("TIFF", "EXR", "DNG");
    private static String exportPrevFormat;
    private static String exportPresetName;
    private static List<File> exportBefore;
    private static int exportTicksLeft;
    private static boolean exportTriggerPending;

    /** 参数导出档位：ISO/EV 保持自动，变化快门与色温。 */
    private static final String[][] EXPORT_PRESETS = {
            {"auto", "P", "0", "0", "1"},
            {"s4000", "M", "4000", "0", "1"},
            {"s125", "M", "125", "0", "1"},
            {"s2s", "M", "-200", "0", "1"},
            {"wb2000", "P", "0", "2000", "0"},
            {"wb10000", "P", "0", "10000", "0"},
    };

    public static boolean enabled() {
        return System.getProperty("cameramod.autoTest") != null;
    }

    public static void start(MinecraftClient client) {
        // 测试默认 1/4 分辨率导出（1920×1080）：单张秒级完成，不拖垮机器。
        // 想全分辨率测试：-Dcameramod.exportScale=1.0 显式覆盖。
        if (System.getProperty("cameramod.exportScale") == null) {
            System.setProperty("cameramod.exportScale", "0.25");
        }
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
        CameraMod.LOGGER.info("[AutoTest] enabled (-Dcameramod.autoTest), out={}", System.getProperty("cameramod.autoTest"));
    }

    private static void tick(MinecraftClient client) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.QUIT) {
            quitTicks--;
            if (quitTicks == 0) {
                // 只走 scheduleStop()：vanilla 退出流程自带完整存档。实测先
                // disconnect() 会卡死在 DH 自己的 LOD 数据库关闭里。
                client.scheduleStop();
            } else if (quitTicks < -900) {
                // 45s 仍没退完 → DH 关库挂死（vanilla 存档在前面已完成），
                // 强制结束进程，不让整条测试流水线陪葬。
                CameraMod.LOGGER.warn("[AutoTest] shutdown hung, forcing halt(0)");
                Runtime.getRuntime().halt(0);
            }
            return;
        }
        try {
            switch (phase) {
                case INIT -> initPhase(client);
                case WAIT_TITLE -> waitTitlePhase(client);
                case CREATE -> createPhase(client);
                case LOADING -> loadingPhase(client);
                case SETTLE -> settlePhase(client);
                case STABILIZE -> stabilizePhase(client);
                case SEQUENCE -> sequencePhase(client);
                default -> { }
            }
        } catch (Throwable t) {
            CameraMod.LOGGER.error("[AutoTest] crashed, aborting", t);
            report.put("error", String.valueOf(t));
            writeReport();
            phase = Phase.FAILED;
        }
    }

    // ------------------------------------------------------------------
    // Phase: INIT
    // ------------------------------------------------------------------

    private static void initPhase(MinecraftClient client) {
        String prop = System.getProperty("cameramod.autoTest", "true");
        Path out = "true".equalsIgnoreCase(prop) || prop.isBlank()
                ? client.runDirectory.toPath().resolve("auto-test")
                : Path.of(prop);
        outDir = out.toFile();
        shotsDir = new File(outDir, "shots");
        exportsDir = new File(outDir, "exports");
        metricsFile = new File(outDir, "metrics.csv");
        try {
            Files.createDirectories(shotsDir.toPath());
            Files.createDirectories(exportsDir.toPath());
            // 下次启动清理：清空上一次的全部产物。
            deleteChildren(shotsDir);
            deleteChildren(exportsDir);
            Files.deleteIfExists(metricsFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        report.put("worldPrefix", WORLD_PREFIX);
        report.put("exportsPerCombo", EXPORTS);
        saveReport();

        detectEnvironment(client);
        if (irisPresent) {
            // 一键穷举检测：随 HDR 捕获导出 colortex 联览图，供确认 AUTO 结果。
            com.example.camera.client.IrisHdrSource.setTargetDumpDir(new File(outDir, "targets").toPath());
        }
        phase = Phase.WAIT_TITLE;
    }

    private static void detectEnvironment(MinecraftClient client) {
        // Iris：类是否存在（有 dev 运行时才在 classpath 上）。
        try {
            Class.forName("net.irisshaders.iris.Iris");
            irisPresent = true;
        } catch (ClassNotFoundException e) {
            CameraMod.LOGGER.info("[AutoTest] Iris not on classpath");
        }
        irisTogglable = false;
        // 首选 Iris 官方 API（v0：getConfig().setShadersEnabledAndApply）。
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object cfg = api.getMethod("getConfig").invoke(inst);
            irisStateMethod = cfg.getClass().getMethod("areShadersEnabled");
            irisApplyMethod = cfg.getClass().getMethod("setShadersEnabledAndApply", boolean.class);
            irisApiConfig = cfg;
            irisTogglable = true;
            CameraMod.LOGGER.info("[AutoTest] Iris toggle via IrisApi.v0");
        } catch (Throwable t) {
            // 老版本兜底：Iris.toggleShadersEnabled()
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                irisStateMethod = iris.getMethod("isShaderPackInUse");
                irisApplyMethod = iris.getMethod("toggleShadersEnabled");
                irisTogglable = true;
                CameraMod.LOGGER.info("[AutoTest] Iris toggle via legacy static toggle");
            } catch (Throwable t2) {
                CameraMod.LOGGER.warn("[AutoTest] Iris present but no toggle API; shader on/off skipped");
            }
        }

        // DH：依据日志（mixin 配置 / logger 名）判定，并提取构建进度百分比。
        String logText = readLogs(client);
        dhPresent = logText.toLowerCase(Locale.ROOT).contains("distanthorizons");
        if (dhPresent) {
            dhParseProgress = extractDhProgress(logText);
            CameraMod.LOGGER.info("[AutoTest] DH detected in logs, parse progress: {}", dhParseProgress);
        } else {
            CameraMod.LOGGER.info("[AutoTest] DH not detected in logs");
        }
        dhTogglable = false;
        // DH 3.x：Config$Client.quickEnableRendering（ConfigEntry<Boolean>）。
        try {
            Class<?> clientCfg = Class.forName("com.seibel.distanthorizons.core.config.Config$Client");
            dhEntry = clientCfg.getField("quickEnableRendering").get(null);
            dhGetMethod = dhEntry.getClass().getMethod("get");
            dhSetMethod = dhEntry.getClass().getMethod("set", Object.class);
            dhTogglable = true;
            CameraMod.LOGGER.info("[AutoTest] DH toggle via quickEnableRendering");
        } catch (Throwable t1) {
            CameraMod.LOGGER.info("[AutoTest] DH 3.x toggle unavailable ({}), trying DhApi walker", t1.toString());
            try {
                Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
                Object cfg = dhApi.getMethod("getConfig").invoke(null);
                Object clientObj = callFirst(cfg, "client", "clientConfig");
                Object rendering = callFirst(clientObj, "rendering", "renderingConfig");
                Object enabled = callFirst(rendering, "getEnabled", "enabled", "getEnableRendering");
                dhGetMethod = findGetter(enabled, "getValue", "getBooleanValue", "get");
                dhSetMethod = findSetter(enabled, "trySetValue", "setValue", "set");
                dhEntry = enabled;
                dhTogglable = dhGetMethod != null && dhSetMethod != null;
            } catch (Throwable t2) {
                CameraMod.LOGGER.info("[AutoTest] DH toggle unavailable: {}", t2.toString());
            }
        }

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("irisPresent", irisPresent);
        env.put("irisTogglable", irisTogglable);
        env.put("dhPresent", dhPresent);
        env.put("dhParseProgress", dhParseProgress);
        env.put("dhTogglable", dhTogglable);
        report.put("environment", env);
        saveReport();
    }

    private static String readLogs(MinecraftClient client) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[] {"logs/latest.log", "logs/debug.log"}) {
            try {
                Path p = client.runDirectory.toPath().resolve(name);
                if (Files.exists(p)) {
                    sb.append(Files.readString(p)).append('\n');
                }
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    private static String extractDhProgress(String logText) {
        Pattern p = Pattern.compile("(?i).{0,120}(?:distanthorizons|\\bDH\\b).{0,200}?(\\d{1,3})\\s*%");
        Matcher m = p.matcher(logText);
        String last = "";
        while (m.find()) {
            last = m.group(1) + "%";
        }
        return last;
    }

    // ------------------------------------------------------------------
    // Phase: WAIT_TITLE / CREATE / LOADING
    // ------------------------------------------------------------------

    private static void waitTitlePhase(MinecraftClient client) {
        if (client.world == null && client.player == null
                && client.currentScreen instanceof TitleScreen) {
            phase = Phase.CREATE;
            worldActionTaken = false;
        }
    }

    private static void createPhase(MinecraftClient client) {
        if (worldActionTaken) return;
        worldActionTaken = true;

        String existing = findExistingWorld(client);
        if (existing != null) {
            worldName = existing;
            CameraMod.LOGGER.info("[AutoTest] reusing existing world '{}'", worldName);
            report.put("worldName", worldName);
            report.put("worldAction", "reused");
            saveReport();
            client.createIntegratedServerLoader().start(worldName, () -> logWorldFailure(client));
            phase = Phase.LOADING;
            return;
        }

        worldName = WORLD_PREFIX + "-" + Long.toHexString(java.util.UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFL);
        // 保证不重名（isLevelNameValid 为 false 时换一个 uuid）。
        int attempts = 0;
        while (!client.getLevelStorage().isLevelNameValid(worldName) && attempts++ < 8) {
            worldName = WORLD_PREFIX + "-" + Long.toHexString(java.util.UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFL);
        }
        CameraMod.LOGGER.info("[AutoTest] creating world '{}'", worldName);
        report.put("worldName", worldName);
        report.put("worldAction", "created");
        saveReport();

        // 2 参 create() 自己 setScreen 并返回 void，从 currentScreen 取回实例。
        CreateWorldScreen.create(client, null);
        CreateWorldScreen screen = client.currentScreen instanceof CreateWorldScreen cws
                ? cws : null;
        if (screen == null) {
            throw new IllegalStateException("CreateWorldScreen did not open");
        }
        WorldCreator wc = screen.getWorldCreator();
        wc.setWorldName(worldName);
        wc.setGameMode(WorldCreator.Mode.CREATIVE);
        wc.setDifficulty(Difficulty.NORMAL);
        wc.setCheatsEnabled(true);
        wc.setGenerateStructures(true);

        // 照抄 vanilla CreateWorldScreen.createLevel() 的调用链：
        // tryLoad 的 Runnable 是「加载任务本体」（→ startServer → startNewWorld），
        // 传取消回调只会立刻被调用并卡在建档界面。
        GeneratorOptionsHolder holder = wc.getGeneratorOptionsHolder();
        DimensionOptionsRegistryHolder.DimensionsConfig config =
                holder.selectedDimensions().toConfig(holder.dimensionOptionsRegistry());
        CombinedDynamicRegistries<ServerDynamicRegistryType> registries =
                holder.combinedDynamicRegistries().with(ServerDynamicRegistryType.DIMENSIONS,
                        new DynamicRegistryManager.Immutable[]{config.toDynamicRegistryManager()});
        Lifecycle registryLifecycle = registries.getCombinedRegistryManager().getRegistryLifecycle();
        Lifecycle base = FeatureFlags.isNotVanilla(holder.dataConfiguration().enabledFeatures())
                ? Lifecycle.experimental() : Lifecycle.stable();
        Lifecycle lifecycle = base.add(registryLifecycle);
        boolean skipConfirm = registryLifecycle == Lifecycle.stable();   // recreated == false

        Runnable loadTask = () -> {
            try {
                Method startServer = screen.getClass().getDeclaredMethod("startServer",
                        LevelProperties.SpecialProperty.class,
                        CombinedDynamicRegistries.class, Lifecycle.class);
                startServer.setAccessible(true);
                startServer.invoke(screen, config.specialWorldProperty(), registries, lifecycle);
            } catch (Throwable t) {
                CameraMod.LOGGER.error("[AutoTest] startServer reflection failed", t);
                logWorldFailure(client);
            }
        };
        IntegratedServerLoader.tryLoad(client, screen, lifecycle, loadTask, skipConfirm);
        phase = Phase.LOADING;
    }

    private static String findExistingWorld(MinecraftClient client) {
        File saves = client.getLevelStorage().getSavesDirectory().toFile();
        File[] dirs = saves.listFiles(File::isDirectory);
        if (dirs == null) return null;
        File best = null;
        for (File d : dirs) {
            if (d.getName().startsWith(WORLD_PREFIX)) {
                if (best == null || d.lastModified() > best.lastModified()) best = d;
            }
        }
        return best == null ? null : best.getName();
    }

    private static void loadingPhase(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            CameraMod.LOGGER.info("[AutoTest] world '{}' loaded, settling", worldName);
            prepareCamera(client);
            phase = Phase.SETTLE;
            settleTicks = MIN_SETTLE_TICKS;
        }
    }

    // ------------------------------------------------------------------
    // Phase: SETTLE / STABILIZE
    // ------------------------------------------------------------------

    private static void settlePhase(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        settleTicks--;
        if (settleTicks > 0) {
            if (settleTicks % 40 == 0) {
                String msg = String.format("[AutoTest] 等待世界生成 %d/%ds…",
                        settleTicks / 20, MIN_SETTLE_TICKS / 20);
                CameraMod.LOGGER.info(msg);
                actionbar(client, msg);
            }
            return;
        }
        sampleTimer = 0;
        sampleWindow.clear();
        phase = Phase.STABILIZE;
    }

    private static void stabilizePhase(MinecraftClient client) {
        if (++sampleTimer < SAMPLE_INTERVAL) return;
        sampleTimer = 0;

        double fps = client.getCurrentFps();
        double mspt = client.getServer() != null ? client.getServer().getAverageTickTime() : 50f;
        double tps = Math.min(20.0, 1000.0 / Math.max(mspt, 1.0));
        sampleWindow.add(new double[] {fps, tps, mspt});
        if (sampleWindow.size() > SAMPLE_WINDOW) sampleWindow.remove(0);
        int n = sampleWindow.size();
        String line = String.format(Locale.US, "sample %d/%d: fps=%.1f tps=%.1f mspt=%.1f",
                Math.min(n, SAMPLE_WINDOW), SAMPLE_WINDOW, fps, tps, mspt);
        metricSamples.add(line);
        appendMetrics(line);
        CameraMod.LOGGER.info("[AutoTest] {}", line);
        actionbar(client, "[AutoTest] " + line);

        if (n < SAMPLE_WINDOW) return;

        double fpsMean = sampleWindow.stream().mapToDouble(a -> a[0]).average().orElse(0);
        double fpsMin = sampleWindow.stream().mapToDouble(a -> a[0]).min().orElse(0);
        double fpsMax = sampleWindow.stream().mapToDouble(a -> a[0]).max().orElse(0);
        double msptMin = sampleWindow.stream().mapToDouble(a -> a[2]).min().orElse(0);
        double msptMax = sampleWindow.stream().mapToDouble(a -> a[2]).max().orElse(0);
        double var = sampleWindow.stream().mapToDouble(a -> (a[0] - fpsMean) * (a[0] - fpsMean)).sum() / SAMPLE_WINDOW;
        double fpsSd = Math.sqrt(var);

        // 稳定判据：fps 波动收敛到小范围（相对标准差 < 15%，极差 <= 8%·均值
        // —— 高帧率下绝对阈值会永不收敛），mspt 极差 < 5ms（tps 由此推导）。
        double fpsRangeLimit = Math.max(10.0, fpsMean * 0.08);
        boolean stable = fpsMean >= 20.0 && fpsSd / Math.max(fpsMean, 1) <= 0.15
                && (fpsMax - fpsMin) <= fpsRangeLimit && (msptMax - msptMin) <= 5.0;

        if (stable) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fpsMean", round1(fpsMean));
            m.put("fpsSd", round1(fpsSd));
            m.put("fpsRange", round1(fpsMax - fpsMin));
            m.put("msptRange", round1(msptMax - msptMin));
            report.put("stability", m);
            saveReport();
            CameraMod.LOGGER.info("[AutoTest] metrics stable: {}", m);
            phase = Phase.SEQUENCE;
            comboIndex = 0;
            stepIndex = 0;
            stepWait = 0;
            buildCombos();
        }
    }

    private static void buildCombos() {
        combos.clear();
        boolean baseIris = irisPresent && shaderState();
        boolean baseDh = dhPresent && dhEnabled();
        combos.add(new Combo(tag(baseIris, baseDh), baseIris, baseDh));
        if (irisTogglable) {
            Combo c = new Combo(tag(!baseIris, baseDh), !baseIris, baseDh);
            if (combos.stream().noneMatch(x -> x.tag().equals(c.tag()))) combos.add(c);
        }
        if (dhTogglable) {
            Combo c = new Combo(tag(baseIris, !baseDh), baseIris, !baseDh);
            if (combos.stream().noneMatch(x -> x.tag().equals(c.tag()))) combos.add(c);
        }
        if (irisTogglable && dhTogglable) {
            Combo c = new Combo(tag(!baseIris, !baseDh), !baseIris, !baseDh);
            if (combos.stream().noneMatch(x -> x.tag().equals(c.tag()))) combos.add(c);
        }
        CameraMod.LOGGER.info("[AutoTest] capture combos: {}", combos.stream().map(Combo::tag).toList());
        report.put("combos", combos.stream().map(Combo::tag).toList());
        saveReport();
    }

    private static String tag(boolean iris, boolean dh) {
        String i = !irisPresent ? "irisNA" : (iris ? "irisOn" : "irisOff");
        String d = !dhPresent ? "dhNA" : (dh ? "dhOn" : "dhOff");
        return i + "_" + d;
    }

    // ------------------------------------------------------------------
    // Phase: SEQUENCE（组合循环：稳定 → 截图 → 导出）
    // ------------------------------------------------------------------

    private static void sequencePhase(MinecraftClient client) {
        if (stepWait > 0) {
            stepWait--;
            return;
        }
        if (comboIndex >= combos.size()) {
            finish(client);
            return;
        }
        Combo combo = combos.get(comboIndex);
        comboTag = combo.tag();

        // 每个组合开始：应用 Iris/DH 开关差异。
        if (stepIndex == 0) {
            if (irisTogglable && shaderState() != combo.iris()) {
                toggleShaders();
                stepWait = 400;    // Iris 全量资源重载 ~20s
                return;
            }
            if (dhTogglable && dhEnabled() != combo.dh()) {
                setDhEnabled(combo.dh());
                stepWait = 200;
                return;
            }
            stepIndex++;
            return;
        }

        // 步骤表：
        // 1=给相机 2=手持截图 3=Neon 4=Classic 5..n=导出档位
        int exportStep = stepIndex - 6;   // 步骤 1-6 是准备与截图，7 起为导出
        if (stepIndex == 1) {
            client.setScreen(null);
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            stepWait = 15;
            stepIndex++;
            return;
        }
        if (stepIndex == 2) {
            takeShot(client, "hand_" + comboTag);
            client.options.setPerspective(Perspective.FIRST_PERSON);
            stepWait = 10;
            stepIndex++;
            return;
        }
        if (stepIndex == 3) {
            CameraModConfig.get().uiStyle = "Neon";
            stepWait = 10;
            stepIndex++;
            return;
        }
        if (stepIndex == 4) {
            takeShot(client, "hud_neon_" + comboTag);
            CameraModConfig.get().uiStyle = "Classic";
            stepWait = 10;
            stepIndex++;
            return;
        }
        if (stepIndex == 5) {
            takeShot(client, "hud_classic_" + comboTag);
            CameraModConfig.get().uiStyle = "Neon";
            stepWait = 10;
            stepIndex++;
            return;
        }
        if (stepIndex == 6) {
            // 步骤 5 已把 uiStyle 复位为 Neon，此步仅占位，再进入导出档位。
            stepIndex++;
            return;
        }
        if (exportStep >= 1 && exportStep <= Math.min(EXPORTS, EXPORT_PRESETS.length)) {
            // 导出是异步管线（WAITING→CAPTURING→worker 写盘），必须靠帧推进，
            // 绝不能在渲染线程里 sleep 等待——那会饿死帧循环形成死锁。
            String[] preset = EXPORT_PRESETS[exportStep - 1];
            if (exportPresetName == null) {
                applyPresetToCamera(client, preset);
                exportPresetName = preset[0];
                exportBefore = listExports(client.runDirectory.toPath().resolve("screenshots").toFile());
                exportTicksLeft = 20 * 180;   // 3 分钟上限
                exportTriggerPending = true;
                stepWait = 5;   // 让 tickMetering 用新档位刷新 cached*，否则快门慢一档
                return;
            }
            if (exportTriggerPending) {
                exportTriggerPending = false;
                CameraModClient.executeCapture(client);
                return;
            }
            if (!HighResCapture.isBusy()) {
                List<File> after = listExports(client.runDirectory.toPath().resolve("screenshots").toFile());
                after.removeAll(exportBefore);
                if (!after.isEmpty()) {
                    File src = after.get(0);
                    File dst = new File(exportsDir, "export_" + comboTag + "_" + exportPresetName
                            + src.getName().substring(src.getName().lastIndexOf('.')));
                    try {
                        Files.copy(src.toPath(), dst.toPath());
                        reportExport(comboTag, exportPresetName, dst.getName() + " [" + validateMagic(dst) + "]");
                    } catch (IOException e) {
                        CameraMod.LOGGER.warn("[AutoTest] copy export failed: {}", e.toString());
                    }
                } else {
                    CameraMod.LOGGER.warn("[AutoTest] export finished but no new file for preset {}", exportPresetName);
                }
                exportPresetName = null;
                exportBefore = null;
                stepIndex++;
                stepWait = 10;
            } else if (--exportTicksLeft <= 0) {
                CameraMod.LOGGER.warn("[AutoTest] export timed out for preset {}", exportPresetName);
                reportTimedOutExport(comboTag, exportPresetName);
                exportPresetName = null;
                exportBefore = null;
                stepIndex++;
                stepWait = 10;
            }
            return;
        }
        int presetCount = Math.min(EXPORTS, EXPORT_PRESETS.length);
        int fmtStep = exportStep - presetCount - 1;
        if (exportStep > presetCount && fmtStep < EXPORT_FORMATS.size()) {
            String fmt = EXPORT_FORMATS.get(fmtStep);
            String[] preset = {"fmt_" + fmt.toLowerCase(java.util.Locale.ROOT), "P", "0", "0", "1"};
            if (exportPresetName == null) {
                // 覆盖本次导出格式（AutoTest 专属；结束后恢复原值）
                String prev = CameraModConfig.get().exportFormat;
                applyPresetToCamera(client, preset);
                CameraModConfig.get().exportFormat = fmt;
                exportPrevFormat = prev;
                exportPresetName = preset[0];
                exportBefore = listExports(client.runDirectory.toPath().resolve("screenshots").toFile());
                exportTicksLeft = 20 * 120;
                exportTriggerPending = true;
                stepWait = 5;
                return;
            }
            if (exportTriggerPending) {
                exportTriggerPending = false;
                CameraModConfig.get().exportFormat = fmt;   // applyPresetToCamera 后可能被读回
                CameraModClient.executeCapture(client);
                return;
            }
            if (!HighResCapture.isBusy()) {
                List<File> after = listExports(client.runDirectory.toPath().resolve("screenshots").toFile());
                after.removeAll(exportBefore);
                if (!after.isEmpty()) {
                    File src = after.get(0);
                    File dst = new File(exportsDir, "export_" + comboTag + "_" + exportPresetName
                            + src.getName().substring(src.getName().lastIndexOf('.')));
                    try {
                        Files.copy(src.toPath(), dst.toPath());
                        reportExport(comboTag, exportPresetName, dst.getName() + " [" + validateMagic(dst) + "]");
                    } catch (IOException e) {
                        CameraMod.LOGGER.warn("[AutoTest] copy export failed: {}", e.toString());
                    }
                } else {
                    CameraMod.LOGGER.warn("[AutoTest] format export finished but no new file ({})", fmt);
                }
                CameraModConfig.get().exportFormat = exportPrevFormat;
                exportPresetName = null;
                exportBefore = null;
                stepIndex++;
                stepWait = 10;
            } else if (--exportTicksLeft <= 0) {
                CameraMod.LOGGER.warn("[AutoTest] format export timed out ({})", fmt);
                reportTimedOutExport(comboTag, exportPresetName);
                CameraModConfig.get().exportFormat = exportPrevFormat;
                exportPresetName = null;
                exportBefore = null;
                stepIndex++;
                stepWait = 10;
            }
            return;
        }
        if (fmtStep >= EXPORT_FORMATS.size()) {
            comboIndex++;
            stepIndex = 0;
            stepWait = 10;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 只负责把参数档位写进相机数据；拍摄与等待由 tick 状态机完成。 */
    private static void applyPresetToCamera(MinecraftClient client, String[] preset) {
        ItemStack cam = client.player.getMainHandStack();
        NbtCompound nbt = CameraBodyItem.getSettings(cam);
        boolean auto = preset[1].equals("P");
        nbt.putString("mode", preset[1]);
        nbt.putBoolean("shutterAuto", auto);
        if (!preset[2].equals("0")) nbt.putInt("shutter", Integer.parseInt(preset[2]));
        nbt.putBoolean("wbAuto", preset[4].equals("1"));
        if (!preset[3].equals("0")) nbt.putInt("wb", Integer.parseInt(preset[3]));
        nbt.putBoolean("isoAuto", true);
        nbt.putBoolean("evAuto", true);
        CameraBodyItem.setSettings(cam, nbt);
        CameraMod.LOGGER.info("[AutoTest] preset {} applied (cameraMode={}, mainHand={})",
                preset[0], CameraModClient.isCameraMode(), cam.getItem());
    }

    private static void reportTimedOutExport(String tag, String preset) {
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) report.computeIfAbsent("exportTimeouts", k -> new ArrayList<String>());
        failed.add(tag + "/" + preset);
        saveReport();
    }

    private static void reportExport(String tag, String preset, String file) {
        @SuppressWarnings("unchecked")
        List<String> exports = (List<String>) report.computeIfAbsent("exports", k -> new ArrayList<String>());
        exports.add(tag + "/" + preset + " -> " + file);
        saveReport();
        CameraMod.LOGGER.info("[AutoTest] exported {} ({})", file, tag);
    }

    private static List<File> listExports(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".tiff") || n.endsWith(".exr") || n.endsWith(".dng"));
        return files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
    }

    /** 魔数校验：TIFF/DNG = II*\0，EXR = 0x762f3101；结果进 report。 */
    private static String validateMagic(File f) throws IOException {
        byte[] m = new byte[4];
        try (var in = java.nio.file.Files.newInputStream(f.toPath())) {
            int rd = in.read(m);
            if (rd < 4) return "too-short";
        }
        String hex = String.format("%02x%02x%02x%02x", m[0], m[1], m[2], m[3]);
        return switch (hex) {
            case "49492a00" -> "TIFF/DNG-ok";
            case "762f3101" -> "EXR-ok";
            default -> "unknown:" + hex;
        };
    }

    private static void prepareCamera(MinecraftClient client) {
        ItemStack cam = new ItemStack(ModItems.LUMIN_F);
        ItemStack lens = new ItemStack(ModItems.LENS);
        CameraBodyItem.installLens(cam, lens);
        client.player.getInventory().main.set(0, cam);
        client.player.getInventory().main.set(1, ItemStack.EMPTY);
    }

    private static void takeShot(MinecraftClient client, String name) {
        try {
            NativeImage img = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            File file = new File(shotsDir, name + ".png");
            img.writeTo(file);
            img.close();
            reportShot(name, file.getName());
            CameraMod.LOGGER.info("[AutoTest] shot {}", file.getName());
        } catch (Exception e) {
            CameraMod.LOGGER.error("[AutoTest] screenshot {} failed", name, e);
        }
    }

    private static void reportShot(String name, String file) {
        @SuppressWarnings("unchecked")
        List<String> shots = (List<String>) report.computeIfAbsent("shots", k -> new ArrayList<String>());
        shots.add(name + " -> " + file);
        saveReport();
    }

    private static void finish(MinecraftClient client) {
        exportPresetName = null;
        exportBefore = null;
        exportTriggerPending = false;
        if (exportPrevFormat != null) CameraModConfig.get().exportFormat = exportPrevFormat;
        client.options.setPerspective(Perspective.FIRST_PERSON);
        CameraModConfig.get().uiStyle = "Neon";
        CameraModConfig.get().save();
        report.put("metrics", metricSamples);
        report.put("dhParseProgressFinal", extractDhProgress(readLogs(client)));
        writeReport();
        phase = Phase.DONE;
        CameraMod.LOGGER.info("[AutoTest] DONE — artifacts in {}", outDir.getAbsolutePath());
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(
                    "[AutoTest] 全部完成！产物: " + outDir.getAbsolutePath()
                            + ("true".equalsIgnoreCase(System.getProperty("cameramod.autoTest.quit", "true"))
                                    ? "，即将保存并退出" : "")), false);
        }
        if ("true".equalsIgnoreCase(System.getProperty("cameramod.autoTest.quit", "true"))) {
            phase = Phase.QUIT;
            quitTicks = 100;   // 留 5s 给聊天消息与最终一帧
        }
    }

    private static void logWorldFailure(MinecraftClient client) {
        CameraMod.LOGGER.error("[AutoTest] world '{}' failed to load", worldName);
        report.put("error", "world load failed: " + worldName);
        writeReport();
        phase = Phase.FAILED;
    }

    // ------------------------------------------------------------------
    // Iris / DH reflection
    // ------------------------------------------------------------------

    private static boolean shaderState() {
        try {
            return (Boolean) irisStateMethod.invoke(irisStateMethod.getDeclaringClass().isInstance(irisApiConfig)
                    ? irisApiConfig : null);
        } catch (Throwable t) {
            return irisPresent;
        }
    }

    private static void toggleShaders() {
        try {
            if (irisApplyMethod.getParameterCount() == 1) {
                // IrisApi.v0：setShadersEnabledAndApply(!当前值)
                boolean target = !shaderState();
                irisApplyMethod.invoke(irisApiConfig, target);
                CameraMod.LOGGER.info("[AutoTest] shaders -> {}", target);
            } else {
                irisApplyMethod.invoke(null);
                CameraMod.LOGGER.info("[AutoTest] toggled shaders (legacy)");
            }
        } catch (Throwable t) {
            CameraMod.LOGGER.warn("[AutoTest] shader toggle failed: {}", t.toString());
        }
    }

    private static boolean dhEnabled() {
        try {
            return (Boolean) dhGetMethod.invoke(dhEntry);
        } catch (Throwable t) {
            return dhPresent;
        }
    }

    private static void setDhEnabled(boolean value) {
        try {
            dhSetMethod.invoke(dhEntry, value);
            CameraMod.LOGGER.info("[AutoTest] DH rendering -> {}", value);
        } catch (Throwable t) {
            CameraMod.LOGGER.warn("[AutoTest] DH toggle failed: {}", t.toString());
        }
    }

    private static Object callFirst(Object target, String... methodNames) throws Exception {
        for (String n : methodNames) {
            if (target == null) return null;
            try {
                Method m = target.getClass().getMethod(n);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Method findGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Method findSetter(Object target, String... names) {
        for (String n : names) {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == 1
                        && (m.getParameterTypes()[0] == boolean.class || m.getParameterTypes()[0] == Boolean.class)) {
                    return m;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Output helpers
    // ------------------------------------------------------------------

    private static void actionbar(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(text), true);
        }
    }

    private static void deleteChildren(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    deleteChildren(f);
                }
                Files.deleteIfExists(f.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static void appendMetrics(String line) {
        try {
            Files.writeString(metricsFile.toPath(), line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static synchronized void saveReport() {
        writeReport();
    }

    private static void writeReport() {
        try {
            File f = new File(outDir, "report.json");
            Files.writeString(f.toPath(), GSON.toJson(report));
        } catch (IOException e) {
            CameraMod.LOGGER.warn("[AutoTest] write report failed: {}", e.toString());
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
