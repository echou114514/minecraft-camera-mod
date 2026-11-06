# TODO / 暂停中的工作

> **2026-11-05 更新：根因已确认，dev Iris 已默认关闭。**
> 对照参考仓库 `refrence/Exposure`（另一个 mod）的脚手架后确认：
> 1. Iris 的 `iris.refmap.json` 命名空间是 **Mojang 官方名**
>    （key = `Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(...)`），
>    而本项目用 **yarn**（1.21.1+build.3 里该类已叫 `WorldRenderer`），
>    dev 下这份 refmap 永远命中不了，注解又没有描述符 → `unmapDesc(null)` NPE。
> 2. Exposure **根本不在 dev 里加载 Iris/Sodium**（可选兼容依赖统一用
>    `modCompileOnly`），光影只在整合包验证；且它用 `officialMojangMappings()`。
> **结论（采用 Exposure 方案 5）**：把 `build.gradle` 里的 Iris/Sodium/DH
> `modRuntimeOnly` 收进 `-PwithIrisDev` 开关，**默认不加载**，
> `./gradlew runClient` 只加载本模组，用于验证模型/HUD；光影行为用
> `scripts/runModpack.sh` + 整合包验证。
> 治本方向（若将来要迁映射）：改用 official Mojang mappings + Parchment。
>
> 状态：**暂停**。`gradlew runClient` 集成 Iris 受 Mixin 开发环境 refmap 重映射缺陷阻塞，暂时放弃继续折腾。
> 已有功能（HDR 捕获、曝光、景深、EV Auto）不受影响，均已提交在 `main` / `dh-compact` / `true_hdr` 上，但**尚未 push**。

相关提交：

- `0832886` feat(capture): read pre-tonemap HDR from Iris and harden capture
- `ed4327a` feat(exposure): auto EV by default with manual overexposure warning
- 本地领先 `origin/main` 6 个提交。

---

## 目标

用 `./gradlew runClient` 启动一个接近实机的开发环境：

- 本模组（Loom 重映射）
- Iris 1.8.14-beta.1 + Sodium 0.8.13-beta.1
- Complementary Reimagined r5.9.3
- Distant Horizons 3.3.3
- 光影包预选、HDR 目标设为 `colortex0`

---

## 已完成

### 捕获 / 曝光（已提交）

- 删除全局 `getFramebuffer()` 重定向、裸 `glViewport`、旧 HDR FBO、硬编码 `colortex5` 读取。
- 捕获改为：临时把窗口 framebuffer 尺寸改为 7680×4320 → `client.onResolutionChanged()` → 在 `MinecraftClient.render` 返回点恢复；`glReadPixels` 统一由 `GlReadback` 处理 FBO/PBO/pack 状态。
- Iris tonemap 前缓冲区通过反射读取，默认 `colortex5`（Eclipse），`-Dcameramod.irisHdrTarget=N` 可覆盖，`-1` 禁用。
- Complementary Reimagined 的 tonemap 前线性缓冲是 `colortex0`（`composite5` 做 `TM_EXPOSURE` + Lottes tonemap）。
- 曝光模型：`最终 = 自动基准(MIDDLE_GRAY/key) × 2^(-meterEV + evComp)`，ACES(Narkowicz) + sRGB LUT，DOF 在线性空间，HUD 不进入捕获。
- 景深：读取深度若无变化（全常数）判定无效并跳过 DOF；拍摄时隐藏手持物品；拍摄前等待 `-Dcameramod.settleFrames` 帧（默认 4）。
- EV Auto 默认 On；设置界面有 A/M 切换；手动滚动 EV 会关闭 Auto；手动净偏移超过 ±1 档时 HUD 变红；预览与成片统一 `totalEvOffset = -lastDiff + evComp`。
- 聊天栏文件名可点击打开。

### 开发环境（未提交）

- `build.gradle` 增加 Modrinth maven 与 `prepareDevRun`：
  - 从项目根 `dev-mods/` 优先取缓存，否则下载；
  - 把 Complementary 光影包放进 `run/shaderpacks`；
  - 首次生成 `run/config/iris.properties` 预选光影包；
  - `runClient` 自动依赖 `prepareDevRun`；
  - runClient 加 `-Dcameramod.irisHdrTarget=0`。
- `.gitignore` 增加 `dev-mods/`。
- `README.md` 增加"开发环境"章节（内容需要按最终方案修正）。
- `dev-mods/` 已放好 4 个本地文件：
  - `iris-fabric-1.8.14-beta.1+mc1.21.1.jar`
  - `sodium-fabric-0.8.13-beta.1+mc1.21.1.jar`
  - `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`
  - `ComplementaryReimagined_r5.9.3.zip`
- `prepareDevRun` 本身已验证可跑通（BUILD SUCCESSFUL，全部命中本地缓存）。

---

## 阻塞点：Iris mixin 在 dev 环境崩溃

### 现象

```
Mixin apply for mod iris failed mixins.iris.json:MixinLevelRenderer
  -> net.minecraft.client.render.WorldRenderer
org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException:
  Unexpected NullPointerException parsing @ModifyArg annotation on
  iris$writeRainAndSnowToDepthBuffer
Caused by: java.lang.NullPointerException:
  Cannot invoke "java.lang.CharSequence.length()" because "desc" is null
```

崩溃发生在 `MinecraftClient` 初始化、Sodium 触发 `RenderLayer` 类加载时。

### 崩溃链

```
RemappingReferenceMapper.remapWithContext
  -> RemappingReferenceMapper.remap
  -> RemapperChain.mapMethodName
  -> MixinIntermediaryDevRemapper.mapMethodName   (FIXME 分支)
  -> MixinRemapper.unmapDesc(null)
  -> MappingTreeView.mapDesc
  -> NPE
```

关键差异：

- **生产环境**：Iris 的 `@ModifyArg(method = "Lnet/minecraft/class_761;method_22714")`（intermediary），refmap 不参与解析，正常。
- **Loom 重映射后的 dev jar**：注解变成
  `method = ["Lnet/minecraft/client/render/WorldRenderer;renderWeather"]`
  —— **只有 owner + name，没有描述符**。
- Iris 的 `iris.refmap.json` 仍是 `named:intermediary`，其中 key 是
  `Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(...)`。
  1.21.1 + build.3 yarn 中该类已改名为 `WorldRenderer`，所以 refmap 查不到该条；
  未命中后进入 `MixinIntermediaryDevRemapper` 的 FIXME 分支，因为没有描述符，
  `unmapDesc(null)` 直接 NPE。

### 已验证 / 已排除

1. **不是 Loom 没有重映射 `files()`**
   `build/loom-cache/remapped_working/remapped.unspecified-*` 确实存在，注解已变 named。
   换成 `modRuntimeOnly "maven.modrinth:iris:..."` 同样崩溃。
2. **不是 Loader 版本问题**
   Fabric Loader 0.19.5 与 0.20.1 的 `MixinIntermediaryDevRemapper` 行为一致，
   都没有 null 保护。升级 Loader 不解决。
3. **不是单纯把 jar 丢进 `run/mods` 就能绕过**
   直接放生产 jar 时 Fabric Loader 会在运行期重映射，仍会走到同一路径。
4. **`-Dmixin.env.remapRefMap=false` 无效**
   `FabricMixinBootstrap.init` 在 `loader.load()` 之后无条件设置该属性为 `true`，
   覆盖 JVM 参数。
5. **Loom 没有改写 `iris.refmap.json`**
   jar 内仍是 `named:intermediary`，且 key 的类名仍是 `LevelRenderer`，
   与 1.21.1+build.3 的 `WorldRenderer` 不匹配，dev 下这份 refmap 实际不可用。
6. **Mixin issue 线索**
   - FabricMC/Mixin issue #55 "Mixin seems to be sending null descriptors to method remappers for some deobf cases"
   - FabricMC/fabric-loader PR #751
   - Indium issue #127
   —— 都指向同一类缺陷：匿名描述符在 dev remapper 中未做 null 保护。

---

## 后续可选方案（按成本排序）

1. **换 Iris 版本**
   寻找注解自带完整描述符、或 refmap 与 1.21.1 yarn 名称一致的 Iris 版本
   （1.8.7 / 1.8.8 已尝试下载但 CDN 极慢，未完成）。
   验证方法：解包 jar，`javap -v net/irisshaders/iris/mixin/MixinLevelRenderer.class`
   检查 `@ModifyArg.method` 是否带 `(...)V`。
2. **构建期补丁 Iris jar**
   用脚本把裸 named 注解改写为带描述符的完整形式，例如
   `Lnet/minecraft/client/render/WorldRenderer;renderWeather(Lnet/minecraft/client/renderer/LightTexture;FDDD)V`。
   注意应在 Loom 重映射**之后**执行，否则会被再次改写。
3. **构建期补生成 refmap 条目**
   在 `iris.refmap.json` 的 `mappings` 中补一条
   `net/irisshaders/iris/mixin/MixinLevelRenderer` →
   `Lnet/minecraft/client/renderer/WorldRenderer;renderSnowAndRain(...)` →
   `Lnet/minecraft/class_761;method_22714(...)`。
4. **给 Mixin 打补丁**
   在 dev 启动参数里替换/打补丁 `MixinIntermediaryDevRemapper`，让 `desc == null` 时跳过映射。
   成本最高，且要跟随 Loader 升级维护，不推荐。
5. **放弃 dev 集成**
   继续用整合包 + `scripts/runModpack.sh` 做运行时验证；`runClient` 只加载本模组，不加载 Iris。

---

## 恢复工作时

1. `build.gradle` 已恢复到实验前的版本（含 Distant Horizons、`dev-mods/` 缓存逻辑）。
2. 直接复现：
   ```bash
   ./gradlew runClient --console=plain
   # 崩溃日志：run/logs/latest.log、run/crash-reports/
   ```
   注意：`runClient` 会打开真实窗口；不要用 `| tail` 看进度，直接看 `run/logs/latest.log`。
3. 若选定方案 1/2/3，先只加载 Iris + Sodium 验证能进主菜单，再加 DH。
4. 启动成功后验证：
   - Complementary 光影正常加载；
   - DH LOD 正常渲染；
   - 日志出现 `[CameraMod] Iris HDR: colortex0 ...`；
   - 拍摄 TIFF 正常、曝光/景深无异常。
5. 验证通过后再更新 README 的开发环境章节并提交
   `build.gradle`、`.gitignore`、`README.md`。
6. push `main` / `dh-compact` / `true_hdr` 仍需用户确认。

---

## 未验证 / 遗留

- 无光影时 `Iris HDR 未就绪` 日志是否应静默（当前只在装有光影但取不到目标时打印？需复查）。
- README 的开发环境章节仍在描述"自动把模组放进 `run/mods`"，与 `build.gradle` 实际行为不完全一致，重启该工作时一并修正。
- Iris 1.8.8 下载残留：`/tmp/opencode/iris-1.8.8+mc1.21.1.jar`（仅约 1.1MB，不完整）。
- 参考资料：
  - `/tmp/opencode/build.gradle.bak`：本次实验前备份（已恢复回项目）。
  - `/tmp/opencode/fl`、`/tmp/opencode/fl201`：Loader 0.19.5 / 0.20.1 源码。
  - `/tmp/opencode/mixsrc`：Mixin 源码。
  - `/mnt/ldata/MC/.minecraft/versions/1.21.1_ExperiencesOptimized/mods`：整合包同版本 jar 来源。

---

# 新计算实现（参考 Photographica / Snapmatica）

> 来源：`refrence/photographica/`（含 Snapmatica）。分析结论：该仓库里有**两套**实现，
> Snapmatica（`snapmatica/`）算力最重、最值得借鉴；Photographica（`src/main/.../photographica/`）是胶片暗房流程。
> 下面按「可直接搬的数学 → 重度耦合的部分」排序，供本项目后续实现时对照。

## 现状对照（本项目已有）
- 曝光：`AutoExposure` + `Metering`（CPU 射线取方块光照），ACES(Narkowicz)+sRGB LUT。
- 景深：`DepthOfField`（读深度图，后处理）。
- 捕获：8K HDR（Iris tonemap 前缓冲）、TIFF（自写 `TiffWriter`）。
- 画幅：全/半 = `sensorHeight` 24/16，仅影响 FOV 与传感器命名。

## 值得抄的 5 条（按价值排序）

### 1. 薄透镜 CoC + 衍射平方和 + 软膝（散景公式）
Snapmatica `snapmatica/.../shaders/evf_blur.fsh:355-408`：
```
coc_mm = f² · |S2 - S1| / ( S2 · N · (S1 - f) )      // 有限对焦
coc_mm = f² / ( N · S2 · DofScale )                   // 无穷远对焦
airy   = 2.44 · 0.00055 · N                            // 550nm 艾里斑
coc_mm = sqrt(coc_mm² + airy²)                         // 失焦⊕衍射
CoC_px = coc_mm · PxPerMm
softKnee: g = c² / (c + 1.5)                           // 0 处值与斜率都为 0
```
要点：先把 sRGB 线性化再卷积；tap 数随圆盘面积自适应；三层（前景/焦点/背景）+ tile reach map。
**本项目 TODO**：把 `DepthOfField` 的景深从「启发式」换成上面的薄透镜 CoC（用 `LensSpec` 焦距/光圈、
`focus` 距离、`focalLength/ aperture`），并叠加衍射项；`isFullFrame` 的 24/16 换成 `sensorHeightMm`。

### 2. 「世界尺度」旋钮 DofScale（mm / 方块）
Snapmatica `SnapmaticaClient.java:97` + `EvfBlurRenderer.java:190`：默认 **375 mm/方块**（1 方块=37.5cm）。
这是让物理公式「旋即可用」的关键；建议做成设置项 `-Dcameramod.mmPerBlock`，默认 375。

### 3. 以入瞳直径为核心的镜头模型（f = 焦距 / 入瞳）
Snapmatica `SnapmaticaClient.java:61-79`：状态存**入瞳直径** `D = focal / N`；变焦时用 `N = f/D` 反推 f 值，
并夹在 `[1.4, 32]`。→ 变焦时 f 值自然变化（像真套头 f/3.5-5.6），避免把 N 当独立旋钮。
**本项目 TODO**：`LensSpec` 增加 `apertureDiameter`（或由 `focalLength/maxAperture` 起始推导，变焦时联动）。

### 4. ISO 噪声 = 亮度 + 低频色度，且 ×画幅裁切系数
Snapmatica `PhotoCapture.java:947-1063, 1236-1279`：
```
lum = (rand-0.5)·noiseSigma·1.5
r += lum + cr ;  g += lum - (cr+cb)/2 ;  b += lum + cb     // 色度项在亮度上和为 0
noiseSigma = isoToNoiseSigma(iso) · max(0.2, sensorCropFactor)
chromaSigma = noiseSigma · chromaNoiseRatio(iso)
```
- 色度噪声在 **3×3 粗网格生成再双线性插值**（去马赛克空间相关），且**随 ISO 上升**（0.15→0.80）。
- `isoToNoiseSigma` 表：100→0, 200→1.5, 400→3, 800→6, 1600→11, 3200→18, 6400→28, 12800→42, ≥→60。
- **×cropFactor** 实现「等效 ISO」：小底同 ISO 更噪（全幅×1.0，APS-C×1.5）。
**本项目 TODO**：`PostProcess.computeLumaNoise/computeChromaNoise` 改成上表 + 低频色度 + cropFactor；
暗角强度也按光圈查表（f/1.4→0.70 … ≥f/8→0.03，`PhotoCapture.java:1226-1234`）。

### 5. 时间积分型长曝光 + 把「世界时钟」改成「曝光时钟」
Snapmatica `PhotoCapture.java:198-362` + `mixin/{WorldTime,CloudTime,IrisTimer,ParticleTime}Mixin.java`：
- 快门 ≥ 1/30s 时逐帧采样累加（最多 120 样本），并有「相邻两帧取中点当虚拟样本」降噪；
- 曝光期间把世界时间/云/Iris `frameTimeCounter`/粒子时间按曝光倍数推进，所以 30s 快门真的把太阳/云拉出轨迹。
**本项目 TODO（后期）**：先做「逐帧累加平均」即可获得长曝夜景；时钟重定需要新 mixin，
目标会随版本变动，务必和 `TODO.md` 的 Iris dev 问题一起规划。

## 其他可借鉴
- **测光**：Snapmatica 是「中央重点」——5 条射线（中心+±18°环，权重 2/1/1/1/1），取方块光照，
  `stopsDark = max(0, 11 - meteredLight)` 缓动 0.06/tick。可作为 `MeteringMode` 的新模式。
- **自动曝光预算分配**：先吃「光圈/快门轴」3 档，再吃 ISO 余量，超了回轴
  （`SnapmaticaClient.java:1092-1159`）；档位取最近用**对数距离**（`:1196`）。
- **白平衡**：从 Planck 黑体轨迹（Kim 三次拟合）反推线性 sRGB，增益按亮度归一（不改曝光）——
  `planckianLinearRgb()`/`whiteBalanceGain()`（`:972-1029`）；AWB 从 MC 光照公式反推光源色再 mired 缓动。
- **色调**：动态范围软肩 `out=min(x,SHOULDER)+over/(1+3·over)`、中间调 S 曲线、高光 rolloff
  （`evf_blur.fsh:425-458`）。
- **FOV**：`2·atan(vHalfMm / imageDistance)`，其中含**对焦呼吸** `v = f·S/(S-f)`（`SnapmaticaClient.java:436-457`）。
- **EXIF / DNG**：`PhotoExif.java`、`DngWriter.java`、`Tiff.java` 是自包含纯 Java，可直接复用
  （本项目的 `TiffWriter` 已有基础）。
- **镜头畸变/色差/猫眼 bokeh/焦点 peaking**：`evf_blur.fsh`（畸变反算读坐标、三通道径向缩放、
  `catsEye`、`evf_peaking.fsh`）。

## 耦合警告（不要照抄）
光圈积分 `ApertureIntegration.java`、时间重定 mixin、Iris 兼容分支重度依赖 MC 渲染管线细节与
mixin 目标，1.21.1 移植需重新定位所有 hook 点。第 1–4 条的**数学**基本零耦合，优先做。
