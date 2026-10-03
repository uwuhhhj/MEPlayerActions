# MEPlayerActions 0.4.3 文档索引

本索引对应 0.4.3 现行代码。**服务器伪装的客户端渲染和即时动画默认开启；私人本地模型初始未启用。** 服务器默认主动同步完整原模型，不需要 MPA 资源包索引或离线合并。两类显示共用渲染总开关。J 是唯一默认快捷键，轮盘设置齿轮直接打开模型图库主页；新的本人服务器伪装自动 SERVER，同一实例内可明确选 CLIENT 私人覆盖，解除后恢复保存的私人选择。

## 用户与管理员

项目核心是服务器 `.bbmodel` 伪装同时支持两类观看者：未安装客户端模组者通过 ME／服务器资源包显示，安装模组者通过 MPA 原模型推送接管本地渲染；被伪装者本人无需安装模组。玩家自行安装 YSM 模型作为私人本地皮肤则延续 OpenYSM 原有能力，归入 CLIENT 路线。完整定位与来源区分见 [项目定位](../README.md#项目定位与-openysm-的关系)。

| 需求 | 入口 |
| --- | --- |
| 安装、默认行为、按键、服务器指令 | [项目说明](../README.md) |
| 客户端全部默认值、私人/服务器显示优先级、命令与排查 | [客户端配置](CLIENT_CONFIG.md) |
| 服务器原模型来源、主动同步、缓存与 ME／CE 分工 | [模型同步与部署](MODEL_DELIVERY.md) |
| YSM 动作、作者配置、皮肤、组件与验证边界 | [YSM 兼容范围](YSM_COMPATIBILITY.md) |
| 内置模型作者、CC0／酒狐 CC BY-NC-SA 4.0 与 GUI MIT 许可 | [第三方说明](../THIRD_PARTY_NOTICES.md) |
| 酒狐模型资产完整许可 | [CC-BY-NC-SA-4.0.txt](licenses/CC-BY-NC-SA-4.0.txt) |
| 参考项目软件 MIT 许可原文 | [LICENSE.OpenYSM.txt](../client/src/main/resources/assets/meplayeractions/builtin/openysm_default/LICENSE.OpenYSM.txt) |

## 开发与适配

| 需求 | 入口 |
| --- | --- |
| 系统结构、接管/恢复生命周期、构建和验收入口 | [架构](../ARCHITECTURE.md) |
| v3 消息、主动推送、资产身份、权限、旧模式兼容与限流 | [客户端协议](../src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md) |
| 0.4.1 资源包模式的索引、共享贴图及离线工具 | [旧客户端资源包格式](CLIENT_RESOURCE_PACK.md) |

未知模型引擎需要服务端适配器，客户端不自动识别引擎。普通 YSM 文件夹已支持原版动画、控制器／物理、作者菜单／表单、皮肤及私人组件；它与 MPA 模型推送、OpenYSM 原网络协议是不同边界。0.4.2 的独立客户端历史实机已覆盖作者预览、配置保存、第一人称手臂、部分原版组件及本地声音／粒子的播放和清理。作者函数、生命周期事件与本地 `ysm.sync` 有直接执行的单元回归证明；这不代表全部作者动作或多人路径都已通过实机。外部模组集成、加密 `.ysm`、网格绑定和 OpenYSM 原网络协议未迁移，不能泛称完整 OpenYSM 已移植。

## 对照源码

下列链接用于仓库或源码包；安装包以用户说明为主，不包含全部源码。

| 内容 | 当前实现 |
| --- | --- |
| 客户端顶层默认配置、读取与保存 | [ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java) |
| 私人模型默认值、ID 与缩放/XYZ 范围 | [LocalAppearanceSettings](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceSettings.java) |
| 按键与 `/mpaclient` 全部命令 | [MEPlayerActionsClient](../client/src/main/java/com/simmc/meplayeractions/client/MEPlayerActionsClient.java) |
| 服务器接管、私人模型优先级、轨迹选项 | [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java)、[LocalAppearanceVisibility](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceVisibility.java) |
| 普通动作即时采样与服务端映射约束 | [EntityAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/EntityAnimationController.java)、[LocalMotionPolicy](../client/src/main/java/com/simmc/meplayeractions/client/LocalMotionPolicy.java) |
| 第一人称、本人与其他玩家的模型绘制 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) |
| 内置默认、本地 BBModel、YSM 文件夹与作者资源 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java)、[YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[YsmModelProfile](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmModelProfile.java) |
| 有类型 Molang、控制器、实例物理与原版查询 | [Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java)、[VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) |
| 作者 extra 分类、动态表单和按模型保存 | [ModelActionMenu](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelActionMenu.java)、[ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[ModelConfigScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigScreen.java)、[ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java) |
| 私人组件、原版物品／装备与本地 FX | [YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java)、[YsmItemRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java)、[YsmEquipmentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java)、[YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java) |
| 服务器模型传输、校验与缓存 | [AssetTransfer](../client/src/main/java/com/simmc/meplayeractions/client/network/AssetTransfer.java)、[ServerModelCache](../client/src/main/java/com/simmc/meplayeractions/client/network/ServerModelCache.java) |
| 旧模式资源包读取与原始 SHA-256 校验 | [PackModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/PackModelLibrary.java) |
| 直接图库主页、模型详情与经典轮盘 | [PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java)、[LocalAppearanceScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/LocalAppearanceScreen.java)、[ModelSettingsScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelSettingsScreen.java)、[AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java) |
| 服务器实例内来源选择与轮盘记忆 | [PlayerInteractionPolicy](../client/src/main/java/com/simmc/meplayeractions/client/PlayerInteractionPolicy.java)、[WheelPreferences](../client/src/main/java/com/simmc/meplayeractions/client/WheelPreferences.java) |
| 独立客户端与服务器 A/B 验收 | [LocalAppearanceHarness](../client/src/main/java/com/simmc/meplayeractions/client/render/LocalAppearanceHarness.java)、[E2EHarness](../client/src/main/java/com/simmc/meplayeractions/client/render/E2EHarness.java) |
| 发布包检查 | [package_release.py](../tools/package_release.py) |

源码入口和验收代码描述实现与检查方法；本索引不代替具体发布包中的验证结果。

0.4.3 采用 `interactions` 发布验证范围：新客户端定向单测及交互实机报告为本轮证据，0.4.2 服务端历史测试仅在源码、资源及 JAR 行为字节一致证明通过后复用。具体边界与打包命令见 [架构](../ARCHITECTURE.md#10-构建测试与发布边界)，不将历史完整检查数计为本轮重跑。
