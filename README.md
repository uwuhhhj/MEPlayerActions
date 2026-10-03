# MEPlayerActions

面向 Paper／ModelEngine 的玩家伪装与动画系统，包含 **Paper 1.21.11 服务端插件**与可选的 **Fabric 1.21.11 客户端模组**。**核心特点是：服务器发布的 YSM 风格玩家伪装，安装和未安装客户端模组的观看者都能看到。** 未安装模组的观看者通过 ModelEngine 和服务器资源包显示；安装模组的观看者可接收完整原始 `.bbmodel`，在本机计算动画并接管显示。当前说明对应 **0.4.3**。作者：SIMMC、Loliiiico。

**客户端默认开启服务器伪装的本地渲染与即时动画。** 服务器主动同步授权模型，客户端完成资产校验、纹理准备与显示接管后，按原版玩家实体即时生成普通动作，保留服务器授权的手动动作。服务器继续管理模型身份、权限、观看距离和人数；未安装模组的观看者继续使用服务器模型后端。被观看者无需安装客户端模组。

**0.4.3 的默认模型传输不需要客户端资源包索引或离线合并。** MPA 向被授权的观看者推送完整原模型；ModelEngine 导入数值蓝图、生成原版资源，CraftEngine 等现有流程继续合并、保护和下发服务器资源包。

**只有“私人本地外观”需要主动选择并启用。** 它让自己在本机看到所选模型，不改变其他玩家看到的外观。私人外观初始未启用，不影响上面默认开启的服务器伪装接管。

## 项目定位与 OpenYSM 的关系

玩家自行安装 YSM 模型作为本地“皮肤”、在本机显示自己的模型，属于 [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated) 原有能力。本项目的 **CLIENT 私人模型**路线延续这一用途，参考、移植或适配上游的相关模型语义、界面代码与资源，来源见 [第三方说明](THIRD_PARTY_NOTICES.md)。

本项目的 **SERVER 服务器伪装**路线由服务器发布 `.bbmodel`，同时支持两种观看者显示方式：

| 模型来源 | 显示路径 | 谁能看到 |
| --- | --- | --- |
| 玩家自行安装的私人 YSM 模型 | 本机加载，作为 CLIENT 私人外观 | 只有自己在本机看到自己的选择 |
| 服务器 `.bbmodel` 的 ME 显示 | ModelEngine 使用蓝图生成资源，服务器下发资源包并显示伪装 | 未安装模组或尚未完成客户端接管的授权观看者 |
| 服务器完整原始 `.bbmodel` 的客户端显示 | MPA 插件主动下发原模型，客户端校验并接管自己及其他伪装玩家的渲染 | 安装客户端模组且完成接管的授权观看者 |

同一个伪装玩家可以同时被两类观看者看到，**被伪装者本人无需安装客户端模组**。模型身份、观看权限、距离与人数仍由服务器决定；客户端接管失败时保留或恢复 ME 显示。两条显示路径的动画与效果能力并不保证完全相同。服务器完整原模型与 ME 数值蓝图／资源包的用途区别见 [模型同步与部署](docs/MODEL_DELIVERY.md)。

**当前格式支持与这个定位分开说明：** CLIENT 已支持普通 YSM `spec:2` 文件夹及本地 `.bbmodel`；加密 `.ysm` 文件加载尚未实现。后续补充私人 `.ysm` 加载时优先复用并适配上游相关代码，保持私人选择只影响本机，不需要因此引入 OpenYSM 的多人分发协议。支持字段及限制见 [YSM 兼容范围](docs/YSM_COMPATIBILITY.md)。

## 文档导航

| 要查什么 | 文档 |
| --- | --- |
| 全部说明与源码入口 | [文档索引](docs/README.md) |
| 客户端默认值、显示优先级、命令、排查 | [客户端配置](docs/CLIENT_CONFIG.md) |
| 服务器模型来源、主动同步、缓存与原版资源包分工 | [模型同步与部署](docs/MODEL_DELIVERY.md) |
| YSM 动作、作者配置、组件及验证边界 | [YSM 兼容范围](docs/YSM_COMPATIBILITY.md) |
| 模块、生命周期、构建与验收边界 | [架构](ARCHITECTURE.md) |
| 多人同步与服务端适配接口 | [客户端协议 v3](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md) |
| 参考默认模型的作者与许可 | [第三方说明](THIRD_PARTY_NOTICES.md) |

## 两类本地模式与基本默认配置

| 模式 | 初始状态 | 模型由谁选择 | 谁能看到 | 条件 |
| --- | --- | --- | --- | --- |
| 服务器伪装的本地渲染接管 | **开启**：`enabled=true`；即时动画：`followServerTimeline=false` | 服务器命令与权限 | 本机观看者看到服务器授权的自己及其他玩家 | 支持主动推送的服务器、完整原模型、有效接管 |
| 私人本地外观 | **未启用**：`localAppearance.enabled=false` | 本机图库或客户端命令 | 只有自己看到自己的选择 | 客户端即可；单人和无插件服务器也可用 |

初始 `showSelf=true`，第三人称显示本人自定义模型；第一人称不绘制自己的完整身体。私人模型初始选择为 `openysm_default`，缩放 `1`，XYZ 偏移均为 `0`。`interpolationTicks=2` 用于主动启用的服务器拖后轨迹；默认即时模式没有额外位置缓冲。完整字段见 [配置默认值](docs/CLIENT_CONFIG.md#完整默认配置)。

这些是新安装、没有保存设置时的默认值。升级会读取已有 `config/meplayeractions-client.json`，保留用户已保存的有效选择。`/mpaclient toggle` 切换全部客户端模型渲染；在 CLIENT 来源中，“关闭本地外观”或 `/mpaclient local off` 只关闭私人选择，服务器伪装仍可由客户端接管。

## 安装

客户端需要 Java 21、Minecraft 1.21.11、Fabric Loader 0.18.4 或以上，以及 Fabric API 0.140.2 或以上。将客户端 JAR 放入该游戏实例的 `mods/` 后重启。只使用私人模型时无需服务器插件、ModelEngine 或服务器资源包。

服务器多人伪装的当前后端为 **ModelEngine R4.1.1**，服务端需要 Paper 1.21.11、Java 21：

1. 在 [Releases](https://github.com/uwuhhhj/MEPlayerActions/releases/latest) 下载同一版本的服务端安装 ZIP。
2. 停服，将安装 ZIP 的 `plugins/` 合并到服务器，移除旧插件 JAR。使用包内新配置，并把自定义设置迁入其中。旧的 `_npc`、`_player` 示例蓝图可移除。
3. 启动服务器，执行 `/meg reload models`，按原有 ModelEngine／CraftEngine 流程生成、合并并下发服务器资源包。安装同版客户端的观看者会接收 MPA 授权的完整原模型，不需要额外运行资源包合并工具。

安装包把 ME 数值蓝图放入 `plugins/ModelEngine/blueprints/meplayeractions/`。两套完整表达式原模型由服务端 JAR 内置提供，`examples/models/` 仅供参考，不需要再复制进 MPA 模型目录。`plugins/MEPlayerActions/models/` 是管理员可选的显式覆盖来源，其优先级高于 JAR；升级时核对旧覆盖文件，避免它们遮蔽新的内置模型。ModelEngine、GSit 需另行准备。生产服务器继续保留自身完整资源包中的其他模型与素材。模型来源、更新和接管条件见 [模型同步与部署](docs/MODEL_DELIVERY.md)。

真实坐下、爬行指令需要 [GSit](https://github.com/Gecolay/GSit)，已验证 3.5.1。原生爬行、床睡眠和载具的动画同步无需 GSit。

## 客户端操作

| 默认按键 | 操作 |
| --- | --- |
| **J** | 参考 OpenYSM 的动作轮盘：动态多边形扇区、作者分类，右侧路径／页码／返回及关联作者表单；右上角设置齿轮进入玩家模型主页 |
| **F5** | 切换视角，第三人称查看完整模型 |

J 是本模组唯一的默认快捷键，旧 G／Y／N 入口不再注册。新轮盘使用独立的按键绑定名称，旧 G 轮盘存档不会沿用；可在 Minecraft 控制设置中重新绑定。

设置齿轮或 `/mpaclient settings` 直接打开**玩家模型主页**，无需再次点击“选择模型”。主页顶部 **CLIENT／SERVER** 切换客户端私人模型和服务器下发模型；CLIENT 页直接提供三维预览、搜索、收藏、分页及模型卡片，按内置、YSM 文件夹和 BBModel 来源分组。YSM 左侧按当前正在使用的外观采样原版玩家姿态，使用库存预览尺度；卡片独立播放作者展示动画，作者背景／前景与固定卡片视角用于卡片。点击卡片只改变待选草稿，左侧保持当前外观；点击“使用模型”才保存并启用私人选择。私人 YSM 的身体与 GUI 保留作者初始比例，YSM GUI 使用原生离屏深度绘制；本轮已验证真实颜色／深度附件执行，并人工核对默认模型眼白与完整头部及酒狐模型画面。详情提供缩放、XYZ、皮肤与作者配置；“保存并预览”在世界中开启本人显示并切到第三人称。

轮盘按当前来源显示动作。CLIENT 的中心控制动作锁定，SERVER 的中心停止服务器手动动作；翻页、分类返回和作者配置保留在轮盘内。“选择后保持轮盘”在主页的轮盘选项中设置，与移动时保留本地动作分开。来源和两侧页码分别记忆，显示时按实际动作目录限制页数。

客户端内置参考 OpenYSM 的 **CC0 默认模型**，另提供原始酒狐、新春酒狐、宇航员酒狐三套可选模型；原作者资源按固定源码版本原样保留，运行时进行 Minecraft 接口适配。三套酒狐资产使用 **CC BY-NC-SA 4.0**，须保留署名与许可，仅用于非商用，改编后遵守相同许可；完整作者与许可见 [第三方说明](THIRD_PARTY_NOTICES.md#openysm-wine-fox-model-assets-cc-by-nc-sa-40)。初始及已有私人选择不被替换，`openysm_default` 仍为初始模型，可切换默认/蓝色皮肤和红色蝴蝶结头饰。服务器 `ysm_01_jk`、`ysm_02_jk` 不内置在客户端 JAR，默认由服务器主动同步。

私人模型可放入 `config/meplayeractions/models/`：嵌入 PNG 的独立 `.bbmodel`，或带 `ysm.json` 和作者资源的普通 YSM `spec:2` 文件夹；在图库点击刷新选择。已接入 main/extra/arm、第一人称手臂、控制器与时间线、有类型 Molang 和实例物理、原版物品／装备，以及私人载具和投射物组件。内置默认模型导入 115 个主动画 clip 和 9 个组件，保留八个作者 extra 入口。作者配置页支持 checkbox/range/radio、皮肤与按模型保存，均只影响本机私人外观。0.4.2 历史验收已在无插件服务器实测时间线触发、原版音效、本地 OGG 流式播放、粒子生成及按实例停止和重载释放；外部模组集成、加密 `.ysm`、网格绑定及 OpenYSM 原网络协议尚未实现。范围与验证边界见 [YSM 兼容范围](docs/YSM_COMPATIBILITY.md)。缩放范围 `0.05–8`，XYZ 各为 `-32–32` 方块，Y 正值向上。

私人外观、所选皮肤和作者表单变量按模型保存在 `config/meplayeractions-client.json`，重进世界或重启后恢复。启用时只覆盖本机看到的自己，其他玩家仍看到服务器决定的外观；关闭后回到当前服务器伪装或原版人物。每次新的本人服务器伪装实例默认使用 SERVER，暂停私人显示、本地编辑与动作，保留全部私人配置。可在主页明确切到 CLIENT，手动用私人模型覆盖本机的自己；选择在同一服务器实例内保留，切回 SERVER 恢复服务器显示。加载中或接管失败仍属于服务器伪装，手动 CLIENT 显示须等待该绑定就绪，避免叠在 ME 回退上。解除伪装后恢复原先保存的私人选择，不删除模型或配置。私人 YSM 有可用手臂组件时显示作者第一人称手臂，物品继续使用原版渲染路径；没有组件时回退。本地动作与作者参数不发送服务器姿态指令、不改变真实位置或碰撞。

已识别为服务器伪装的玩家，客户端不叠画原版盔甲、披风或鞘翅；本人手动选 CLIENT 覆盖时也遵守此规则，手中物品继续显示。没有服务器伪装的私人模型仍按可用定位骨骼显示原版装备。

```text
/mpaclient settings
/mpaclient local model openysm_default
/mpaclient local play extra1
/mpaclient local stop
/mpaclient local off
/mpaclient status
```

本地文件 ID 为 `local:文件名.bbmodel`，YSM 文件夹 ID 为 `ysm:文件夹名`。完整客户端命令、重置范围和显示排查见 [客户端配置](docs/CLIENT_CONFIG.md)。服务器示例通过服务器 `disguise` 自动同步；私人预览使用图库中内置或自行导入的本地模型。

## 服务器伪装与动作

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

伪装后自动同步移动、跳跃、潜行、游泳、陆地爬行、睡眠、船、矿车、骑乘、梯子、鞘翅、挥臂和挖掘。两套示例 `ysm_01_jk` / `ysm_02_jk` 保留原外貌与尺寸，客户端原模型包含 60 个 YSM 动作，ME 数值蓝图包含 57 个动作；条件动作及耳朵、发丝、飘带、尾巴物理按模型表达式计算。ModelEngine 路径有独立的实时表达式控制器。

SERVER 轮盘动作需要服务器同步就绪及可用的本人服务器模型。真实坐下、爬行使用服务器 `pose` 命令。命令支持 Tab 补全，`/meplayeractions help` 查看帮助。模型名必须紧跟 `disguise`，后面可填多个参数：

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

OpenYSM 原项目使用自己的分发协议和缓存。本项目使用 MPA v3 的授权模型推送，两者网络协议不互通；模型文件夹仅在当前支持范围内解释。26.x 需要另行适配验证，完整 OpenYSM 功能对齐留待后续。

推送的完整原模型包含贴图，可能与 ME／CE 资源包中的贴图重复；跨资源包复用尚未实现。服务器只同步当次可见、授权绑定对应的资产，客户端不能指定任意模型下载。被授权接收者仍能取得原模型，ME／CE 的资源包保护不加密这条模型推送。资产缺失、哈希不一致或渲染失败时保持服务器后端显示。

两端建议同版升级到 0.4.3。服务端保留有界的旧资源包／旧下载兼容路线；新客户端要求服务端协商主动推送，旧服务端不支持时保持服务器显示，不能仅凭同为协议 v3 推断兼容。[旧资源包格式](docs/CLIENT_RESOURCE_PACK.md) 仅供对应兼容模式使用。机制见 [架构](ARCHITECTURE.md)、[协议](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md)；表达式参考 [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)。

0.4.3 的发布验证使用 `interactions` 范围：重新验证本轮交互与装备显示变化，明确记录服务端字节一致证明复用的 0.4.2 基线。历史完整检查不计作本轮重跑结果，实际定向测试与实机数量见发布验证 JSON 和 tests ZIP.
