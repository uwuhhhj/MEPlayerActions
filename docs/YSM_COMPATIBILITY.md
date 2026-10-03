# YSM 兼容范围（0.4.2）

[文档索引](README.md) · [客户端配置](CLIENT_CONFIG.md) · [模型同步与部署](MODEL_DELIVERY.md)

**MPA 0.4.2 已实现普通 YSM 文件夹的原版玩家动作、作者配置与本地组件适配；完整 OpenYSM 功能仍有边界。** 私人目录导入与服务器完整 BBModel 推送使用不同文件入口。下表描述当前源码能力，不把资源加载、单元检查或某个动作能播放等同于所有路径已通过实机验收。

## 当前支持与限制

| 项目 | 当前 MPA 行为与边界 |
| --- | --- |
| 主骨架与动画文件 | 导入有界 `spec:2` 普通文件夹的方块骨架、`main/extra/arm` 动画及独立 `fp_arm` 组件，保留位置／旋转／缩放、插值和循环。内置 CC0 默认模型主动画库为 **115 个 clip**（main 50、extra 8、arm 57），另有 **9 个组件**；组件动画单独计算。这些数量是清单与导入范围，不是实机通过数量。 |
| Molang、查询与物理 | 支持有类型的数值、字符串、向量、`null`、`??`、变量与有界控制语句，识别 `//` 与 `/* */` 注释并保留字符串内的同样字符，提供原版实体的 `query/q` 与 `ysm` 查询、骨骼查询和按实例保存状态的一阶／二阶物理。作者 `fn.*` 调用保留 `args[0]` 等参数的类型，每次调用隔离并恢复参数和临时变量，嵌套调用最多 16 层。查询未绑定时仍可能取 `0`；已明确绑定的 `null` 可使用作者默认表达式。函数与执行量有边界，不能据此假定外部模组查询已接入。 |
| 控制器与时间线 | 导入作者 `animation_controllers` 和 `functions/*.molang`，用文件名 `@event` 后缀注册事件；`name@event.molang` 保存函数与事件，`@event.molang` 仅注册事件。有界 Unicode 名称及含括号的事件函数名可保存，`fn.name` 调用仍须符合安全标识符规则。每个 `AnimationPlayer` 实例首次采样执行 `player_init` 完整列表一次，`reset` 后重跑；每次采样执行 `player_update` 完整列表，各段脚本独立使用参数与临时变量。`player_update` 的 `args[0]` 是第一人称布尔值，读取原版所有者视角绑定，未绑定时按 `fp.arm` 组件判断；GUI 惰性预览为 `false`。内置控制器事件仅执行列表首段，以其返回值决定控制器行为。执行有界状态转换、`on_entry/on_exit`、控制器槽、动画权重、过渡和时间线脚本；各实例持有自己的变量、控制器和物理，GUI 使用独立实例。 |
| 原版动作、手持、装备与第一人称 | 从原版实体观察主副手、挥手、使用物品、骑乘、睡眠等状态，不要求被观看者安装模组。原版物品、盔甲、装饰纹样、附魔光效、鞘翅和头戴物按模型可用定位骨骼绘制；私人 YSM 有可用手臂组件时绘制第一人称手臂，`fp_arm` 文件入口使用正式的 `fp.arm.*` 控制器前缀。保留原版物品使用路径，缺少组件时回退。没有定位骨骼的模型不猜测通用挂点。 |
| 作者 extra 入口与分类 | 本地动作列表和 G 轮盘读取 `extra_animation` 的作者顺序、名称、配置关联及 `extra_animation_classify`，支持分类返回。内置默认模型保留 `extra0`–`extra7` 八个作者入口；关联配置通过齿轮进入。菜单“保持打开”和“动作锁定”分别控制界面及移动时是否取消额外动作。 |
| 动态表单与按模型保存 | 按作者 `config_forms` 生成 checkbox、range、radio。点击“应用并保存”后应用受限的变量／数学赋值脚本，按模型 ID 保存表单变量、radio 选择和皮肤；这些私人参数不发送服务器。保存不包含查询缓存、物理或控制器运行状态；涉及实时世界查询、FX 或外部模组调用的任意表单脚本不属于当前保存入口。 |
| 纹理、语言与皮肤 | 读取作者纹理列表和 `default_texture`，提供该模型的皮肤选择；读取 `lang` 并在作者配置与动作界面使用模型文本。默认/蓝色皮肤与红色蝴蝶结仍可设置。当前渲染选定基础 PNG，normal/specular 等 PBR 材质和任意模型的真实玩家皮肤替换尚未实现。 |
| GUI 与进入效果 | 使用作者 `preview_animation`，默认源为 `gui`；读取作者预览旋转、背景／前景和光照参数。首次显现过渡、GUI 动画和控制器 `on_entry` 是三种机制；参考默认模型没有名为 `enter` 的专用动画，不能把 GUI 效果记作自动 enter 动作。本轮独立客户端实机已验证图集 UV 与默认模型眼睛、作者背景／前景和固定镜头、首次显现进度、重复选择不重启动画及资源重载。 |
| 声音与粒子 | 已导入 `sound_effects/particle_effects` 和本地 OGG，接入 `ysm.play_sound/stop_sound/stop_all_sounds/particle/abs_particle`，按实例／世界管理与释放，带资源及触发预算。本轮独立客户端实机已验证时间线触发的真实 OGG 流打开、循环播放、原版包声音及真实粒子；停播、重载和重置只清理实例所有的效果，并恢复原私人外观。未提供的自定义粒子定义及外部模组效果不能视为已经支持。 |
| 载具与投射物组件 | 读取 `files.vehicles/projectiles`，按实体 ID／标签匹配并在私人模型使用者关联的原版实体上绘制独立组件。组件的空间、运动与环境查询读取实际组件实体；缺少玩家／生物能力时提供有类型的 `null` 与可用性标记，不借用模型所有者的玩家输入。默认导入 arm、fp_arm、箭、三叉戟、浮标、烟花、船、箱船和矿车，共 9 个组件。该私人组件路径不把模型选择或组件资产同步给其他玩家，也不等同于所有模组实体替换。 |

`animation_time_update`、`start_delay`、`loop_delay` 仍是拒绝导入的未支持字段；`blend_weight` 已有支持。普通 BBModel 与 YSM 的文件路径、资源大小、骨骼／方块和脚本预算继续适用。

作者 `ysm.sync` 仅以最多 16 个有限数值参数，在当前 `AnimationPlayer` 内本地分发其私有 `@sync` 事件完整列表；各段脚本的参数／临时变量独立，调用深度最多 16 层并共享执行预算。此路径没有 C2S 消息，不传播至其他玩家，也不接入 OpenYSM 原网络协议。`initialVariables` 在惰性上下文求作者默认值，原版、FX 与同步回调不产生实际效果；`defer` 本轮未接入。本地分发、作者函数和生命周期事件已通过直接执行的单元回归；这些证明不等同于全部作者模型的实机验证或 OpenYSM 网络同步。

外部模组专用动画族与接口（如 tac、carryon、parcool、swem、slashblade、tlm、immersive_melodies、irons_spell_books）没有接入；清单保留这些名称不表示它们的动作文件或运行时已迁移。**加密 `.ysm`、OpenYSM 原网络／缓存协议及网格蒙皮绑定未实现。** 26.x 和未审计 fork 也需要另行适配验证。

`ysm.in_shield_block_cooldown` 的 5 tick 窗口从带明确玩家身份的真实成功格挡事件起算。当前原版 1.21.11 没有已接入的被观看者事件入口，`ysm.in_shield_block_cooldown_available` 因而为 `false`；持盾、挥手、物品冷却或本机按键都不能代替这个事件。其他原版实体查询照常读取，不要求被观看者安装模组。

## 私人配置与验证边界

在图库的模型设置中进入“作者配置 / 皮肤…”，或在本地动作列表／轮盘点击配置齿轮。皮肤切换立即保存；表单修改需“应用并保存”。“重置模型配置”只清除此模型保存的皮肤、表单变量和 radio 选择，不改变私人启用、缩放或位置。

选择、缩放、位置及各模型配置保存在当前实例的 `config/meplayeractions-client.json`。这些操作只影响自己在本机看到的私人外观，不修改服务器姿态、权限或其他观看者的模型。使用服务器动作页仍需服务器授权绑定与接管就绪。

本轮通过 261 项服务端及 277 项客户端单元测试。独立客户端构建（SHA-256 前 12 位 `1dc542e07678`）在无服务器插件环境完成 18 个阶段、127 项实机检查，覆盖作者 GUI 预览、表单引起的真实几何变化与保存恢复、第一人称手臂 GPU 绘制、实际船／箭实体组件，以及声音／粒子的播放、停播和资源释放；私人参数及效果不发送服务器动作请求，验收后恢复原外观并清理夹具。

同一客户端构建在混合观众测试中通过 376 项接管检查，未安装 MPA 模组的独立客户端通过 94 项检查。两套服务器模型的双手持物、挥动及盾牌格挡读取真实原版实体，不要求被观看者握手；还覆盖资源推送、缓存重连、资源包重载、私人外观不影响他人及取消伪装／换模。完整报告及截图随发布的 tests ZIP 提供。

作者函数／事件与本地 `ysm.sync` 的直接执行证明来自单元回归。这些结果不代表所有 OpenYSM 功能或每个作者模型均已验证。清单中的 115 个主 clip、9 个组件和八个 extra 入口仍是静态资源范围，不是逐项实机通过数量。

模型完全加载失败时，查看本地加载错误或 `/mpaclient status`。模型能显示但眼睛、附件或动作与参考项目不同，应检查是否使用了未接入查询、外部模组动画族、缺少定位骨骼或未支持字段。

## 对照源码与参考版本

仓库／源码包可查看 [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[YsmModelProfile](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmModelProfile.java) 的导入与元数据；[Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java)、[YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java) 的脚本／物理／控制器；[VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) 的实际原版输入。

用户界面与保存见 [ModelActionMenu](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelActionMenu.java)、[ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[ModelConfigScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigScreen.java)、[ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java)。独立渲染与效果见 [YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java)、[YsmItemRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java)、[YsmEquipmentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java)、[ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java)。代码链接用于源码包，安装包以本页说明为主。

本表对照 [IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated) 固定提交 `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`：

- [动画文件合并与默认继承](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/model/ModelAssemblyFactory.java#L57)。
- [模型预览使用 preview_animation](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L450)。
- [动态配置表单](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/rip/ysm/gui/ModelSettingsScreen.java#L139)、[YSM 查询／函数](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L79)。
- [控制器 on_entry](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerRuntime.java#L247)、[声音关键帧](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/event/SoundKeyFrameExecutor.java#L23)。

ME／CE 继续生成、合并、保护并下发原版资源包；MPA 同步服务器完整原模型及动作状态，不要求额外离线资源包合并或强制安装 OpenYSM。具体部署见 [模型同步与部署](MODEL_DELIVERY.md)，资源许可见 [第三方说明](../THIRD_PARTY_NOTICES.md)。
