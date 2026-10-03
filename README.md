# MEPlayerActions

为 **Paper 1.21.11 + ModelEngine R4.1.1** 提供玩家模型伪装、动作菜单和姿态动画同步，需要 Java 21。配套 Fabric 客户端可本地播放骨骼动画、平滑显示模型，并使用中文动作面板。

可直接通过指令设置模型缩放、本人可见性、观看距离、观看人数、视觉延迟和缓慢药水。模型与资源包由 ModelEngine 管理。作者：SIMMC、Loliiiico。

自动同步移动、跳跃、潜行、游泳、陆地爬行、坐下、睡眠、载具、飞行、挥臂及挖掘。安装客户端的观众在模型加载成功后使用本地渲染，其余观众使用 ModelEngine；观看距离与人数仍由服务器控制。

原生床睡眠与 GSit 躺下分别识别。示例的 `bed_sleep` 为横卧动作，原有 `sleep` 卷曲睡眠仍可通过菜单或 `play` 使用。

## 安装

1. 准备 Paper 1.21.11、Java 21 和 ModelEngine R4.1.1。使用真实坐下、爬行指令时，还需安装 [GSit](https://github.com/Gecolay/GSit)（本发布验收版本为 3.5.1）；原生爬行、睡眠及载具动画同步无需 GSit。
2. 从 [Releases](https://github.com/uwuhhhj/MEPlayerActions/releases/latest) 下载 `MEPlayerActions-版本-install.zip`，停服后将包内 `plugins/` 合并到服务器目录。更新时移除旧版插件 JAR，并将旧配置中的自定义设置迁入包内新配置；覆盖自定义模型前先备份。
3. 启动服务器，执行 `/meg reload models`，让玩家加载 ModelEngine 生成的新资源包。安装包提供 `ysm_01_jk_player` 和 `ysm_01_jk_npc` 两个示例模型。

### 客户端安装

1. 使用 Minecraft **1.21.11 Fabric**，安装 Fabric API（最低 0.140.2）。
2. 将 Releases 中的 `MEPlayerActions-Client-版本.jar` 放入该游戏实例的 `mods/`，重新启动游戏。服务器插件与客户端均使用 0.3.1 或同一发布版本。
3. 进入服务器并伪装后，按 **N** 打开中文动作面板。默认直接跟随客户端实体的位置、朝向和普通动作；自己的模型和其他已伪装玩家的模型均由观看者本地绘制，被观看者无需安装模组。第一人称自动隐藏自己的完整模型，第三人称默认可见。关闭本地渲染会恢复 ModelEngine。

客户端会自动下载服务器模型并缓存。资源重载或渲染失败时恢复 ModelEngine，模型再次准备好后重新接管。当前目标版本为 1.21.11；26.x 需适配并验证对应版本的加载与渲染接口。

## 快速使用

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk_npc
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

依次查看模型、进行伪装、打开动作菜单、播放挥手、解除伪装。伪装后自动同步玩家状态，也可在菜单中点击动作。`play` 仅展示动画，真实坐下或爬行请使用 `pose`。指令支持 Tab 补全；输入 `/meplayeractions help` 查看帮助。

客户端还可使用 `/mpaclient` 打开面板、`/mpaclient status` 查看连接状态。解除服务器伪装后，在世界内执行 `/mpaclient preview ysm_01_jk_npc` 可本地预览示例模型，切换第三人称观察；使用 `/mpaclient preview off` 结束。预览只影响自己的客户端。

### 伪装参数

**模型名必须在 `disguise` 后面，多个参数放在模型名后面。** 例如：

```text
/meplayeractions disguise ysm_01_jk_npc scale=1.5 show-self=false view-distance=8 max-viewers=10 delay=3 effect=slowness:1
```

| 参数 | 默认值 | 用途 |
| --- | --- | --- |
| `scale` | `1.0` | 模型缩放，范围 0.05–8 |
| `hide-self` | `true` | 是否隐藏原版玩家人物 |
| `show-self` | `true` | 是否向本人显示模型；设为 `false` 可避免第一人称遮挡，同时也会在本人第三人称中隐藏 |
| `view-distance` | `8` | 其他玩家距离必须小于此值才可见，单位格，范围 0.1–256 |
| `max-viewers` | `10` | 最多向最近的 N 名其他玩家显示，本人不占名额；范围 0–1000，0 表示其他人不可见 |
| `delay` | `2` | ModelEngine 视觉延迟，单位 tick，范围 0–20；客户端默认即时跟随，不受此值影响 |
| `effect` | 无 | 仅支持缓慢：`slowness:1` 为缓慢 I，`slowness:2:60` 为缓慢 II、持续最多 60 秒 |

省略参数时使用服务器配置默认值，参数只对本次伪装生效。也支持 `--scale=1.5` 或 `--scale 1.5`。缓慢药水需要 `mact.disguise.effects` 权限，默认 OP 可用；解除伪装或执行 `reset` 会清理本次药水。

视觉延迟只影响模型显示，玩家真实位置和碰撞照常。20 TPS 下 2 tick 约 100 ms，可用 `delay=3` 或 `delay=4` 增加拖后感。

客户端默认使用原版实体的帧间平滑，不额外等待服务器坐标包。若需要拖后感，可在 **N** 面板开启“服务器拖后轨迹”，再调整“拖后缓冲”；该模式同时使用服务器延迟轨迹与动画。床和 GSit 的特殊锚点、模型映射、同步开关及手动动作仍由服务器提供。其他玩家仍受正常网络传输延迟影响。

### 常用命令

以下子命令均放在 `/meplayeractions` 后面：

| 子命令 | 用途 |
| --- | --- |
| `disguise <模型名> [参数...]` / `undisguise` | 伪装 / 解除伪装 |
| `models` / `animations` | 查看可用模型 / 当前模型动画 |
| `menu` | 打开中文动作菜单 |
| `play <动作名> [速度] [ONCE\|LOOP\|HOLD]` | 播放一次、循环或保持末帧，例如 `play sit 1.0 HOLD` |
| `stop` / `reset` | 停止手动动画 / 清理本插件创建的姿态、飞行、动作和药水 |
| `pose sit` / `pose crawl` | 通过 GSit 真实坐下 / 爬行 |
| `pose fly [on\|off]` | 开关真实飞行，需要管理员启用并授予权限 |
| `sync` / `sync <类型> <on\|off\|default>` | 查看或调整自己的动画同步，例如 `sync crawl off`，用 `sync crawl default` 恢复默认 |
| `attach [模型名]` | 接管已有的 ModelEngine 状态机伪装 |
| `status` / `reload` | 查看诊断状态 / 重载配置，默认 OP 可用 |

同步类型：`movement sprint jump sit sleep ride crawl sneak swim flight elytra swing mining`。玩家设置会保存；服务器关闭的同步项不能由玩家开启。

### 修改默认配置

配置位于 `plugins/MEPlayerActions/config.yml`。修改后执行 `/meplayeractions reload`，再重新伪装。常用设置：

| 配置 | 用途 |
| --- | --- |
| `disguise.*` | 缩放、人物隐藏、本人可见性、观看距离及人数的默认值 |
| `controller.sample-interval-ticks` / `controller.animation-update-interval-ticks` | 采样 / 动画切换检查间隔，默认均为 1 tick |
| `controller.transition-in-ticks` / `controller.transition-out-ticks` | 动画进入 / 退出的过渡时间，默认均为 2 tick |
| `visual-follow.delay-ticks` | 默认视觉延迟，默认 2 tick |
| `jump.minimum-play-ticks` / `jump.landing-grace-ticks` | 跳跃最短播放时间 / 落地收尾上限，默认 0（按动画计算）/ 6 tick |
| `models.allowed` / `animations.models` | 可用模型白名单 / 各模型的状态动画映射 |
| `custom-actions` / `menu.animation-labels` | 自定义动作 / 菜单动画中文名称 |

真实飞行需设置 `gameplay.allow-flight-command: true` 并授予 `mact.flight`。普通玩家默认可使用伪装、动作、坐下、爬行和同步；缓慢药水、飞行、诊断、重载分别需要 `mact.disguise.effects`、`mact.flight`、`mact.debug`、`mact.admin`，默认仅 OP 拥有。

接管 `/meg disguise` 时，需在 ModelEngine 配置中启用 `Model-Engine.Use-State-Machine`，重载后重新伪装，再执行 `attach`。

自定义客户端模型推荐使用与示例相同的 Blockbench 4.10 格式、内嵌 PNG、cube 类型 `.bbmodel`，支持 linear、step、catmullrom 关键帧；外部贴图、mesh、脚本及 5.0 分离骨骼表暂不支持，无法本地加载时继续使用 ModelEngine。更新模型后重载插件与 ME，并重新伪装。
