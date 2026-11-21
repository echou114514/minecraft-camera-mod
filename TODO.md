# TODO

> 2026-11-08 更新：1.2.0 大修完成（详见 CHANGELOG.md）。以下是当前遗留项。

## 阻塞项

- **dev 环境 Iris 集成（暂停中）**
  - 根因已确认：Iris 的 `iris.refmap.json` 使用 Mojang 官方名
    （key = `Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(...)`），
    本项目用 yarn（该类叫 `WorldRenderer`），dev 下 refmap 命不中且注解无描述符
    → `unmapDesc(null)` NPE。Iris/Sodium/DH 因此默认不在 dev 加载（`-PwithIrisDev` 才启用），
    光影行为用 `scripts/runModpack.sh` + 整合包验证。
  - 治本方向：迁移到 official Mojang mappings + Parchment（参考 `refrence/Exposure` 的做法）。

## 计划中

- **测试工程化**（本次大修刻意排除的一项）：为
  `DepthOfField.computeCocPixels`、`AutoExposure.solve`、`TiffWriter` 头部布局、
  `LensUpgradeRecipe` 三条升级路径补 JUnit / golden-image 回归测试；CI 已具备运行条件。
- **逐光影 HDR 适配表**：`irisHdrTarget` 目前靠配置 + 猜测扫描（colortex0–7 打分），
  值得维护一份「光影 → 缓冲号」文档或在配置里内置常见光影预设。
- **DataComponent 的服务端同步审查**：`camera-mod:camera_data` 现在是 typed 组件，
  自定义 `PacketCodec`（当前用 Codec 默认同步路径）可进一步收紧包大小。
- **发布自动化增强**：release workflow 已就绪，可加 Modrinth/CurseForge 上传步骤。
- **镜头升级配方书可视化**：特殊配方（lens_upgrade）无法在配方书展示，目前靠 advancement
  解锁 + README；可考虑给镜头 tooltip 补升级材料提示（已在 tooltip 有基础说明）。

## 已知取舍（有意为之，见代码注释）

- GeckoLib 动画按 per-body 固定 instanceId（`CameraBodyItem.INSTANCE_ID_F/H`），
  所有 Lumin F 栈共享动画状态；快门/安装动画是全局动作，可接受。改 per-stack 需要
  引入 animId 组件与服务端分配。
- 成片白平衡用 CCT→RGB 增益近似（Tanner Helland），非完整 Bradford 色适应矩阵。
- 测光 EV 模型是 -8..13 的线性近似，未做实机校准曲线。
