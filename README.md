# MEPlayerActions

玩家模型渲染、中文动作界面和姿态动画同步，包含 **Fabric 1.21.11 客户端模组**与 **Paper 1.21.11 服务端插件**。当前说明对应 **0.4.1**。作者：SIMMC、Loliiiico。

**客户端默认开启服务器伪装的本地渲染与即时动画。** 服务器授予模型绑定、完整资源包可用并完成接管后，客户端按原版玩家实体即时生成普通动作，保留服务器授权的手动动作。服务器继续管理模型身份、权限、观看距离和人数；未安装模组的观看者继续使用服务器模型后端。被观看者无需安装客户端模组。

**只有“私人本地外观”需要主动选择并启用。** 它让自己在本机看到所选模型，不改变其他玩家看到的外观。私人外观初始未启用，不影响上面默认开启的服务器伪装接管。

## 文档导航

| 要查什么 | 文档 |
| --- | --- |
| 全部说明与源码入口 | [文档索引](docs/README.md) |
| 客户端默认值、显示优先级、命令、排查 | [客户端配置](docs/CLIENT_CONFIG.md) |
| 服务器安装包合并命令与统一资源包格式 | [客户端资源包](docs/CLIENT_RESOURCE_PACK.md) |
| 模块、生命周期、构建与验收边界 | [架构](ARCHITECTURE.md) |
| 多人同步与服务端适配接口 | [客户端协议 v3](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md) |
| 参考默认模型的作者与许可 | [第三方说明](THIRD_PARTY_NOTICES.md) |

## 两类本地模式与基本默认配置

| 模式 | 初始状态 | 模型由谁选择 | 谁能看到 | 条件 |
| --- | --- | --- | --- | --- |
| 服务器伪装的本地渲染接管 | **开启**：`enabled=true`；即时动画：`followServerTimeline=false` | 服务器命令与权限 | 本机观看者看到服务器授权的自己及其他玩家 | 支持 v3 的服务器、匹配完整资源包、有效接管 |
| 私人本地外观 | **未启用**：`localAppearance.enabled=false` | 本机图库或客户端命令 | 只有自己看到自己的选择 | 客户端即可；单人和无插件服务器也可用 |

初始 `showSelf=true`，第三人称显示本人自定义模型；第一人称不绘制自己的完整身体。私人模型初始选择为 `openysm_default`，缩放 `1`，XYZ 偏移均为 `0`。`interpolationTicks=2` 用于主动启用的服务器拖后轨迹；默认即时模式没有额外位置缓冲。完整字段见 [配置默认值](docs/CLIENT_CONFIG.md#完整默认配置)。

这些是新安装、没有保存设置时的默认值。升级会读取已有 `config/meplayeractions-client.json`，保留用户已保存的有效选择。`/mpaclient toggle` 切换全部客户端模型渲染；“关闭本地外观”或 `/mpaclient local off` 只关闭私人选择，服务器伪装仍可由客户端接管。

## 安装

客户端需要 Java 21、Minecraft 1.21.11、Fabric Loader 0.18.4 或以上，以及 Fabric API 0.140.2 或以上。将客户端 JAR 放入该游戏实例的 `mods/` 后重启。只使用私人模型时无需服务器插件、ModelEngine 或服务器资源包。

服务器多人伪装的当前后端为 **ModelEngine R4.1.1**，服务端需要 Paper 1.21.11、Java 21：

1. 在 [Releases](https://github.com/uwuhhhj/MEPlayerActions/releases/latest) 下载同一版本的服务端安装 ZIP。
2. 停服，将安装 ZIP 的 `plugins/` 合并到服务器，移除旧插件 JAR。使用包内新配置，并把自定义设置迁入其中。旧的 `_npc`、`_player` 示例蓝图可移除。
3. 启动服务器，执行 `/meg reload models`，再用安装包内 `tools/build_client_resource_pack.py` 合并当前服务器完整引擎包与客户端原模型。按 [安装包目录下的合并命令](docs/CLIENT_RESOURCE_PACK.md#从服务器安装-zip-合并) 操作，将最终 ZIP 作为服务器原版资源包下发。

安装包包含 ModelEngine 用的数值蓝图和客户端用的表达式原模型，分别位于 `plugins/ModelEngine/blueprints/meplayeractions/` 与 `plugins/MEPlayerActions/models/`。ModelEngine、GSit 需另行准备。发布附包是两套服务器模型的完整示例；生产服务器存在其他模型时，用自身完整引擎包合并，保留额外资源。模型更新后重新合并，并更新服务器资源包 URL/哈希。构建无需修改 ModelEngine 源码。

真实坐下、爬行指令需要 [GSit](https://github.com/Gecolay/GSit)，已验证 3.5.1。原生爬行、床睡眠和载具的动画同步无需 GSit。

## 客户端操作

| 默认按键 | 操作 |
| --- | --- |
| **Y** | 模型图库：搜索、收藏、显示 ID、来源筛选、分页、可旋转真实 3D 预览及缩略图 |
| **G** | 动作轮盘：悬停后松键选择，或点击、数字 1–8；锁定只让界面保持打开 |
| **N** | 动作列表：区分私人本地动作与服务器动作，提供渲染总开关、第三人称本人显示 |
| **F5** | 切换视角，第三人称查看完整模型 |

按键可在 Minecraft 按键绑定中调整。图库点击卡片只浏览；点击“使用模型”才启用私人选择。设置页提供缩放、XYZ 对齐、保存、关闭与恢复默认。“保存并预览”会开启私人外观和本人显示，并在世界中切到第三人称。

客户端仅内置参考 OpenYSM 的 **CC0 默认模型**，保留作者及许可说明，可切换默认/蓝色皮肤和红色蝴蝶结头饰。服务器 `ysm_01_jk`、`ysm_02_jk` 不内置在客户端 JAR，它们来自完整服务器资源包。

私人模型可放入 `config/meplayeractions/models/`：嵌入 PNG 的独立 `.bbmodel`，或包含 `ysm.json`、主骨架、主/extra 动画和 PNG 的普通 YSM 文件夹；在图库点击刷新选择。当前支持有界的 YSM 主模型与常规/extra 动画，不读取加密 `.ysm`。完整 OpenYSM 动态表单、外部模组接口、投射物、载具替换和声音留待后续。缩放范围 `0.05–8`，XYZ 各为 `-32–32` 方块，Y 正值向上。

私人外观与参数保存在 `config/meplayeractions-client.json`，重进世界或重启后恢复。启用时只覆盖本机看到的自己，其他玩家仍看到服务器决定的外观；关闭后回到当前服务器伪装或原版人物。在已有服务器伪装时，私人替换会等待服务器显示接管就绪。私人模式第一人称保留原版手臂与手持物，本地动作不发送服务器姿态指令、不改变真实位置或碰撞。

```text
/mpaclient settings
/mpaclient local model openysm_default
/mpaclient local play extra1
/mpaclient local stop
/mpaclient local off
/mpaclient status
```

本地文件 ID 为 `local:文件名.bbmodel`，YSM 文件夹 ID 为 `ysm:文件夹名`。完整客户端命令、重置范围和显示排查见 [客户端配置](docs/CLIENT_CONFIG.md)。旧的 `/mpaclient preview ysm_01_jk` / `preview off` 保留为临时预览；服务器示例需要完整资源包，且有本人服务器绑定时需先解除伪装。

## 服务器伪装与动作

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

伪装后自动同步移动、跳跃、潜行、游泳、陆地爬行、睡眠、船、矿车、骑乘、梯子、鞘翅、挥臂和挖掘。两套示例 `ysm_01_jk` / `ysm_02_jk` 保留原外貌与尺寸，客户端原模型包含 60 个 YSM 动作，ME 数值蓝图包含 57 个动作；条件动作及耳朵、发丝、飘带、尾巴物理按模型表达式计算。ModelEngine 路径有独立的实时表达式控制器。

客户端界面的服务器动作与真实坐下、爬行按钮需要服务器同步就绪及可用的本人服务器模型。命令支持 Tab 补全，`/meplayeractions help` 查看帮助。模型名必须紧跟 `disguise`，后面可填多个参数：

```text
/meplayeractions disguise ysm_02_jk scale=0.8 show-self=true view-distance=8 max-viewers=10 delay=2 effect=slowness:1
```

| 参数 | 服务端默认值与含义 |
| --- | --- |
| `scale` | `1.0`，在模型原始尺寸上缩放 |
| `hide-self` | `true`，隐藏原版人物 |
| `show-self` | `true`，是否向本人显示服务器伪装模型 |
| `view-distance` | `8`，其他玩家距离必须小于此值才可观看 |
| `max-viewers` | `10`，最多其他观看者人数 |
| `delay` | `2` tick，ME 视觉轨迹延迟；客户端默认即时跟随 |
| `effect` | 默认无，只允许缓慢，例如 `slowness:1` |

常用子命令：`menu` 打开菜单；`animations` 查看动作；`play <动作> [速度] [ONCE|LOOP|HOLD]` 播放动作；`stop` 停止手动动作；`reset` 清理本插件姿态和药水；`pose sit`、`pose crawl` 使用真实姿态；`sync` 调整同步项目；`status` 查看诊断；`reload` 重载配置。

`play extra0` 切换 `ysm_02_jk` 的花朵与帽子；`ysm_01_jk` 保留原外貌，没有这两组方块。专用动作可从菜单选择。`play` 展示动画，真实姿态使用 `pose`。服务端配置位于 `plugins/MEPlayerActions/config.yml`，可调整采样、切换间隔、过渡、跳跃收尾、同步许可及各模型映射；修改后执行 `reload` 并重新伪装。

## 适配与资源边界

客户端没有 ModelEngine、GSit 或服务端插件的硬依赖。多人同步需要服务端实现 `meplayeractions:main` 的稳定 v3 协议；其他模型引擎需要服务端适配器，实现相同资产身份、状态、权限和接管约定。客户端不会自动识别未知引擎或任意模型格式。

OpenYSM 原项目使用自己的分发协议和缓存。本项目使用统一 Minecraft 资源包，两者网络协议不互通；模型文件夹仅在当前支持范围内解释。26.x 需要另行适配验证，完整 OpenYSM 功能对齐留待后续。

资源包内容可以被获得该包的玩家提取。0.4.1 客户端被动读取已安装资源包，不发送 `asset_request`；服务端为旧客户端保留受限兼容传输，并执行连接级握手、入包、带宽和并发限制。模型缺失、哈希不一致或渲染失败时保持服务器后端显示；资源包恢复后重新校验并接管。机制见 [架构](ARCHITECTURE.md)、[协议](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md)；表达式参考 [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)。
