# MEPlayerActions 0.2.6

用于 **Paper 1.21.11 + ModelEngine R4.1.1** 的玩家伪装动作插件，Java 21。新增 `/meplayeractions` 指令、动作菜单、姿态动画同步和自定义动作配置。模型仍由 ModelEngine 加载、渲染和打包资源包。

作者署名：SIMMC、Loliiiico。

## 安装

1. 停服，移除服务器上的旧版 MEPlayerActions JAR，再把安装 ZIP 内的 `plugins/` 合并到服务器目录；首次安装使用包内默认配置，更新时保留自己的配置和 `players.yml`。**0.2.6 沿用旧 NPC 会话动画兼容；模型资产未变，可只更新 JAR。旧配置缺少新观众字段时自动使用默认值。** 安装新版 bbmodel 时按下面第 3 步重载模型和资源包。
2. 保留已有 ModelEngine R4.1.1。使用 GSit 坐姿/躺卧或 `/meplayeractions pose sit`、`/meplayeractions pose crawl` 时需要 [GSit](https://github.com/Gecolay/GSit)。插件对 3.x 公共 API 在运行时检查，不再因版本不是 3.2.1 就拒绝接入。原生陆地趴下、床上睡眠和船/矿车识别无需 GSit。
3. 如果更新了 bbmodel，执行 `/meg reload models`，让玩家加载 ModelEngine 新生成的资源包。只更新 JAR 并使用旧 NPC 兼容轨道时不需要此步。
4. 执行 `/meplayeractions disguise ysm_01_jk_npc`，再用 `/meplayeractions menu` 或 `/meplayeractions play wave` 测试。默认本人可见模型，可切第三人称观察；避免第一人称遮挡时加 `show-self=false`。

安装包包含：

```text
plugins/MEPlayerActions-0.2.6.jar
plugins/MEPlayerActions/config.yml
plugins/ModelEngine/blueprints/npc/ysm_01_jk_player.bbmodel
plugins/ModelEngine/blueprints/npc/ysm_01_jk_npc.bbmodel
```

示例玩家模型 `ysm_01_jk_player` 有 28 个动画；兼容 NPC 模型 `ysm_01_jk_npc` 在原有 26 个动画后新增 `crawl_idle`、`crawl_walk`、`player_jump`，共 29 个。两个模型沿用含耳尖约 1.75 格的几何体；NPC 的几何、贴图、层级和原有动画保持一致，不需要修改 MythicMobs 技能里的旧动画名。若你自行改过 NPC 模型，应先备份并合并新增轨道，避免覆盖个人修改。`hover` 复用 `fly`，鞘翅动画暂用 `fly` 回退。资源包需由你的 ME 生成；包内不附带 ModelEngine、GSit 或 MythicMobs JAR。

0.2.0 的默认 NPC 配置将 `crawl-idle` 和 `crawl-walk` 显式禁用，NPC 原有 `climb` 也是直立动作；这会出现 debug 显示 `CRAWL_IDLE`、原生 `SWIMMING`，却没有自动动画。0.2.1 同时修复模型和旧配置映射。陆地趴下使用 Root 旋转 90° 的专用轨道，GSit 躺下使用 `sleep`；不把两者混用。缺少映射会在首次进入状态时提示原因，`/meplayeractions status` 会列出候选动画。

0.2.3 兼容服务器仍加载旧 NPC 的情况：`/meplayeractions animations` 若只有 26 个旧动画、缺少 `crawl_idle`、`crawl_walk`、`player_jump`，会在创建或接管玩家伪装时尝试生成这三个会话内动画。`crawl_idle` 复制 `climb_idle` 的肢体姿势，`crawl_walk` 复制 `climb` 的手脚运动，并补上卧倒 Root 的 90° 旋转与位置补偿；原有 `climb`、`climb_idle` 继续保留。`player_jump` 根据旧 `jump` 补齐起落过渡，Root 不额外抬高。轨道仅用于此玩家会话，不写入 ModelEngine 的共享蓝图，不改变其他 NPC 的原动画；同名模型原有的新版轨道优先。

兼容只适用于随包 `ysm_01_jk_npc` 的已知 Root 骨架和符合条件的原轨道；自定义过骨架、Root 姿势或脚本的模型可能需要合并新版 bbmodel。配置显式禁用的映射仍保持禁用。兼容轨道会列入 `/meplayeractions animations` 并可手动播放；`/meplayeractions status` 分别显示模型原有动画数、会话内补齐列表、实际命中的动画、轨道来源与播放层状态。旧原版 NPC 预期显示“模型原有动画：26；会话内补齐：[crawl_idle, crawl_walk, player_jump]”；陆地静止趴下预期是 `CRAWL_IDLE`、自动动画 `crawl_idle`。若模型原有动画数仍为 26，可能有未更新或重复同名的 bbmodel，可用这个数确认实际加载结果。

0.2.4 修复“爬行轨道已播放，模型却比原玩家/鞘翅低”的定位问题。核对基准 `JK酒狐_ME通用精简.bbmodel` 后，`climb` 与 `climbing` 原本就使用 Root X=90°、位置 `(0,4,19)`，头部另有 X=-90° 补偿；随包模型按 NPC 比例使用这些轨道，保留这一次旋转，不再给玩家实体额外旋转。问题在旧版伪装把 ME 显示枢轴挂到玩家身上：R4.1.1 `getPivotOffset` 使用 `Pose.STANDING` 的尺寸，而客户端乘挂点随实际姿态改变，导致趴下时发生额外高度偏差。

现在 `/meplayeractions disguise` 创建的模型使用 ME 的独立显示枢轴，以玩家脚底坐标定位，保留模型原有的动画位置补偿和可配置视觉延迟。站立、潜行、趴下、睡眠及载具都不再经过玩家乘挂高度这一层；朝向仍由 ME 身体旋转控制。`/meplayeractions status` 新增“模型定位”，应显示“独立视觉枢轴（以玩家脚底坐标定位）”。本次模型资产和配置未改变，只更新 JAR 后重新伪装即可。接管 `/meg disguise` 时继续保留原生定位方式，若其使用玩家乘挂，可改用 `/meplayeractions disguise` 创建独立定位的伪装。

## 玩家指令

0.2.6 主命令统一为 `/meplayeractions`，旧 `/mact` 与 `/meactions` 不再注册。子命令按用途整理：模型（`disguise / undisguise / models / attach`）、动画（`menu / animations / play / stop`）、姿态（`pose / reset`）、同步（`sync`）及管理（`status / reload`）。姿态使用 `pose sit`、`pose crawl`、`pose fly`；动作使用 `play <动作名>`，不再注册 `wave` 等根级快捷子命令，也不再接受 `list / action / debug` 旧名称。`help` 按这些用途分组展示，Tab 只补全当前命令和具有权限的选项。

`show-self` 默认恢复为 `true`；配置缺少该字段时同样为 true。更新时若保留了 0.2.5 配置中明确写出的 `show-self: false`，这个显式设置仍生效：请改为 `true` 后重载，或在本次伪装命令中加 `show-self=true`。

| 指令 | 用途 |
| --- | --- |
| `/meplayeractions disguise <模型名> [参数...]` | 先指定模型，再设置缩放、原人物隐藏、视觉延迟、观众范围与缓慢药水 |
| `/meplayeractions undisguise` | 解除本插件伪装；接管原生 ME 时只停止动作接管 |
| `/meplayeractions menu` | 点击展示当前模型的动作，支持分页 |
| `/meplayeractions models`、`/meplayeractions animations` | 查看允许且已加载的模型、当前动画 |
| `/meplayeractions play wave`、`/meplayeractions play nod`、`/meplayeractions play talk` | 使用配置中的动作别名 |
| `/meplayeractions play sit 1.0 HOLD` | 展示动画，支持速度及 `ONCE / LOOP / HOLD` |
| `/meplayeractions pose sit`、`/meplayeractions pose crawl`、`/meplayeractions pose fly [on|off]` | 坐下／爬行通过 GSit 创建真实姿态；飞行须在配置中启用并拥有权限 |
| `/meplayeractions stop` | 停止手动展示动作，保留自动同步 |
| `/meplayeractions reset` | 结束本插件创建的坐姿、爬行、飞行、展示动作与伪装药水 |
| `/meplayeractions sync sit off` | 关闭自己的坐姿动画同步，持久保存 |
| `/meplayeractions sync sit default` | 恢复跟随服务器配置 |
| `/meplayeractions status`、`/meplayeractions reload` | 管理员查看状态、重载配置 |

自动同步包括闲置、走路、跑步、跳跃、坠落、潜行、游泳、坐姿、睡眠/躺下、船/矿车/其他坐骑、陆地趴下/爬行、悬停、飞行、鞘翅、主副手挥臂和挖掘。床上睡眠、原生低矮空间趴下、载具乘坐直接读取 Paper 状态；GSit `/lay`、`/bellyflop` 和爬行读取 GSit API，优先于 GSit 的隐形座位。水中水平游泳停住使用 `swim-prone-idle`，直立踩水使用 `swim-idle`，避免停止移动就站起来。关闭姿态同步后，不会将该姿态误判成跳跃或游泳。

菜单中的随包默认动画已有完整中文名称；保留动作 ID 在物品说明中，便于使用 `play` 命令。菜单区分动画展示与底部“真实坐下／真实爬行／真实飞行”按钮，播放坐姿动画不会让玩家真实坐下。原始动画名可通过 `menu.animation-labels.<动画ID>` 修改，`custom-actions.<动作名>.label` 优先；旧配置未写名称时也会使用内置汉化。未知自定义动画默认显示“自定义动作”，其 ID 仍在说明中。

## 指令中直接配置伪装

0.2.5 要求先填写模型名，再填写任意组合的多个参数，不需要编辑 `config.yml`：

```text
/meplayeractions disguise ysm_01_jk_npc scale=0.8 effect=slowness:1
/meplayeractions disguise ysm_01_jk_npc scale=1.2 delay=4 effect=slowness:2:60
/meplayeractions disguise ysm_01_jk_player hide-self=false delay=0
/meplayeractions disguise ysm_01_jk_npc scale=0.75 show-self=false view-distance=8 max-viewers=10 effect=slowness:1
```

模型名必填，不能把 `scale=...` 等参数放在模型名之前。模型名后也支持 `--scale 0.8`、`--scale=0.8`、`--effect slowness:1` 等写法；Tab 第二项只补全模型，后续项补全参数。

| 参数 | 含义 |
| --- | --- |
| `scale=0.8` | 模型缩放，范围 0.05–8；省略使用 `disguise.scale` |
| `hide-self=true` | 是否隐藏原玩家人物，接受 true/false 或 on/off；省略使用配置默认值 |
| `show-self=true` | 是否向本人渲染模型，默认 true；可配置 `disguise.show-self`。不会占用其他观众名额 |
| `view-distance=8` | 其他观众与真实玩家脚底的三维距离必须严格小于此值；默认 8 格，范围 0.1–256；配置 `disguise.view-distance` |
| `max-viewers=10` | 最近的最多 N 名其他玩家可见；默认 10，范围 0–1000，0 禁止其他人观看；配置 `disguise.max-viewers` |
| `delay=3` | 本次模型的视觉延迟，范围 0–20 tick；省略使用 `visual-follow.delay-ticks` |
| `effect=slowness:1` | 缓慢 I，随本次伪装持续；药水等级从 1 开始，最大 256 |
| `effect=slowness:2:60` | 缓慢 II，最多持续 60 秒；范围 1–86400 秒，解除伪装会提前结束 |

`effect` 只接受缓慢 `slowness`，省略等级默认为 I；其他药水和重复参数会报错。模型、参数名和值都有 Tab 补全。未知参数、重复同类参数或超出范围会明确报错，解析、模型白名单、模型是否加载、药水名和权限均在更改现有伪装前检查。

`show-self` 控制模型本身，`hide-self` 控制原版玩家人物；两项独立。服务器无法获知客户端第一人称/F5 模式，因此 `show-self=false` 在第一人称和第三人称都隐藏本人的模型，其他玩家按范围与名额观看。观众每 tick 更新，在范围内按距离从近到远分配，同距离按 UUID 排序；本人不占名额。距离恰好等于上限时不可见；跨世界、离线、`Player.canSee` 不可见及 ME 强制隐藏的观众不会占名额。此范围是额外上限，最终可见性仍受 Paper 的实体跟踪距离、ME 剔除和客户端渲染距离限制。

这些观众参数通过 ME 实际模型跟踪接口控制生成和销毁，并同步限制预留客户端协议的状态收件人；不会更改真实玩家坐标、碰撞或 Bukkit 的隐藏玩家设置。仅对本插件创建的 `/meplayeractions disguise` 生效；`/meplayeractions attach` 保留原生 ME 的可见性。`/meplayeractions status` 显示参数与当前入选的其他观众数。解除伪装会恢复本插件改动的跟踪过滤与强制配对状态。

缓慢药水参数需要 `mact.disguise.effects`，默认 OP 可用；给普通玩家开放时可通过权限插件授予，无需改本插件配置。缩放、隐藏、观众范围与延迟沿用 `mact.disguise` 权限。

重复执行同模型指令也可应用新参数；重新执行不带参数的伪装指令会恢复配置默认值并结束之前的伪装药水。参数仅属于本次会话，不写入全服配置。接管原生 `/meg disguise` 的会话须先解除原生模型后再使用带参数的 `/meplayeractions disguise`。

解除/更换伪装、主动 `/meplayeractions reset`、退出和正常插件关闭会清理受管药水；原有同类药水会扣除已过时间后恢复，已过期的不会恢复。死亡不恢复先前药水。喝牛奶、使用其他药水或其他插件改动同类效果后，插件结束对该类型的管理，避免之后清理掉新的效果。普通传送和姿态切换保留本次药水。持续药水按最多 200 tick 的短租约续期，异常中断续期后约 10 秒内失效（20 TPS），不会写入永久效果。显示药水图标，默认不显示药水粒子；最终效果可用 `/meplayeractions status` 的“指令参数”和“受管药水”检查。

跳跃通过 Paper 起跳事件跟踪，另以离地上升作兜底；走下悬崖选择 `fall`。默认 `jump` 优先映射新增 `player_jump`，具有起跳、腾空和恢复站姿的完整周期，原有 `jump` 保留作为回退。连续跳跃会重新开始新一轮；短跳落地不会立即切掉动画，长时间下落会切换到坠落。姿势切换、传送和重置会清除旧跳跃。

挖掘由 `BlockDamageEvent` 开始，主手摆臂续期；松手、破坏方块、换目标、换工具或超时会停止。原生挥臂由 `PlayerArmSwingEvent` 识别，区分主副手。它与主动打招呼 `/meplayeractions play wave` 是两种动作。姿态在优先级 100，手臂动作在 150，手动动作在 200；手臂层按模型关键帧叠加，不重播腿部姿态。示例模型的挖掘回退到已有 `attack`，副手回退到 `use_offhand`；专用挖掘/载具动画可自行映射。静态坐姿、睡眠和趴姿默认 `HOLD`，持续移动和挖掘 `LOOP`，挥臂 `ONCE`。

模型缺少某项映射时不播放该覆盖层；坐下和爬行指令会在开始前检查必要动画。`play` 只展示动画，例如 `play fly` 不授予飞行能力。默认手动动作遇移动、伤害、姿态切换或超过 30 秒会结束。

可用同步类型：`movement sprint jump sit sleep ride crawl sneak swim flight elytra swing mining`。例如 `/meplayeractions sync sleep off`、`/meplayeractions sync ride off`、`/meplayeractions sync mining off`。服务器关闭的项，玩家不能自行打开。关闭同步只停止本插件对应的动画层，不阻止真实姿态，也不停止 ME 自身基础动作。

## 常用配置

配置位置：`plugins/MEPlayerActions/config.yml`。修改后执行 `/meplayeractions reload`；重载会清理本插件的伪装和姿态，随后重新 `/meplayeractions disguise`。

关闭全服坐姿同步：

```yaml
synchronization:
  enabled: true
  sit: false
```

自定义动作别名，玩家使用 `/meplayeractions play greeting` 或 `/meplayeractions play greeting`：

```yaml
custom-actions:
  greeting:
    label: 打招呼
    animation: wave
    speed: 1.35
    loop: ONCE
    max-duration-ticks: 60
    permission: mact.use
```

`max-duration-ticks` 不得超过 `manual.max-duration-ticks`。模型和动作 ID 使用 1–64 位小写英文、数字、`_`、`-`。需要只开放配置动作时，将 `manual.allow-raw-animation` 设为 `false`；默认会开放模型的所有原始动画。可在 `animations.models.<模型名>` 修改各状态的候选动画，空列表 `[]` 表示禁用该模型的对应映射。新模型还需加入 `models.allowed`。

采样、动画切换检查与过渡时间现在分别配置：

| 配置 | 默认值 | 效果 |
| --- | --- | --- |
| `controller.sample-interval-ticks` | 1 | 读取位置、输入、姿态的间隔，范围 1–20 tick |
| `controller.animation-update-interval-ticks` | 1 | 应用新动画层的检查间隔，范围 1–20 tick |
| `controller.transition-in-ticks` / `transition-out-ticks` | 2 / 2 | 动画入场、退场的混合插值时间，0 表示立即切换 |
| `controller.swing-duration-ticks` | 8 | 挥臂事件的有效时间，连续挥臂续期 |
| `controller.mining-timeout-ticks` | 12 | 连续挖掘未收到主手摆臂时的兜底超时 |
| `visual-follow.delay-ticks` | 2 | 模型位置及对应姿态的显示延迟，范围 0–20 tick；0 关闭 |
| `visual-follow.max-distance-blocks` | 2.0 | 最大视觉拖后距离；超限立即对齐，范围 0.1–8 格 |
| `visual-follow.snap-on-posture-change` | true | 坐姿、趴姿、睡眠、游泳、载具、飞行切换时立即对齐 |
| `jump.minimum-play-ticks` | 0 | 0 按动画长度/速度及入场时间计算；也可指定 1–100 tick |
| `jump.landing-grace-ticks` | 6 | 落地后动画收尾的上限，范围 0–40 tick；0 落地即结束 |

20 TPS 下 1 tick 约 50 ms。优先保持两个间隔为 `1`；减小过渡到 `1` 会更快响应，增大到 `3`–`4` 会更柔和。增大采样/切换间隔会增加识别延迟，短暂姿态可能在采样之间被错过；需要视觉拖后时优先调 `visual-follow.delay-ticks`。位置及跳跃过程仍每 tick 记录，完整姿态采样遵循配置间隔。挥臂事件独立记录，有效期至少覆盖一个动画检查间隔。移动速度除以实际经过的 tick，同一 tick 的指令采样、跨世界和传送不会产生异常速度。旧 `controller.interval-ticks` 仍作为采样间隔的兼容别名，显式设置新键时以新键为准。

播放中的动画持续由 ME 推进，未改变的动画不会每次检查都从头开始；退场也保留混合时间。ME R4.1.1 的 Display 骨骼使用客户端变换插值，网络刷新和距离 LOD 仍由 ME 渲染器控制。0.2.1 通过 ME 的视觉变换接口给模型增加可配置的位置拖后；历史位置与姿态使用同一帧，避免模型还在空中却切成走路。玩家真实坐标与碰撞照常，不复制多个模型残影。传送/跨世界、拖后距离超限会立即对齐；默认进入或离开固定姿态也对齐。床/载具的偏移、朝向还取决于模型关键帧和 ME pivot，需在游戏里核对。

建议先使用默认的 2 tick（约 100 ms），想要更明显的拖后可调到 3–4；设为 0 恢复紧跟。跳跃最短时间默认从模型动画自动计算，最多 100 tick；落地收尾还有独立上限，低天花板下不会拖很久。示例 `player_jump` 不额外抬高 Root，真实轨迹的视觉延迟已经提供腾空高度。

```yaml
visual-follow:
  delay-ticks: 2
  max-distance-blocks: 2.0
  snap-on-posture-change: true
jump:
  minimum-play-ticks: 0
  landing-grace-ticks: 6
```

可以单独调整每个自动状态的播放参数，以及每个模型的动画映射：

```yaml
animations:
  models:
    ysm_01_jk_player:
      sleep: [sleep]
      boat: [sit]
      minecart: [sit]
      mining: [attack]             # 有专用动画时改为 [mining, attack]
      swing-mainhand: [attack]
      swing-offhand: [use_offhand]
animation-settings:
  sleep:
    loop: HOLD
    speed: 1.0
    transition-in-ticks: 2
    transition-out-ticks: 2
  mining:
    loop: LOOP
    speed: 1.2
    transition-in-ticks: 1
    transition-out-ticks: 1
```

`animation-settings.<状态>` 支持 `speed`（0.05–20）、`loop`（`ONCE / LOOP / HOLD`）及两个过渡时间；未配置时使用默认模式、速度 1 与 controller 过渡时间。`animations.models.<模型>.<状态>: []` 显式禁用该模型映射。动作切换冷却仍由 `manual.cooldown-ticks` 控制，默认 4 tick。`disguise.scale` 控制伪装缩放。

从旧版升级时删除旧 JAR，保留自己的 `config.yml` 和 `players.yml`，启动后重新伪装；旧原版 NPC 会尝试启用上述会话内兼容。若安装新版 bbmodel，仍需 `/meg reload models` 和更新资源包。新状态的缺省映射会补足，新配置注释需对照安装包合并。`config-version` 小于 3 或未写时，旧版 NPC 默认空爬行映射会在内存中恢复为通用候选，旧默认 `jump: [jump]` 会优先尝试 `player_jump`；已有的非空自定义映射保留。若要继续禁用 NPC 爬行映射，明确使用 `config-version: 3` 和 `[]`，或关闭 `synchronization.crawl`。本插件不自动改写配置文件。若自定义过动画优先级，须满足 `posture-priority < interaction-priority < manual-priority`，手臂层默认 150。

动作层优先级须满足姿态 < 交互 < 手动，默认分别为 100、150、200，对应 `controller.posture-priority`、`controller.interaction-priority`、`controller.manual-priority`；配置未写交互优先级时使用 150。

真实飞行指令默认关闭。管理员同时设置 `gameplay.allow-flight-command: true` 并授予 `mact.flight` 后，可使用 `/meplayeractions pose fly on` 和 `/meplayeractions pose fly off`；速度由 `gameplay.flight-speed` 控制。创造模式已有飞行无需开启此选项即可同步动画。

## 接管原生 ME 伪装

`/meplayeractions disguise` 会自动创建 `state_machine` 动画处理器。若继续使用 `/meg disguise`，先将 ME 配置的 `Model-Engine.Use-State-Machine` 设为 `true`，重载 ME 后重新伪装；**R4.1.1 的 disguise 指令没有 `usm=true` 参数**。

默认自动接管白名单内的状态机伪装，也可 `/meplayeractions attach <模型>` 手动接管。原生伪装执行 `/meplayeractions undisguise` 后会暂停本次登录的自动接管，重新 `attach` 可恢复；解除原生模型本身使用 `/meg undisguise`。

普通玩家默认拥有 `mact.use / disguise / play / sit / crawl / sync`；`mact.disguise.effects / flight / debug / admin` 默认为 OP。真实姿态通过 GSit API 创建，其他插件仍可取消相关事件。`reset` 只清理本插件创建的姿态和仍受管的药水。

## 可选客户端与验证

预留频道 `mact:main`，含握手、附近玩家状态快照、动画开始 tick、停止与心跳，详见 [协议说明](src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md)。此版本始终使用 ME 渲染；客户端本地骨骼渲染、模型数据导出和逐观察者渲染切换尚未实现。OpenYSM、BetterModel 的代码仅用于研究参考。

已完成 Java 21 编译、158 项动作状态/配置/协议/飞行恢复/模型轨道/跳跃时间线/伪装参数/药水租约/旧 NPC 会话兼容/显示枢轴定位/模型观众测试与安装包一致性检查。兼容测试使用 ME R4.1.1 的真实蓝图、时间线和关键帧类，检查旧 26 动画补齐、共享模型不变、肢体轨道独立复制、Root 单位与 1.5 倍缩放、新版轨道优先及不同骨架拒绝兼容；新增实际 ME 显示枢轴测试，覆盖 0.5/1/1.5/2 倍缩放和四个朝向的动画位移。真实游戏中的最终渲染仍需安装后验证。建议先以旧 NPC 执行 `/meplayeractions disguise ysm_01_jk_npc scale=1.5 show-self=true`，确认 status 显示会话内补齐、自动 `crawl_idle` 和独立视觉枢轴，再测试爬行与跳跃。其余回归包括：带缩放与缓慢的伪装并重复修改参数 → 解除伪装/喝牛奶/更换药水 → 两个模型在陆地一格高空间趴下/爬行 → 床上睡觉并起身 → GSit 坐下/躺下/趴下 → 船/矿车上下车 → 水中水平游泳停住与直立踩水 → 普通跳/连续跳/低天花板跳/悬崖坠落 → 主副手挥臂 → 挖掘、松手、换目标 → 传送与解除伪装，并让另一名玩家观察。分别尝试视觉延迟 0、2、4 tick。使用 `/meplayeractions status` 查看原生 Pose、载具、采样姿态、手臂状态、视觉延迟、跳跃播放时长和实际映射。

观众回归还需在游戏里测试：本人隐藏/可见 → 7.9 格进入、恰好 8 格离开 → 超过 10 名观众时保留最近者 → 最近者退出后补位 → 跨世界与隐藏玩家 → 解除/更换伪装。单元测试已覆盖这些筛选规则、ME 强制配对绕过过滤的处理和临时跟踪接口更换后的清理；尚未运行真实服务器多玩家测试。

接口参考：[Paper 起跳事件](https://jd.papermc.io/paper/1.21.11/com/destroystokyo/paper/event/player/PlayerJumpEvent.html)、[Paper 挥臂事件](https://jd.papermc.io/paper/1.21.11/io/papermc/paper/event/player/PlayerArmSwingEvent.html)、[Paper 挖掘中止事件](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/block/BlockDamageAbortEvent.html)、[GSit 公共 API](https://github.com/Gecolay/GSit/blob/main/core/src/main/java/dev/geco/gsit/api/GSitAPI.java)、[GSit 姿态类型](https://github.com/Gecolay/GSit/blob/main/core/src/main/java/dev/geco/gsit/model/PoseType.java)。本地 ME R4.1.1 API 和渲染器已核对。未取得 GSit 3.5.1 JAR，接口按官方源码核对并在运行时验证；若新版没有 `startCrawl(Player)` 单参数入口，仍可观察已有 GSit 姿态，请用 GSit 自身命令开始爬行，`/meplayeractions pose crawl` 会明确报告接口限制。

## 源码构建

将你自己的 `ModelEngine-R4.1.1.jar` 放在本项目上一级，使用 JDK 21+、Maven 3.8.6+：

```powershell
mvn -B -ntp package
python tools/package_release.py
```

输出到工作区 `dist/`。如 ME JAR 在其他位置，给 Maven 传入 `'-Dmodelengine.jar=绝对路径'`。测试成功后打包工具检查 JAR、模型和配置，并生成安装 ZIP、源码 ZIP 和校验报告；依赖 JAR 与构建缓存不会打入发布包。
