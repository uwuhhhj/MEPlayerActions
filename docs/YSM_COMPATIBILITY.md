# YSM 兼容范围（0.4.3）

[文档索引](README.md) · [客户端配置](CLIENT_CONFIG.md) · [模型同步与部署](MODEL_DELIVERY.md)

**MPA 0.4.3 已实现普通 YSM 文件夹的原版玩家动作、作者配置与本地组件适配；完整 OpenYSM 功能仍有边界。** 私人目录导入与服务器完整 BBModel 推送使用不同文件入口。下表描述当前源码能力，不把资源加载、单元检查或某个动作能播放等同于所有路径已通过实机验收。

## 内置模型与许可

客户端保留 CC0 的 `openysm_default` 初始模型，并增加原始酒狐（`wine_fox_01_taisho_maid`）、新春酒狐（`wine_fox_02_new_year`）和宇航员酒狐（`wine_fox_03_astronaut`）。后三者来自固定参考版本的原始 `spec:2` 文件夹，原文件与作者信息保持不变，使用 CC BY-NC-SA 4.0；模型资产仅供非商用，分发／改编须遵守署名与相同许可要求，见 [来源与完整许可](../THIRD_PARTY_NOTICES.md#openysm-wine-fox-model-assets-cc-by-nc-sa-40)。不自动替换已有选择，不把服务器 01/02 模型装入客户端。

内置原始文件不等于完整 OpenYSM 功能迁移；以下支持与验收边界仍适用，115 个主 clip／9 个组件的清单数字只指原 CC0 默认模型。新增酒狐模型的实机覆盖须另看对应验收记录。

## 当前支持与限制

| 项目 | 当前 MPA 行为与边界 |
| --- | --- |
| 主骨架与动画文件 | 导入有界 `spec:2` 普通文件夹的方块骨架、`main/extra/arm` 动画及独立 `fp_arm` 组件，保留位置／旋转／缩放、插值和循环。内置 CC0 默认模型主动画库为 **115 个 clip**（main 50、extra 8、arm 57），另有 **9 个组件**；组件动画单独计算。这些数量是清单与导入范围，不是实机通过数量。转换后格式 `65535` 且标记 `ysm_signed_cube` 的负尺寸方块保留有符号端点、原面、UV 与绕序；常规 BBModel 仍拒绝倒置边界，有限数值、尺寸和资源预算不变。 |
| 作者初始渲染比例 | 私人 YSM 玩家 BODY 和 GUI 使用共享 [YsmRenderScale](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmRenderScale.java)，保留上游 INITIAL 的 `S(height_scale, width_scale, height_scale)`，字段缺省各 `0.7`。矩阵先追加 Y 偏移 `0.01` 再追加 S，作用到顶点时是先缩放再加该偏移；外层原版／用户变换保留。身体的手持物品与装备继承同一父变换。服务器与常规 BBModel 保持单位作者比例；第一人称手臂、载具及投射物按各自路径，未追加这层 BODY 比例。作者比例只用于渲染，不改变真实碰撞。 |
| Molang、查询与物理 | 支持有类型的数值、字符串、向量、`null`、`??`、变量与有界控制语句，识别 `//` 与 `/* */` 注释并保留字符串内的同样字符，提供原版实体的 `query/q` 与 `ysm` 查询、骨骼查询和按实例保存状态的一阶／二阶物理。作者 `fn.*` 调用保留 `args[0]` 等参数的类型，每次调用隔离并恢复参数和临时变量，嵌套调用最多 16 层。查询未绑定时仍可能取 `0`；已明确绑定的 `null` 可使用作者默认表达式。函数与执行量有边界，不能据此假定外部模组查询已接入。 |
| 控制器与时间线 | 导入作者 `animation_controllers` 和 `functions/*.molang`，用文件名 `@event` 后缀注册事件；`name@event.molang` 保存函数与事件，`@event.molang` 仅注册事件。有界 Unicode 名称及含括号的事件函数名可保存，`fn.name` 调用仍须符合安全标识符规则。每个 `AnimationPlayer` 实例首次采样执行 `player_init` 完整列表一次，`reset` 后重跑；每次采样执行 `player_update` 完整列表，各段脚本独立使用参数与临时变量。`player_update` 的 `args[0]` 是第一人称布尔值，读取原版所有者视角绑定，未绑定时按 `fp.arm` 组件判断；GUI 惰性预览为 `false`。内置控制器事件仅执行列表首段，以其返回值决定控制器行为。执行有界状态转换、`on_entry/on_exit`、控制器槽、动画权重、过渡和时间线脚本；各实例持有自己的变量、控制器和物理，GUI 使用独立实例。 |
| 原版动作、手持、装备与第一人称 | 从原版实体观察主副手、挥手、使用物品、骑乘、睡眠等状态，不要求被观看者安装模组。手中原版物品按模型可用定位骨骼绘制；没有服务器伪装时私人模型还可显示盔甲、装饰纹样、附魔光效、鞘翅和头戴物。已识别的服务器伪装玩家隐藏原版盔甲／披风／鞘翅，本人手动 CLIENT 覆盖也遵守该规则；私人 YSM 有可用手臂组件时绘制第一人称手臂，`fp_arm` 文件入口使用正式的 `fp.arm.*` 控制器前缀。保留原版物品使用路径，缺少组件时回退。没有定位骨骼的模型不猜测通用挂点。 |
| 作者 extra 入口与分类 | CLIENT 经典 J 轮盘读取 `extra_animation` 的作者顺序、名称、配置关联及 `extra_animation_classify`，支持分类返回。内置默认模型保留 `extra0`–`extra7` 八个作者入口；关联配置通过作者齿轮在右侧同屏显示，按动态多边形扇区、分类路径和页码浏览；中心的本地动作锁定与主页的选择后保持轮盘分别控制移动取消和菜单关闭。 |
| 动态表单与按模型保存 | 按作者 `config_forms` 生成 checkbox、range、radio。点击“应用并保存”后应用受限的变量／数学赋值脚本，按模型 ID 保存表单变量、radio 选择和皮肤；这些私人参数不发送服务器。保存不包含查询缓存、物理或控制器运行状态；涉及实时世界查询、FX 或外部模组调用的任意表单脚本不属于当前保存入口。 |
| 纹理、语言与皮肤 | 读取作者纹理列表和 `default_texture`，提供该模型的皮肤选择；读取 `lang` 并在作者配置与动作界面使用模型文本。默认/蓝色皮肤与红色蝴蝶结仍可设置。当前渲染选定基础 PNG，normal/specular 等 PBR 材质和任意模型的真实玩家皮肤替换尚未实现。 |
| GUI 与进入效果 | YSM 主页左侧显示当前使用的外观，卡片选择保持待选草稿，使用后才更新左侧；详情预览所选模型。左侧／详情采样当前原版玩家姿态及有类型查询，保持库存 GUI 第三人称上下文，采用上游库存预览尺度。卡片使用独立假人，将作者 `preview_animation` 提交到 `player.cap`，不叠加 idle 或主动作；作者背景／前景和禁止旋转参数用于卡片。OWNER／CARD 各有时钟、控制器、物理与作者变量，共享纹理图集；YSM GUI 使用 [NativeGuiRenderBackend](../client/src/main/java/com/simmc/meplayeractions/client/ui/NativeGuiRenderBackend.java) 接入 Minecraft／Fabric Special GUI 离屏渲染：RGBA8 颜色与 DEPTH32 深度附件、原生着色器、逐顶点深度、LEQUAL 测试并写深度；不再依赖面的平均深度决定遮挡。常规 BBModel 保留旧二维投影、取景及进入表现。原默认模型没有名为 `enter` 的专用动画，当前 YSM 不额外合成统一进入变换。0.4.2 的 UV、眼睛、旧 GUI 动画及首次显现检查属于历史基线；本轮已通过作者初始比例及原生 RGBA8／DEPTH32、LEQUAL 深度绘制的实际执行检查，并人工核对默认模型眼白、完整头部及三套酒狐画面。半透明片段仍按提交次序混合并写深度，不保证任意相交半透明面的排序。源码分支及提交计数不能代替实际离屏执行证明。GUI 手持状态只用于当前姿态，未迁移 GUI 手持物品几何绘制；GUI 采样不触发实际世界 FX，不等同于整个上游 GUI 渲染链路。 |
| 声音与粒子 | 已导入 `sound_effects/particle_effects` 和本地 OGG，接入 `ysm.play_sound/stop_sound/stop_all_sounds/particle/abs_particle`，按实例／世界管理与释放，带资源及触发预算。0.4.2 的独立客户端历史实机已验证时间线触发的真实 OGG 流打开、循环播放、原版包声音及真实粒子；停播、重载和重置只清理实例所有的效果，并恢复原私人外观。未提供的自定义粒子定义及外部模组效果不能视为已经支持。 |
| 载具与投射物组件 | 读取 `files.vehicles/projectiles`；普通文件夹中的旧 `files.arrow` 仅兼容为 `minecraft:arrow`，现代 `projectiles` 定义优先，不额外映射 `spectral_arrow`，也不表示支持所有旧二进制 YSM 格式。按实体 ID／标签匹配并在私人模型使用者关联的原版实体上绘制独立组件。组件的空间、运动与环境查询读取实际组件实体；缺少玩家／生物能力时提供有类型的 `null` 与可用性标记，不借用模型所有者的玩家输入。默认导入 arm、fp_arm、箭、三叉戟、浮标、烟花、船、箱船和矿车，共 9 个组件。该私人组件路径不把模型选择或组件资产同步给其他玩家，也不等同于所有模组实体替换。 |

`animation_time_update`、`start_delay`、`loop_delay` 仍是拒绝导入的未支持字段；`blend_weight` 已有支持。普通 BBModel 与 YSM 的文件路径、资源大小、骨骼／方块和脚本预算继续适用。

转换后的普通 YSM（内部格式 `65535`）允许同一动画时间线事件最多 **64 条独立脚本**，按作者顺序保留，该事件所有脚本的 UTF-8 合计最多 **32 KiB**；这不扩大生命周期／函数事件的脚本数量限制。有限的作者动画时长、关键帧和事件时间最多 **10000 秒**。显式 `animation_length` 原样保留，不用后面的关键帧或事件扩展尾部；未提供该字段时保留上游的无限时长语义，不从最后一个关键帧推断有限时长。上述边界只用于转换后的 YSM，常规 BBModel 仍保留每个时间线事件 **32 条脚本**、有限时长 **3600 秒**及原有时间范围限制；**8 MiB 输入预算**与其他资源、几何和执行预算不变。源码入口为 [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java) 和 [BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java)。这些是导入语义与安全边界，不表示新增实机或单元测试已通过。

作者 `ysm.sync` 仅以最多 16 个有限数值参数，在当前 `AnimationPlayer` 内本地分发其私有 `@sync` 事件完整列表；各段脚本的参数／临时变量独立，调用深度最多 16 层并共享执行预算。此路径没有 C2S 消息，不传播至其他玩家，也不接入 OpenYSM 原网络协议。`initialVariables` 在惰性上下文求作者默认值，原版、FX 与同步回调不产生实际效果；`defer` 本轮未接入。本地分发、作者函数和生命周期事件已通过直接执行的单元回归；这些证明不等同于全部作者模型的实机验证或 OpenYSM 网络同步。

外部模组专用动画族与接口（如 tac、carryon、parcool、swem、slashblade、tlm、immersive_melodies、irons_spell_books）没有接入；清单保留这些名称不表示它们的动作文件或运行时已迁移。**加密 `.ysm`、OpenYSM 原网络／缓存协议及网格蒙皮绑定未实现。** 26.x 和未审计 fork 也需要另行适配验证。

`ysm.in_shield_block_cooldown` 的 5 tick 窗口从带明确玩家身份的真实成功格挡事件起算。当前原版 1.21.11 没有已接入的被观看者事件入口，`ysm.in_shield_block_cooldown_available` 因而为 `false`；持盾、挥手、物品冷却或本机按键都不能代替这个事件。其他原版实体查询照常读取，不要求被观看者安装模组。

## 持物动画的上游迁移

本轮持物路径按固定 OpenYSM 源码迁移格式校验、主副手判定及重复请求处理，并适配 Minecraft 1.21.11 的实体和物品栈 API。此前强制 `LOOP` 会把作者的 `HOLD`（停留最后一帧）覆盖成循环；现在符合上游格式规则的持物动画使用作者的 `HOLD`、`ONCE` 或 `LOOP`，不统一强制循环。

[AnimationFormatValidator](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/AnimationFormatValidator.java) 与 [IAnimationPredicate.playAnimationWithValid](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/IAnimationPredicate.java) 检查的是 **YSM 内部格式版本**：版本至少为 `19` 时保留作者循环设置；旧版本的主动画程序集也保留作者设置，旧版本非主程序集才使用判定器的兼容循环参数。该规则用于持物、使用和挥动选择；只有动画名称的旧目录保留兼容回退。普通文件夹在上游 [YSMFolderDeserializer](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java) 中使用 `65535`；`ysm.json` 的 `spec:2` 是清单结构版本，不能据此当成旧内部格式 `2`。

[主手](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/predicate/MainHandHoldPredicate.java)／[副手](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/predicate/OffHandHoldPredicate.java) 的物品比较规则也保留：上一份跟踪的真实物品栈当前已经受损时，只比较物品类型；否则比较完整栈状态。适配层按上游保留前次真实 `ItemStack` 引用；损伤判定读取该栈的当前状态，因此首次从无损变为受损也遵守该规则。完整栈比较调用 `ItemStack.areEqual`，包含数量与组件，不以哈希代替栈相等，也不把不可变副本代替原版跟踪语义。每只手仅保留有界的当前状态，世界／实例重置时清理原版栈引用；其他采样值仍取当前原版状态。[AnimationControllerInstance](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java) 的相同动画／循环请求不重启规则由共享 `HandPlayback` 实现，身体和第一人称手臂使用同一持物请求时钟；有效请求变化或上游物品判定要求重设时才重新开始，挥动／使用状态仍读取当前原版实体。组件的实体绑定和状态采样由 MPA 的 Minecraft 适配层提供，不要求被观看者安装模组或握手。

产品入口为 [AnimationFormatValidator](../client/src/main/java/com/simmc/meplayeractions/client/AnimationFormatValidator.java)、[VanillaYsmAnimations](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmAnimations.java) 及 [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) 的原版状态适配。主迁移源仍为上述固定版本；[ModernYSM 的 `1.20.1-forge` 分支固定版本 `a515d44686af77155a311b2a592327ca5d45a658`](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658) 仅作二次源码核对，该项目已停止维护，未成为新的运行时依赖。

这是持物判定与播放请求路径的迁移，不代表整个 OpenYSM 运行时、外部模组兼容或网络协议已经复制完成。源码及定向单元检查与当前模型的实机证明分开记录，新增模型和本轮修正的实际验收以 0.4.3 验证报告为准。

## 私人配置与验证边界

在玩家模型主页的 CLIENT 页进入模型详情和“作者配置 / 皮肤…”，或在 J 轮盘点击关联作者齿轮，同屏显示对应表单。皮肤切换立即保存；独立作者配置页的草稿需“应用并保存”，轮盘内的关联控件按交互执行脚本并保存。“重置模型配置”只清除此模型保存的皮肤、表单变量和 radio 选择，不改变私人启用、缩放或位置。

选择、缩放、位置及各模型配置保存在当前实例的 `config/meplayeractions-client.json`。这些操作只影响自己在本机看到的私人外观，不修改服务器姿态、权限或其他观看者的模型。新本人服务器伪装实例自动 SERVER，暂停私人显示和本地编辑／动作但保留文件；手动切到 CLIENT 可恢复本地操作，同一实例内记忆选择，私人显示仍等待服务器绑定就绪。解除伪装恢复原私人选择。SERVER 动作仍需授权绑定与接管就绪。

### 0.4.2 历史验证基线

0.4.2 通过 261 项服务端及 277 项客户端单元测试。独立客户端构建（SHA-256 前 12 位 `1dc542e07678`）在无服务器插件环境完成 18 个阶段、127 项实机检查，覆盖作者 GUI 预览、表单引起的真实几何变化与保存恢复、第一人称手臂 GPU 绘制、实际船／箭实体组件，以及声音／粒子的播放、停播和资源释放；私人参数及效果不发送服务器动作请求，验收后恢复原外观并清理夹具。

同一客户端构建在混合观众测试中通过 376 项接管检查，未安装 MPA 模组的独立客户端通过 94 项检查。两套服务器模型的双手持物、挥动及盾牌格挡读取真实原版实体，不要求被观看者握手；还覆盖资源推送、缓存重连、资源包重载、私人外观不影响他人及取消伪装／换模。完整报告及截图随发布的 tests ZIP 提供。

作者函数／事件与本地 `ysm.sync` 的直接执行证明来自单元回归。上述完整结果属于 0.4.2，不计作 0.4.3 本轮重跑，也不代表所有 OpenYSM 功能或每个作者模型均已验证。清单中的 115 个主 clip、9 个组件和八个 extra 入口仍是静态资源范围，不是逐项实机通过数量。

### 0.4.3 本轮验证范围

本版采用显式 `interactions` 验证，关联客户端 SHA-256 `984b5ec2f2394ce1ee0c01e034db2254532f3e358b8dece5d015b0756f62266d`。本轮 78 项定向客户端单元测试通过，失败、错误和跳过均为零。实机 A 为 14 阶段、53 个必需门、73 项检查；无 MPA 模组的观众 B 为 11 阶段、16 个必需门、38 项检查；独立客户端为 1 阶段、20 个必需门、25 项检查，均通过。

这些检查覆盖新的 J 入口、参考图库／多边形轮盘、同屏作者表单、来源与页码记忆、服务器默认及手动 CLIENT 覆盖、取消伪装恢复私人选择和装备隐藏，也验证作者 INITIAL 比例以及原生 GUI RGBA8／DEPTH32、LEQUAL／深度写入的真实执行。另已人工查看 A 的默认模型眼白和完整头部，以及独立客户端默认／三套酒狐相关的七张主页或世界截图，画面正常；这不等于任意半透明面、全部作者动画或 GUI 手持物品几何均已验证。

服务端 261 项及 Python 工具 16 项历史结果已按严格源码、资源及行为字节一致性证明复用，未重跑；服务端允许的差异限于版本及构建元数据，报告明确标记 `testsRerun=false`。正式打包仍校验这些证明与当前源码／JAR。方法清单及实机证据随验证 JSON 和 tests ZIP 提供，不把 0.4.2 的全部动画／GUI／FX 场景计作本轮重跑。

模型完全加载失败时，查看本地加载错误或 `/mpaclient status`。模型能显示但眼睛、附件或动作与参考项目不同，应检查是否使用了未接入查询、外部模组动画族、缺少定位骨骼或未支持字段。

## 对照源码与参考版本

仓库／源码包可查看 [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[YsmModelProfile](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmModelProfile.java) 的导入与元数据；[Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java)、[YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java) 的脚本／物理／控制器；[VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) 的实际原版输入。

用户界面与保存见 [ModelActionMenu](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelActionMenu.java)、[ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[ModelConfigScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigScreen.java)、[ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java)。独立渲染与效果见 [YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java)、[YsmItemRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java)、[YsmEquipmentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java)、[ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java)。代码链接用于源码包，安装包以本页说明为主。

本表对照 [IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated) 固定提交 `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`：

- [动画文件合并与默认继承](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/model/ModelAssemblyFactory.java#L57)。
- [主页左侧使用原版玩家库存预览](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L549)，[卡片假人使用作者 preview_animation](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L450)与 [ModelButton / 卡片相机](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ModelButton.java#L181)。本项目入口为 [NativeGuiPreviewCamera](../client/src/main/java/com/simmc/meplayeractions/client/ui/NativeGuiPreviewCamera.java)、[PreviewScene](../client/src/main/java/com/simmc/meplayeractions/client/ui/PreviewScene.java) 与 [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)。
- [动态配置表单](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/rip/ysm/gui/ModelSettingsScreen.java#L139)、[YSM 查询／函数](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L79)。
- [控制器 on_entry](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerRuntime.java#L247)、[声音关键帧](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/event/SoundKeyFrameExecutor.java#L23)。

ME／CE 继续生成、合并、保护并下发原版资源包；MPA 同步服务器完整原模型及动作状态，不要求额外离线资源包合并或强制安装 OpenYSM。具体部署见 [模型同步与部署](MODEL_DELIVERY.md)，资源许可见 [第三方说明](../THIRD_PARTY_NOTICES.md)。
