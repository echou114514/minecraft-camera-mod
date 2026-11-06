# 进度快照 (2026-11-05 晚) — Lumin 双机 + 3D 模型/动画 + HUD 重构（进行中）

> 关机前快照。**所有源码/资源已落盘**，改动**尚未 git commit**。
> 本次在 11-04 的基础上做了大改：单物品相机 → **两台 Lumin 相机（传感器固化）**；并启动了 HUD 重构。

## 本轮目标（用户 11-05 确认）
1. 改成**两种相机**，虚构品牌 **Lumin**：`Lumin F`(全画幅) / `Lumin H`(半画幅)；**传感器固化在机身**（更真实），
   只安装镜头；CMOS 物品改成**合成机身的材料**。
2. HUD 按 `refrence/new_hud.html` 重做，**模式只有 5 个**：`自动 / 照片 / 人像 / 夜景 / 电影`（其余忽略）。
3. 修「滚轮切到相机格卡一下」：根因是 `CameraModClient.applyCameraGuiScale()` 进出相机模式时强制
   GUI 缩放=1 并 `client.onResolutionChanged()`（重分配帧缓冲）。

---

## ✅ 已完成（本轮）

### 0. 修复启动卡住：GeckoLib 4 的 UV 格式
- 现象：`runClient` 加载资源时抛
  `camera-mod:geo/item/lumin.geo.json: Error loading model file`
  → `Not a JSON Object: [3.0,3.0,11.0,7.0]`，随后整包资源重载失败、启动卡在加载画面。
- 原因：**GeckoLib 4 不接受 Blockbench 原生 per-face UV 数组**。
  每个面必须是对象 `{ "uv": [u, v], "uv_size": [w, h] }`（`FaceUV`），
  而我们的转换脚本直接输出了 `[u1,v1,u2,v2]`。
- 修复：`refrence/build-tools/convert_bbmodel.py` 里把每个面的 UV 转成
  `uv=min` + `uv_size=|Δ|`，重跑脚本生成新的 `lumin.geo.json`。
- 验证：`./gradlew runClient` 资源重载无错误，正常进入标题画面
  （日志无 `Error loading model file`，仅有预期的 Iris 类未找到 WARN）。

### A. 双机 Lumin（编译通过）
- `ModItems.java`：新增 `LUMIN_F`(full) / `LUMIN_H`(half)，移除 `CAMERA_BODY`。
- `item/Jishen.java`：重写为「机身自带传感器」——
  - 构造 `Jishen(Settings, boolean fullFrame)`，实例方法 `isFullFrame()` / `instanceId()`；
  - 固定动画实例 ID 分两套：`INSTANCE_ID_F=0x1CA3E4A11L` / `INSTANCE_ID_H=0x1CA3E4A12L`；
  - `hasSensor()` 恒 true（机身自带），`isReady()` = 是 Lumin 机身且有镜头；
  - `installAction()` 只剩镜头；`use()` 只装镜头；`isCamera(stack)` 判两种机身；
  - tooltip 显示 `Lumin F · 全画幅` / `Lumin H · 半画幅`。
- `client/render/LuminGeoModel.java`（新）：共享 `geo/item/lumin.geo.json` + `animations/item/lumin.animation.json`，
  纹理按型号返回 `textures/item/lumin_f.png` 或 `lumin_h.png`。
- `client/render/CameraItemRenderer.java`：改用 `LuminGeoModel`，`getInstanceId` 返回 `animatable.instanceId()`，
  `preRender` 只隐藏 `lens` 骨骼（sensor 固化不再隐藏）。
- `CameraModClient.java`：渲染器同时注册到 `LUMIN_F` 与 `LUMIN_H`；`isCamera(stack)` 替换旧判断。
- `PostProcess.java`：`Jishen.isCamera(body)` 替换旧判断。

### B. 模型 / 纹理 / 动画资源（已重生成）
- 生成脚本：`refrence/build-tools/convert_bbmodel.py`（输入 `refrence/build-tools/camera_body.source.json`，
  即 Blockbench 工程的明文 JSON；改动后重跑即可）。
- 产物：
  - `assets/camera-mod/geo/item/lumin.geo.json`（8 骨骼 / 144 cubes，identifier `geometry.lumin`）
  - `assets/camera-mod/animations/item/lumin.animation.json`（`shutter` / `install_lens` / `install_sensor`）
  - `assets/camera-mod/textures/item/lumin_f.png`、`lumin_h.png`（128×128，H 的强调色改成青蓝）
  - `assets/camera-mod/models/item/lumin_f.json`、`lumin_h.json`（`builtin/entity` + display transforms）
  - `refrence/camera_body.bbmodel`（可编辑工程副本）
- 旧 `camera_body.*` 资源与模型/配方已删除。
- `install_sensor` 动画目前**未在玩法中触发**（传感器固化；保留资源备用）。

### C. 配方 / 语言
- `data/camera-mod/recipe/lumin_f.json` / `lumin_h.json`：机身配方，中央分别是 `full_frame_cmos` / `half_frame_cmos`
  （即 CMOS = 合成材料）。旧的 `camera_body.json` 删除。
- `lang/zh_cn.json`：`item.camera-mod.lumin_f` = `Lumin F 全画幅机身`，`lumin_h` = `Lumin H 半画幅机身`。

### D. dev Iris 卡顿/崩溃（参考 Exposure 分析结论）
- `build.gradle`：Iris/Sodium/DH 的 `modRuntimeOnly` 收进 **`-PwithIrisDev` 开关，默认不加载**，
  这样 `./gradlew runClient` 能只跑本模组（用于验证模型/HUD）。
- `TODO.md`：写入**根因证据**（Iris refmap 是 Mojang 名 vs 本项目 yarn 名 → `unmapDesc(null)` NPE；
  Exposure 用 Mojang 映射且 dev 不加载 Iris）+ 采用方案 5 的决策 + **Photographica/Snapmatica 新计算实现**
  的完整借鉴清单（CoC 公式、DofScale、入瞳模型、ISO 噪声、长曝光时钟等）。
- 参考分析子代理产出已并入 TODO.md（无需再跑）。

---

## ⏳ 进行中 / 未完成

### HUD 重构（**关键：子代理在关机时仍在运行，可能未写文件**）
- 已定契约文件：`src/client/java/com/example/camera/client/hud/HudSnapshot.java`（**已存在，勿改**）。
- 待子代理实现：`hud/CameraHud.java`，约定 API：
  ```java
  public static void render(DrawContext ctx, MinecraftClient client, HudSnapshot s);
  public static void tick(float deltaSeconds);
  public static void reset();
  ```
- **恢复时先检查 `hud/CameraHud.java` 是否存在**：
  - 若不存在/不完整 → 按 `HudSnapshot` + `refrence/new_hud.html` 重写（视觉：顶部栏/左侧模式列表弹簧动画/
    底部参数轨/模式附加效果；坐标用 `1/guiScale` 归一，**绝不改 GUI 缩放**）。
  - 若存在 → `./gradlew compileClientJava` 验证后再接线。
- 接线（在 `CameraModClient` 里，**尚未做**）：
  1. 删除 `applyCameraGuiScale()` 及其两处 `client.onResolutionChanged()`（修卡顿）。
  2. 新增 `CameraMode` 枚举（自动/照片/人像/夜景/电影），选择存相机 NBT（如 `Settings.hudMode`）。
  3. 输入：`↑/↓` 切模式、`滚轮` 切参数、`←/→` 调数值；滚轮需要在 `MouseHandler.onScroll` 加客户端 mixin
     （yarn 名 `onScroll(long,double,double)`，见 `refrence/1.21.1/client/net/minecraft/client/MouseHandler.java:129`），
     相机模式且无界面时取消原版切换物品栏并转给 HUD。
  4. 参数 6 项：曝光 / ISO / 快门 / 白平衡 / 对焦 / 焦距（焦距仅变焦头显示）。
  5. 模式→曝光预设：自动=A_PLUS 全 AUTO；照片=P；人像=AV 最大光圈+AF；夜景=TV 长快门+AutoISO；电影=M 1/48+AutoISO+档条+Log。
  6. 每帧填 `HudSnapshot`（readout、paramFill、paramValues、flags…）。

### 其它未完成
- `CameraSettingsScreen`（旧参数面板）目前保留、仍可用右键打开；将来可退役或与新 HUD 合并。
- `README.md` 仍是旧说明（副手镜头、单物品相机、合成表），需按 Lumin 双机重写。
- 实机验证：`./gradlew runClient`（现在默认无 Iris）验证模型、第一人称显示变换、`install_lens`/`shutter` 动画。
- `models/item/lumin_*.json` 的 display 位移/缩放/旋转需按实机微调。

---

## 恢复步骤
```bash
cd /mnt/ldata/minecraft-camera-mod
ls src/client/java/com/example/camera/client/hud/          # 看 CameraHud.java 是否已生成
python3 refrence/build-tools/convert_bbmodel.py            # 需要时重生成模型资源
./gradlew compileClientJava --console=plain                # 编译
./gradlew runClient --console=plain                        # 默认不含 Iris，可验证模型/HUD
# 需要 Iris 时: ./gradlew runClient -PwithIrisDev
```
- Blockbench：`DISPLAY=:1 nohup blockbench >/tmp/opencode/blockbench.log 2>&1 &`；MCP 服务 `http://127.0.0.1:8787`
  重启后可能需在 Blockbench 里手动启动。
- 若要再改模型：改 `refrence/camera_body.bbmodel` 或用 Blockbench，再导出/更新
  `refrence/build-tools/camera_body.source.json` 后重跑 `convert_bbmodel.py`。

## git 状态
本轮所有改动未提交。已改：`build.gradle` `gradle.properties` `fabric.mod.json` `ModItems.java` `item/Jishen.java`
`CameraModClient.java` `PostProcess.java` `CameraSettingsScreen.java` `lang/zh_cn.json`；新增：`hud/` `render/`
`geo/` `animations/` `models/item/lumin_*.json` `textures/item/lumin_*.png` `recipe/lumin_*.json`
`PROGRESS.md` `TODO.md`；删除：旧 `camera_body.*`。
