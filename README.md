# MEPlayerActions

为 **Paper 1.21.11 + ModelEngine R4.1.1** 提供玩家模型伪装、动作菜单和姿态动画同步，需要 Java 21。配套 Fabric 客户端可本地播放骨骼动画、平滑显示模型，并使用中文动作面板。

本文说明安装、使用和配置；开发者可继续阅读 [整体设计架构](ARCHITECTURE.md) 和 [客户端协议 v3](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md)。以下内容按当前工作区的 **0.3.2** 源码整理。

可直接通过指令设置模型缩放、本人可见性、观看距离、观看人数、视觉延迟和缓慢药水。模型与资源包由 ModelEngine 管理。作者：SIMMC、Loliiiico。

自动同步移动、跳跃、潜行、游泳、陆地爬行、坐下、睡眠、载具、飞行、挥臂及挖掘。安装客户端的观众在模型加载成功后使用本地渲染，其余观众使用 ModelEngine；观看距离与人数仍由服务器控制。

原生床睡眠与 GSit 躺下分别识别。示例的 `bed_sleep` 为横卧动作，原有 `sleep` 卷曲睡眠仍可通过菜单或 `play` 使用。

## 两端如何配合

服务器负责模型许可、真实姿态、权限、观众选择及动作配置；客户端负责已授权模型的本地绘制。**是否安装模组取决于观看者**：安装模组的玩家可以本地显示另一位未安装模组玩家的伪装。

| 场景 | 模型显示方式 | 条件 |
| --- | --- | --- |
| 观看者未安装或关闭模组 | ModelEngine | 加载 ModelEngine 资源包 |
| 观看者安装模组，使用本插件创建的伪装 | 可接管为本地渲染 | 模型资产和纹理加载成功、服务器确认，且符合观众限制 |
| 接管 `/meg disguise` 的动作 | ModelEngine | 原模型须使用 `state_machine`；接管不会转为客户端渲染 |
| 模型资产不支持、同实体有外来模型或本地渲染失败 | 保持或恢复 ModelEngine | 失败只影响相应观看者的接管 |
| `/mpaclient preview` | 本地示例预览 | 已进入世界且本人没有服务器动作绑定 |

客户端握手不会直接隐藏 ModelEngine。它先下载或校验缓存模型、准备纹理，再发送 `render_ready`；服务器确认 `render_ack` 后才启用本地绘制。两种路径可同时服务不同观众。

## 安装

1. 准备 Paper 1.21.11、Java 21 和 ModelEngine R4.1.1。插件启动时检查 Paper 的 Minecraft 版本和 ModelEngine 的精确版本。使用真实坐下、爬行指令时，还需安装 [GSit](https://github.com/Gecolay/GSit)；当前姿态与分包假人可见性适配以 3.5.1 为基准，其他版本需验证。原生爬行、睡眠及载具动画同步无需 GSit。
2. 从 [Releases](https://github.com/uwuhhhj/MEPlayerActions/releases/latest) 下载 `MEPlayerActions-版本-install.zip`，停服后将包内 `plugins/` 合并到服务器目录。更新时移除旧版插件 JAR，并将旧配置中的自定义设置迁入包内新配置；覆盖自定义模型前先备份。
3. 启动服务器，执行 `/meg reload models`，让玩家加载 ModelEngine 生成的新资源包。安装包提供 `ysm_01_jk_player`、`ysm_01_jk_npc` 和基准模型 `ysm_02_jk`。

服务端安装包的主要目录如下；ModelEngine、GSit 及生成后的资源包需另行准备：

```text
服务器目录/
└─ plugins/
   ├─ MEPlayerActions-<版本>.jar
   ├─ MEPlayerActions/
   │  └─ config.yml
   └─ ModelEngine/
      └─ blueprints/npc/
         ├─ ysm_01_jk_player.bbmodel
         ├─ ysm_01_jk_npc.bbmodel
         └─ ysm_02_jk.bbmodel
```

### 客户端安装

1. 使用 Minecraft **1.21.11 Fabric**，安装 Fabric Loader（最低 0.18.4）和该 Minecraft 版本的 Fabric API（最低 0.140.2）。
2. 将 Releases 中的 `MEPlayerActions-Client-版本.jar` 放入该游戏实例的 `mods/`，重新启动游戏。服务器插件与客户端均使用 0.3.2 或同一发布版本。
3. 进入服务器并伪装后，按 **N** 打开中文动作面板。默认直接跟随客户端实体的位置、朝向和普通动作；自己的模型和其他已伪装玩家的模型均由观看者本地绘制，被观看者无需安装模组。第一人称自动隐藏自己的完整模型，第三人称默认可见。关闭本地渲染会恢复 ModelEngine。

客户端会自动下载服务器模型并缓存。资源重载或渲染失败时恢复 ModelEngine，模型再次准备好后重新接管。当前目标版本为 1.21.11；26.x 需适配并验证对应版本的加载与渲染接口。

`ysm_02_jk` 来自 `JK酒狐_ME通用精简.bbmodel`，保留原骨架、贴图和动画，仅修改模型标识。它包含 YSM 表达式和脚本，当前客户端会回退到 ModelEngine，供验证模型切换与恢复显示；不包含 YSM 的动态物理功能。

## 快速使用

```text
/meplayeractions models
/meplayeractions disguise ysm_01_jk_npc
/meplayeractions menu
/meplayeractions play wave
/meplayeractions undisguise
```

依次查看模型、进行伪装、打开动作菜单、播放挥手、解除伪装。伪装后自动同步玩家状态，也可在菜单中点击动作。`play` 仅展示动画，真实坐下或爬行请使用 `pose`。指令支持 Tab 补全；输入 `/meplayeractions help` 查看帮助。

客户端还可使用 `/mpaclient` 打开面板、`/mpaclient status` 查看连接状态、`/mpaclient toggle` 开关本地渲染。解除服务器伪装后，在世界内执行 `/mpaclient preview ysm_01_jk_npc` 可本地预览示例模型，切换第三人称观察；使用 `/mpaclient preview off` 结束。预览只影响自己的客户端，不会创建服务器伪装或授予真实姿态能力。

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

`stop` 只停止手动动画，自动姿态同步仍可继续；`reset` 清理本插件创建的姿态、飞行和药水并重新采样，保留伪装。`undisguise` 对本插件创建的模型解除伪装，对接管的原生模型只退出动作接管；后者仍需 `/meg undisguise` 解除原模型。

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
| `synchronization.*` | 全局动画同步许可；与玩家自己的 `sync` 设置共同决定实际启用项 |
| `manual.*` | 原始动画播放许可、动作冷却、最长持续时间、速度和中断条件 |
| `client-sync.*` | 客户端通信开关、负载大小、动作请求冷却及额外通信距离限制 |

真实飞行需设置 `gameplay.allow-flight-command: true` 并授予 `mact.flight`。普通玩家默认可使用伪装、动作、坐下、爬行和同步；缓慢药水、飞行、诊断、重载分别需要 `mact.disguise.effects`、`mact.flight`、`mact.debug`、`mact.admin`，默认仅 OP 拥有。

接管 `/meg disguise` 时，需在 ModelEngine 配置中启用 `Model-Engine.Use-State-Machine`，重载后重新伪装，再执行 `attach`。

观看范围有两道限制：本插件创建的伪装先选择 ModelEngine 可跟踪、同世界、可见且距离严格小于 `disguise.view-distance` 的最近观众，再受 `client-sync.view-distance-blocks` 的通信距离限制。后者默认 64 格，不会把前者默认 8 格的模型观看范围扩大到 64 格。距离包含高度，本人由 `show-self` 单独控制且不占人数名额。

### 添加模型与动作

1. 将模型放入 ModelEngine 的 `blueprints/`，确保模型 ID 已加载，并把 ID 加入 `models.allowed`。模型和命令动作 ID 使用 1–64 位小写英文、数字、`_`、`-`。
2. 在 `animations.models.<模型ID>` 设置状态到动画候选列表的映射。服务器按顺序选择模型实际拥有的第一个动画；省略状态继承 `animations.defaults`，显式设为 `[]` 则禁用该状态映射。
3. 在 `custom-actions` 添加菜单动作别名。动画名必须存在于模型中；`play` 和两端菜单使用别名调用，同名自定义动作优先于原始动画。
4. 更新模型后执行 `/meg reload models`、`/meplayeractions reload`，重新分发 ME 资源包并重新伪装。插件重载会结束旧会话并清空本次服务生命周期的资产缓存。

例如，将以下片段合并到已有配置的相应节点，避免重复顶层键：

```yaml
models:
  allowed: [ysm_01_jk_player, ysm_01_jk_npc, ysm_02_jk, my_avatar]
animations:
  models:
    my_avatar:
      run: [run, walk]
      crawl-idle: [crawl_idle]
      crawl-walk: [crawl_walk]
custom-actions:
  greeting:
    label: 打招呼
    animation: wave
    loop: ONCE
    speed: 1.0
    permission: mact.play
```

客户端原始模型查找顺序为 `plugins/MEPlayerActions/models/<id>.bbmodel` → ModelEngine `blueprints/` 中匹配文件名或 `model_identifier` 的模型 → 服务端 JAR 内置示例。第一处用于显式覆盖客户端资产，应与 ME 实际加载的模型保持一致；放入此目录不会自动向 ME 注册模型。客户端缓存按原始文件 SHA-256 区分，模型内容更新后无需手动清空旧缓存。

自定义客户端模型推荐使用与示例相同的 Blockbench 4.10 格式、内嵌 PNG、cube 类型 `.bbmodel`、内联 `outliner` 和逐面 UV，支持数值型 position/rotation/scale 及 linear、step、catmullrom 关键帧。解析器接受 3.2 及以上的 3.x、4.x，以及使用内联骨骼的 5.0；外部贴图、mesh、脚本、box UV、cube rescale、5.0 非空分离骨骼表及 5.1 以后格式不支持，无法本地加载时继续使用 ModelEngine。不能把任意 ModelEngine 特殊骨骼行为视为已实现的客户端功能，详细边界见架构文档。

### 客户端设置与数据文件

| 文件或设置 | 用途 |
| --- | --- |
| 服务端 `plugins/MEPlayerActions/players.yml` | 保存每个玩家的同步偏好，不保存跨重启的伪装会话 |
| 客户端 `config/meplayeractions-client.json` | 保存本地显示设置 |
| 客户端 `config/meplayeractions/cache/<SHA256>.bbmodel` | 校验过的模型磁盘缓存，清理策略总量约 128 MiB |
| `enabled`，默认 `true` | 是否启用本地渲染；关闭后释放接管并恢复 ME |
| `showSelf`，默认 `true` | 本人客户端是否显示自己的模型，仍须满足服务器 `show-self` |
| `followServerTimeline`，默认 `false` | 使用服务器拖后轨迹及其动画，而非客户端实体即时跟随 |
| `interpolationTicks`，默认 `2`，范围 0–6 | 仅对服务器拖后模式增加展示缓冲 |

## 常见问题

| 现象 | 检查方式 |
| --- | --- |
| 插件未启用 | 查看启动日志，核对 Paper 1.21.11、Java 21 和 ModelEngine 精确版本 R4.1.1 |
| “模型未列入”或“模型未加载” | 核对 `models.allowed` 与 `/meg reload models` 的加载结果，检查同名模型 |
| 模型能显示，但某状态不播放 | 使用 `/meplayeractions animations` 和 `status` 检查候选映射、实际命中及同步开关；禁用映射不会自动变成别的姿态 |
| 客户端显示“等待服务器” | 核对服务端 `client-sync.enabled`、两端协议 v3 和客户端本地渲染开关 |
| 客户端保持 ME 渲染 | 核对伪装是否由本插件创建、是否共存外来模型，检查资产加载、纹理错误和 `/mpaclient status`；接管原生 ME 仅同步动作 |
| 趴下仍直立或床上模型位置不对 | 更新玩家与 NPC 示例模型并重新伪装；爬行使用卧倒 Root 的 `crawl_*`，原生床使用 `bed-sleep`，GSit 躺下使用 `sleep`，不要额外给模型套原版整身旋转 |
| 第一人称看不到自己的完整模型 | 客户端主动跳过本人完整模型；切换第三人称，并核对服务端 `show-self` 与客户端 `showSelf` |
| 真实坐下或爬行不可用 | 检查 GSit API 诊断与 `mact.sit` / `mact.crawl`；菜单动画按钮只播放动画，真实姿态使用 `pose` 或专用按钮 |
| 手动动画很快停止 | 检查移动、伤害、姿态变化和 `manual.max-duration-ticks`；这些是默认中断条件 |

`status` 默认需要 `mact.debug`。渲染失败后客户端对相应资产退避约 30 秒再尝试；服务器的渲染租约为 100 tick，客户端还按单调时间检查约 5 秒超时。恢复时间受服务器和客户端调度影响。

## 从源码构建

服务端使用 Maven，客户端使用项目自带的 Gradle 9.2.0 Wrapper，均需 JDK 21。服务端通过 `pom.xml` 的本地 `systemPath` 编译依赖 ModelEngine，默认位置是项目上一级的 `ModelEngine-R4.1.1.jar`；该依赖不随插件打包。

在项目根目录执行：

```powershell
mvn package
```

如依赖位于其他路径，可使用 `mvn "-Dmodelengine.jar=C:/path/ModelEngine-R4.1.1.jar" package`。在 `client/` 目录执行：

```powershell
.\gradlew.bat build
```

Linux/macOS 使用 `./gradlew build`。产物分别位于 `target/MEPlayerActions-0.3.2.jar` 和 `client/build/libs/MEPlayerActions-Client-0.3.2.jar`；安装客户端时使用普通 JAR，而非 `-sources.jar`。

`mvn package` 与 Gradle `build` 分别运行两端单元测试。需要验收显示效果时，还应在真实 Paper 和 Minecraft 客户端中检查模型、资源重载、姿态、观看距离，以及安装和未安装模组的混合观众；单元测试不能替代这一验收。

发布工具为 [tools/package_release.py](tools/package_release.py)，向工作区根目录的 `dist/` 输出服务端安装包、源码包、客户端 JAR/安装包、测试证据及验证 JSON。它要求最新两端构建、无失败或跳过的测试报告、模型清单匹配，以及绑定到实际 JAR SHA-256 的两客户端实机报告；仅运行构建命令不足以完成发布验收。详细输入与产物见 [整体设计架构](ARCHITECTURE.md#10-构建测试与发布边界)。
