# MEPlayerActions 0.4.1 文档索引

本索引对应 0.4.1 现行代码。**服务器伪装的客户端渲染和即时动画默认开启；私人本地模型初始未启用。** 两者共用渲染总开关，但私人选择只影响自己在本机看到的外观。

## 用户与管理员

| 需求 | 入口 |
| --- | --- |
| 安装、默认行为、按键、服务器指令 | [项目说明](../README.md) |
| 客户端全部默认值、私人/服务器显示优先级、命令与排查 | [客户端配置](CLIENT_CONFIG.md) |
| 安装 ZIP 解压后的资源包合并命令、原始模型与共享贴图格式 | [客户端统一资源包](CLIENT_RESOURCE_PACK.md) |
| 默认模型来源、作者与 CC0 许可 | [第三方说明](../THIRD_PARTY_NOTICES.md) |

## 开发与适配

| 需求 | 入口 |
| --- | --- |
| 系统结构、接管/恢复生命周期、构建和验收入口 | [架构](../ARCHITECTURE.md) |
| v3 消息、资产身份、权限、资源包模式、旧客户端兼容与限流 | [客户端协议](../src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md) |

未知模型引擎需要服务端适配器，客户端不自动识别引擎。OpenYSM 文件夹基础解析、统一 Minecraft 资源包和 OpenYSM 原网络协议是不同边界；完整 OpenYSM 功能对齐留待后续。

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
| 内置默认、本地 BBModel、YSM 文件夹 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java)、[YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java) |
| 资源包读取与原始 SHA-256 校验 | [PackModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/PackModelLibrary.java) |
| 模型图库、实际参数、动作列表与轮盘 | [LocalAppearanceScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/LocalAppearanceScreen.java)、[ModelSettingsScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelSettingsScreen.java)、[ActionsScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ActionsScreen.java)、[AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java) |
| 独立客户端与服务器 A/B 验收 | [LocalAppearanceHarness](../client/src/main/java/com/simmc/meplayeractions/client/render/LocalAppearanceHarness.java)、[E2EHarness](../client/src/main/java/com/simmc/meplayeractions/client/render/E2EHarness.java) |
| 打包检查与资源包构建 | [package_release.py](../tools/package_release.py)、[build_client_resource_pack.py](../tools/build_client_resource_pack.py) |

源码入口和验收代码描述实现与检查方法；本索引不代替具体发布包中的验证结果。
