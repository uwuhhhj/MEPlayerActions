# MEPlayerActions

Paper 玩家伪装与动画插件，配套可选的 Fabric 客户端模组。当前服务端与客户端均为 **0.6.1**，目标版本 **Minecraft 1.21.11／Java 21**。作者：SIMMC、Loliiiico。

同一个客户端提供三条独立路径。模型上传到服务器不等于创建服务器伪装：

| 路径 | 模型与渲染 | 其他人看到什么 |
| --- | --- | --- |
| 本地私人外观 | 本地选择，仅本人客户端渲染，不上传 | 原版玩家 |
| 私人模型多人分享 | 有权限的玩家主动上传完整 YSM／BBModel 资源，服务器授权分发，各客户端渲染 | 获准的模组观看者看到私人模型；原版观看者看到原版玩家 |
| 服务器伪装 | 服务器管理伪装、绑定和动作；模组观看者在本机接管 | 模组观看者看到本地动画，其他观看者通过 ModelEngine 与资源包看到服务器伪装 |

## 快速安装

| 用途 | 必需组件 |
| --- | --- |
| 服务器玩家伪装 | Paper 1.21.11、Java 21、ModelEngine R4.1.1、对应服务器资源包 |
| 私人模型多人分享中继 | Paper 1.21.11、Java 21、MPA 插件；无需 ModelEngine 或服务器资源包 |
| 客户端本地模型／服务器伪装接管 | Minecraft 1.21.11、Java 21、Fabric Loader ≥ 0.18.4、Fabric API ≥ 0.140.2 |

1. 关闭游戏或服务器，替换对应安装包内的 JAR：客户端放入 `mods/`，服务端放入 `plugins/`，避免同时加载两个版本。升级保留已有配置、私人模型和自定义蓝图。
2. 服务器伪装首次安装时，将安装包的 `plugins/ModelEngine/blueprints/meplayeractions/` 一并部署。需要客户端接管的模型，由管理员将完整原文件明确放入 `plugins/MEPlayerActions/models/`；两套示例可从安装包的 `examples/models/` 复制。
3. 执行 `/meg reload models` 加载 ME 模型，并沿用原有 ModelEngine／CraftEngine 的资源包生成、合并与下发流程。MPA 只推送管理员明确发布的原模型，不自动下载 ME 蓝图或内置资源；没有 MPA 原文件时仍可服务器伪装。不需要额外合并工具或 MPA 资源包索引。
4. 仅私人分享时，启用 `client-sync.private-models.enabled`，授予发布者 `mact.private.upload`、观看者 `mact.private.view`；发布者再主动开启分享。开关与两项权限默认关闭，OP 也需显式授权。私人资源使用独立有界缓存，不放进 ME 蓝图目录。

真实坐下／爬行指令另需可选的 [GSit](https://github.com/Gecolay/GSit)；原生爬行、床睡眠和载具动画无需 GSit。自定义模型来源、服务器配置和部署排查见 [模型同步与部署](docs/MODEL_DELIVERY.md)。

## 基本使用

服务器指令支持 Tab 补全，`/meplayeractions help` 查看完整帮助：

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

伪装后自动响应普通移动、姿态和交互。服务器决定模型身份、观看权限、距离和人数；客户端接管失败时保留或恢复服务器显示。

管理员使用 `/meplayeractions status` 查看全服资源与保护状态，`status network`、`status tasks`、`status models`、`status protection` 查看分类指标，`status player <玩家名>` 查看单个在线玩家。上传、下载和后台任务共享有界预算；服务器负载升高时延迟非关键工作、暂停新增模型，已有显示与安全清理按保护等级处理。默认值、错误提示与调优见 [服务器资源保护](docs/SERVER_RESOURCE_PROTECTION.md)。

客户端默认 **J** 打开动作轮盘，右上齿轮进入玩家模型主页；也可执行 `/mpaclient settings`。主页顶部的“客户端／服务端”共用 5×2 图库；服务端“使用模型”交给服务器执行伪装命令。服务器伪装与私人模型互斥，须先解除服务器伪装才能使用私人模型。服务器伪装接管默认开启，私人模型初始未启用。关闭客户端渲染仍可使用有效的 SERVER 动作轮盘。

私人模型放入当前实例的 `config/meplayeractions/models/`，支持当前范围内的 `.ysm`、ZIP、YSM 文件夹和自包含 `.bbmodel`。在图库刷新并点击卡片预览，“使用模型”仅本机显示；云朵“上传分享”才使用并上传，收到服务器确认后显示已上传。按 **F5** 查看第三人称。导入、显隐、来源、分享与排查见 [客户端配置](docs/CLIENT_CONFIG.md)。

客户端没有 ModelEngine 或服务器插件的硬依赖；单人和无插件服务器仍可使用私人模型。其他服务器模型引擎需适配相同协议。YSM 行为参考并适配 OpenYSM 等项目，具体支持范围见 [YSM 兼容说明](docs/YSM_COMPATIBILITY.md)，来源与资源许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。

## 文档入口

| 内容 | 文档 |
| --- | --- |
| 全部文档导航 | [文档索引](docs/README.md) |
| 客户端配置、操作与排查 | [客户端配置](docs/CLIENT_CONFIG.md) |
| 服务器部署、原模型来源与缓存 | [模型同步与部署](docs/MODEL_DELIVERY.md) |
| 后台频率、增量同步与性能边界 | [性能配置](docs/PERFORMANCE.md) |
| 资源预算、自动保护与全局状态 | [服务器资源保护](docs/SERVER_RESOURCE_PROTECTION.md) |
| 模型格式、动画与组件支持 | [YSM 兼容说明](docs/YSM_COMPATIBILITY.md) |
| 模块与生命周期 | [架构](ARCHITECTURE.md) |
| 源码索引、构建与交付 | [开发指南](CONTRIBUTING.md) |
| 服务器适配协议 | [客户端协议](docs/CLIENT_PROTOCOL.md) |
| 版本变更 | [更新日志](docs/history/README.md) |
