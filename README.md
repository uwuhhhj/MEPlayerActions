# MEPlayerActions

玩家模型渲染、中文动作面板和姿态动画同步，包含 **Fabric 1.21.11 客户端模组**与 **Paper 1.21.11 服务端插件**。客户端运行需要 Java 21、Fabric Loader 和 Fabric API；服务端插件的当前模型后端为 **ModelEngine R4.1.1**。作者：SIMMC、Loliiiico。

服务器多人伪装模式由服务器管理模型、动作权限、观看距离和人数。安装模组的观看者自动使用本地模型渲染，可流畅显示自己和其他玩家；被观看者无需安装模组。未安装模组的观看者使用 ModelEngine 资源包。第一人称隐藏自己的完整模型，第三人称默认显示。

客户端没有 ModelEngine、GSit 或服务端插件的硬依赖。多人同步需要服务器实现 `meplayeractions:main` 的 v3 协议；其他模型引擎实现相同资产、状态和渲染接管约定后，可以使用同一客户端。客户端按协议解释模型和动作，不会自动识别未适配的引擎或任意模型格式。

支持移动、跳跃、潜行、游泳、陆地爬行、睡眠、船、矿车、骑乘、梯子、鞘翅、挥臂和挖掘。两套示例模型为 `ysm_01_jk` 和 `ysm_02_jk`，保留各自外貌与原始尺寸；条件动作、耳朵、发丝、飘带及尾巴物理按模型表达式计算。ModelEngine 路径也有独立的实时表达式控制器。

## 安装

客户端使用 Minecraft 1.21.11、Fabric Loader 0.18.4 或以上、Fabric API 0.140.2 或以上，将客户端 JAR 放入该实例的 `mods/` 后重启。只使用本地自己的外观时，安装客户端即可，无需服务器插件或 ModelEngine 资源包。

需要服务器多人伪装时，再安装服务端部分：

1. 在 [Releases](https://github.com/uwuhhhj/MEPlayerActions/releases/latest) 下载同一版本的服务端安装 ZIP。
2. 停服，将安装 ZIP 的 `plugins/` 合并到服务器，移除旧插件 JAR。使用包内新配置，并把自定义设置迁入其中。旧的 `_npc`、`_player` 示例蓝图可移除。
3. 启动服务器，执行 `/meg reload models`，为玩家更新 ModelEngine 生成的资源包。

安装包包含 ModelEngine 用的数值蓝图和客户端用的表达式原模型，分别位于 `plugins/ModelEngine/blueprints/meplayeractions/` 与 `plugins/MEPlayerActions/models/`。ModelEngine、GSit 和生成后的资源包需另行准备。

真实坐下、爬行指令需要 [GSit](https://github.com/Gecolay/GSit)，已验证 3.5.1。原生爬行、床睡眠和载具的动画同步无需 GSit。

## 使用

按 **N** 打开动作面板，进入“本地外观设置”；也可使用 `/mpaclient settings` 或 `/mpaclient local`。选择内置 `ysm_01_jk` / `ysm_02_jk`，或把符合客户端格式要求的单个 `.bbmodel` 放入该实例的 `config/meplayeractions/models/` 后选择。缩放范围为 `0.05–8`，世界 X/Y/Z 偏移各为 `-32–32` 方块，Y 正值向上；点击“保存并预览”后在第三人称查看。

本地外观默认关闭，模型、缩放、偏移及启用状态保存在 `config/meplayeractions-client.json`，重进世界或重启后恢复。它只改变本机看到的自己：单人世界、无插件服务器均可使用，其他玩家仍看到服务器决定的外观。启用时优先显示这份本地外观；关闭后显示当前服务器伪装或原版人物。本地动作预览只改变动画，不发送服务器姿态命令，也不修改真实位置、碰撞和能力。

也可用 `/mpaclient local model ysm_02_jk` 选择并启用模型，`/mpaclient local play wave` 预览动作，`local stop` 停止动作，`local off` 关闭本地外观，`local reset` 恢复默认。本地文件的模型 ID 为 `local:文件名.bbmodel`。

| 模式 | 模型由谁选择 | 谁能看见 | 是否需要服务器协议 |
| --- | --- | --- | --- |
| 本地自己的外观 | 本机设置 | 只有自己 | 无需 |
| 服务器多人伪装 | 服务器命令与权限 | 服务器允许的观众 | 安装模组的观看者需要 v3；当前未装模组者使用 ME |

服务器多人伪装使用以下指令：

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

伪装后自动同步状态。客户端面板中的服务器动作与真实坐下、爬行按钮只在服务器同步就绪后使用。按 **F5** 切换视角；指令支持 Tab 补全，输入 `/meplayeractions help` 查看帮助。

模型名必须紧跟 `disguise`，后面可填写多个参数：

```text
/meplayeractions disguise ysm_02_jk scale=0.8 show-self=true view-distance=8 max-viewers=10 delay=2 effect=slowness:1
```

| 参数 | 默认值与含义 |
| --- | --- |
| `scale` | `1.0`，在模型原始尺寸上缩放 |
| `hide-self` | `true`，隐藏原版人物 |
| `show-self` | `true`，是否显示自己的伪装模型 |
| `view-distance` | `8`，其他玩家距离必须小于此值才可观看 |
| `max-viewers` | `10`，最多其他观看者人数 |
| `delay` | `2` tick，ModelEngine 视觉轨迹延迟；客户端默认即时跟随 |
| `effect` | 默认无，只允许缓慢，例如 `slowness:1` |

常用子命令：`menu` 打开菜单；`animations` 查看动画；`play <动作> [速度] [ONCE|LOOP|HOLD]` 播放动作；`stop` 停止手动动作；`reset` 清理本插件姿态和药水；`pose sit`、`pose crawl` 使用真实姿态；`sync` 调整同步项目；`status` 查看诊断；`reload` 重载配置。

`play extra0` 切换 `ysm_02_jk` 的花朵与帽子；`ysm_01_jk` 保留原外貌，未包含这两组方块。其他专用动作可从菜单选择。`play` 展示动画，真实姿态使用 `pose`。

客户端 `/mpaclient toggle` 是客户端渲染总开关，涵盖本地外观和服务器模型接管，关闭后恢复服务器后端或原版人物。重新启用本地外观会打开总开关；“关闭本地外观”只关闭自己的本地选择。旧的 `/mpaclient preview ysm_01_jk` / `preview off` 保留为临时示例预览；持久自己的外观使用“本地外观”设置。

配置在 `plugins/MEPlayerActions/config.yml`，可调整采样间隔、动画切换间隔、过渡、跳跃收尾、同步许可与各模型动画映射。修改后执行 `reload` 并重新伪装。

本轮提供基础独立客户端设置，以及这两套模型的 YSM 表达式与动作；完整 OpenYSM 界面和功能对齐留待后续迭代，当前不包含其全部外部模组接口或粒子系统。26.x 需要适配验证。开发资料见 [架构](ARCHITECTURE.md) 和 [客户端协议](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md)；表达式和动作语义参考 [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)。
