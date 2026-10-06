# YSM 功能与来源（0.5.1）

[文档索引](README.md) · [客户端使用与配置](CLIENT_CONFIG.md) · [同步协议](CLIENT_PROTOCOL.md) · [开发导航](../CONTRIBUTING.md)

本页列出当前代码支持的原版 Minecraft、私人本地功能与私人模型多人同步。私人模型可独立使用；分享须玩家显式开启、服务器许可和观看者安装模组。游戏不自动发送缺同步插件／不支持私人多人同步提示，状态由设置或 `/mpaclient status` 主动查看。

支持矩阵表示已经接入的格式、执行入口和用户功能，不代表其中每条执行语义都已与上游一致。已确认的实现差异和用户已确认的实施规则见下方 [2026-10-06 核对记录](#2026-10-06-核对记录)；本页供开发和排查索引使用，不在游戏内反复弹出兼容提示。

保留三条独立路径：本地私人模型仅自己观看；显式上传并获服务器许可的私人模型由模组观看者本地渲染、原版观看者仍看原版玩家；服务器伪装由模型引擎向原版观看者呈现，模组观看者接管本地渲染。私人分享不转换成 ME 伪装，原版位置同步仍由 Minecraft 负责。

## 来源与许可

| 固定参考 | 提交 | 参考内容 |
| --- | --- | --- |
| [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated/tree/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85) | `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85` | 原版动作、模型、组件、图库、经典轮盘、作者资源 |
| [Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher/tree/b1230a431900a286d2cca198072df7fb43c490b4) | `b1230a431900a286d2cca198072df7fb43c490b4` | 表达式、公开模型解码、动态骨骼／物理、原版状态、表单语义 |
| [ModernYSM](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658) | `a515d44686af77155a311b2a592327ca5d45a658` | 早期查询、函数、生命周期交叉核对 |

迁移软件沿各自 MIT 许可；模型及动画资源遵守各自许可，见 [第三方说明](../THIRD_PARTY_NOTICES.md)。新增 BBModel 动作预设保留 Mojang／Microsoft Bedrock 来源及 splatty 的 CC BY 署名，不将这些资源重新标为 MIT。内置 CC0 默认及原始 Alex／Steve 模型；三套酒狐的 77 个原始文件保留作者资源，采用 CC BY-NC-SA 4.0，仅供非商用，改编／分发须署名并使用相同许可。服务器 `ysm_01_jk`／`ysm_02_jk` 不内置；玩家皮肤替换只识别可信原始 Alex／Steve 内容，不能以任意文件名冒充。

## 支持矩阵

| 功能 | 当前实现 | 主要源码 |
| --- | --- | --- |
| 导入与解码 | `spec:2` 文件夹、完整 ZIP／ZIP 容器 `.ysm`、公开自包含二进制 `.ysm` v1–32、本地 `.bbmodel`；沿来源 CityHash／MT／XChaCha／Zstd 解码后转换完整资产，逐文件校验路径与预算 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java)、[NativeModelBundle](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeModelBundle.java) |
| 几何与材质 | 作者骨骼、方向四边面、负尺寸、UV、材质索引、geometry identifier 与作者比例；PNG／BMP／JPEG／WebP、原生 RGBA 按实际格式解码。本地／私人 BBModel 复用 Sparkle 完整导入与装配源码；多项 geometry 选择沿固定来源 | [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java) |
| 表达式 | 字符串、结构、动态骨骼句柄、数组、空值回退、实体子上下文 `->`、`this`、函数参数与来源数学规则；变量／物理／事件按实例隔离。未知原生查询函数或参数数量无效时整条公式按来源降为 `0` 并诊断，危险宿主调用与超预算仍拒绝；原生控制器条件的非法语法沿实际来源回退为 0，普通 BBModel 条件校验保持原规则 | [Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java) |
| 动画与控制器 | main／arm／extra、作者状态转换与进出脚本、时间线、生命周期、动态骨骼、一／二阶物理、槽级 `ysm.defer` 与初始化顺序；持物保留作者 ONCE／LOOP／HOLD。`anim_time_update` 及兼容别名、`start_delay`、`loop_delay` 在三份固定来源均无实现，按 [Minecraft 官方动画定义](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/actor_animation.v1.8.0?view=minecraft-bedrock-stable) 补齐，不新增全局配置开关 | [YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[YsmAnimationClock](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationClock.java) |
| 原版状态与查询 | 移动、潜行、睡眠、骑乘、攀梯、飞行／鞘翅、主副手使用／挥手／物品变化、四个盔甲槽、真实输入脉冲；远程读取原版实体，被观看者无需模组；投射物读取实际类型／所有者／状态 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java)、[VanillaYsmAnimations](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmAnimations.java) |
| 渲染与组件 | BODY 比例与持物 locator 共用骨骼变换；原生左右手遮罩与第一人称坐标沿来源，普通持物保留游戏变换；载具／投射物、额外持物 locator、乘员 locator、肩膀鹦鹉读取真实原版状态，不改变碰撞或权限 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)、[YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java) |
| 声音与粒子 | 作者本地 OGG 流式播放、原版资源包声音、真实粒子；提供实例／控制器作用域的停止调用，重载／实例结束释放效果，执行频率与存活数量有界。控制器／播放槽退出沿来源自动清理，按本体／组件实例隔离管理器 | [YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java) |
| 预览与图库 | OWNER 当前玩家、SELECTED 左侧草稿假人、CARD 卡片假人有独立时钟／控制器／物理／查询；假人不继承人物库存，OWNER 持物沿作者骨骼提交原生深度渲染。点击卡片预览草稿，“使用模型”才改变世界；保留作者卡片尺寸、入场／预览、拖动方向、目录与递归搜索。经典 420 宽面板整体居中，固定卡片与容器不再分别缩放；小 GUI 缩减行列 | [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[ModelGalleryIndex](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelGalleryIndex.java)、[GalleryPanelLayout](../client/src/main/java/com/simmc/meplayeractions/client/ui/GalleryPanelLayout.java) |
| 表单与皮肤 | checkbox／range／radio 的 read／write 完整脚本、步长／负步长、选中规则、作者函数、嵌套语言和提示；读值不改世界，提交原子保存作者变量／radio／纹理，实时物理与查询缓存不保存 | [ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[AuthorFormWidgets](../client/src/main/java/com/simmc/meplayeractions/client/ui/AuthorFormWidgets.java) |
| 轮盘与显示 | J 居中经典轮盘，作者顺序／分类／分页、滚轮提示、关联配置齿轮、解除模块与统一主页；CLIENT／SERVER 显式切换，玩家／装备／伪装三显隐独立，渲染总开关在设置；SERVER 动作连接不依赖渲染；两来源共用经典图库，私人云朵上传与服务器授权模型选择保持独立 | [AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java)、[PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java) |
| 私人多人同步 | 独立 `meplayeractions:private` v1；显式发布，服务器验证与授权分发，观看者校验 hash／纹理、ACK 与续租；同步皮肤、作者变量／radio／roaming、extra 动作与原生 sync 事件。服务器伪装优先，手动私人覆盖仅影响本人 | [PrivateModelSyncClient](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) |

普通 BBModel／服务器表达式保持原上下文；原生 YSM 按格式启用上述语义。缺失查询可能取 `0`，显式 `null` 可使用作者默认表达式；诊断仅在实际读取发生后记名称、次数与实际默认值。作者数字 roaming 每模型最多 64 项、名称最多 32 字符，跟踪脏变更并合并 profile；逐帧位置、物理与查询缓存留本机。

原生 YSM 头部查询沿固定来源：`ysm.head_pitch` 取原版俯仰角负值，`ysm.head_yaw` 先环绕到 `[-180,180)`、限制在 `[-85,85]` 后取负；`query.head_x_rotation` 对应 yaw，`query.head_y_rotation` 对应 pitch。自动头部跟随仍接收原版角度，与脚本查询分离，使作者的头发防穿模补偿按原规则执行。

## 2026-10-06 核对记录

本轮以表中固定提交交叉核对源码。以下区分已经调整的代码、本轮源码迁移结果和用户已确认的实施规则；未进行新一轮游戏场景复验，不把源码结论写成实机验收结论。各项差异有独立触发条件，不能将它们全部认定为本次“向上瞬移”的原因。

### 本轮已调整

| 项目 | 源码与处理 | 验收说明 |
| --- | --- | --- |
| 头发防穿模相关头查询 | 提交 `83ddb07` 已先修正原生头角度符号与 yaw 范围；[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java)。自动骨骼跟随与作者查询保持分离。 | 沿上游查询规则修复；本轮不重新声明模型画面验收。 |
| 私人／服务器接管身体位置 | [NativePlayerPresentation](../client/src/main/java/com/simmc/meplayeractions/client/render/NativePlayerPresentation.java) 从真实 world `entityRenderStates` 读取原版位置、renderer offset 和对应 delta；[ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) 使用同一 world camera。查询 origin 保持原版 state.xyz，不叠加外观 offset；第一人称无身体 state 时只生成本人当帧 fallback，不能沿用旧远端快照。 | 对齐原版玩家的位置呈现路径；不再自行用另一组 tick 字段重建身体位置。 |
| 服务器时间线 | [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) 中的动画时间线与原版身体帧入口 分工：服务器时间线驱动动画，不参与 XYZ 插值或位置更新；私人分享不新增实时位置广播。 | 动画时钟与原版位置帧分离；待用户安排后续打包与画面验收。 |
| 私人观看者 flying | [PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) 及状态刷新读取服务器 `Player.isFlying()`；[PrivateModelSyncClient](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java) 只消费服务器状态，旧包没有字段时取 false。玩家上传不能自行伪造该权威标志。 | 只补原版远端实体未提供的展示状态，不修改移动或飞行权限。 |

### 本轮源码迁移结果

下面沿初始审计的优先级索引逐项记录当前迁移。来源链接固定到实际核对提交；MPA 链接直接指向当前实现，避免源码行号移动后误指其他函数。单元、宿主 classfile 核对与实机验收分别记录。

| 初始优先级／核对项 | 当前实现 | 固定上游证据 | 当前处理 | 状态 |
| --- | --- | --- | --- | --- |
| P1 · 无控制器接管时的回位 | [AnimationPlayer / NativeYsmAnimationProcessor](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmAnimationProcessor.java) | [OpenYSM AnimationProcessor:104–160](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/processor/AnimationProcessor.java#L104-L160) | 无人接管的最终骨骼通道沿上游保存最后解析姿态并在 3 tick 内回位；仍有其他槽接管的通道保持该槽输出。 | 定向检查通过 |
| P1 · 新播放槽的起始快照 | [NativeYsmAnimationProcessor](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmAnimationProcessor.java) | [OpenYSM BoneAnimationQueue:68](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/keyframe/BoneAnimationQueue.java#L68) | 每个播放从已经解析的最终骨骼快照建立固定开始值；混合 ONCE／LOOP 槽按独立通道处理，结束一个槽不会重启其他槽过渡。 | 定向检查通过 |
| P1 · builtin predicate 的缩放过渡 | [NativeYsmAnimationProcessor](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmAnimationProcessor.java) | [OpenYSM predicate 创建特殊缩放控制器](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L58)、[OpenYSM 起始 scale 处理](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java#L251-L255) | 沿来源的 predicate TransformProvider 与 TransitionPoint 处理 scale 满起始进度、位置／旋转过渡和作者权重；作者状态控制器保持其自身规则。 | 定向检查通过 |
| P1 · 带初始旋转的骨骼混合 | [AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java) | [OpenYSM predicate 旋转混合](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L236-L239)、[OpenYSM controller 旋转混合](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerRuntime.java#L506) | 原生 nlerpEulerAngles 将骨骼 initial rotation 加入起止角，混合后扣回；普通 BBModel 保持既有上下文。 | 定向检查通过 |
| P1 · 控制器／播放退出时停止声音 | [YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) | [OpenYSM markDirty](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L198)、[OpenYSM stopSound](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java#L389) | 控制器状态切换、播放循环／退出／reset 沿来源释放各自声音管理器；全局声音独立。本体和同一玩家的第一人称手臂不再复用并互相关闭效果管理器。 | 定向检查通过 |
| P1 · merge_multiline_expr | [NativeYsmScriptArrays](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmScriptArrays.java) | [OpenYSM 属性读取](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java#L201)、[OpenYSM 合并脚本](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L841-L859)；[Sparkle 合并脚本](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bundle/AnimationMapper.java#L210-L228) | 二进制、文件夹、手臂和组件均传递属性；时间线及控制器 entry／exit 数组按来源用换行连接为一个程序，保留跨行语句与整体 return。 | 定向检查通过 |
| P1 · 原版姓名／着火／阴影提交 | [LivingEntityGeometryVisibilityMixin](../client/src/main/java/com/simmc/meplayeractions/client/mixin/LivingEntityGeometryVisibilityMixin.java) | [OpenYSM AvatarRenderer.submit:29](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/fabric/src/main/java/com/elfmcys/yesstevemodel/fabric/mixin/client/PlayerRendererMixin.java#L29)、[OpenYSM 姓名提交](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L178) | 只抑制玩家身体及特征图层，继续执行原版管理器与 renderer 基类的姓名／计分牌、火焰及阴影提交。三层显隐独立。 | 定向检查通过 |
| P2 · 隐身与发光轮廓 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) | [OpenYSM 可见性传入](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L159)、[OpenYSM outline 选择](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/IGeoRenderer.java#L56-L66) | 原生模型使用当前真实 state 的 outlineColor 和来源轮廓分支；原版隐身可见性继续参与模型提交。 | 定向检查通过 |
| P2 · 组件与装备使用同一原版帧 | [NativePlayerPresentation](../client/src/main/java/com/simmc/meplayeractions/client/render/NativePlayerPresentation.java)、[YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java) | [OpenYSM 向图层传递 state](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L185)、[OpenYSM 图层 state](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L35) | 载具、投射物、钓线和装备贯通实际 world state、逐实体 delta、冻结相机、光照及原版 renderer offset；查询使用未叠加外观偏移的原生 origin。 | 定向检查通过 |
| P2 · 组件 query.life_time | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) | [OpenYSM 实例时钟](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/QueryBinding.java#L56) | 去除实体年龄覆盖，原生查询保持模型实例 seek time；实体自身 age 仍供需要它的其他状态使用。 | 定向检查通过 |
| P2 · ysm.entity_type 名称 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) | [OpenYSM 类型名称规则](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L315-L328) | 原生 player／maid 沿上游别名；其余 living entity 返回完整 namespace:identifier，普通 BBModel 上下文保持原行为。 | 定向检查通过 |
| P2 · 无效 axis 的空值回退 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) | [OpenYSM position](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/Position.java#L23)、[OpenYSM position_delta](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/PositionDelta.java#L22)、[OpenYSM rotation_to_camera](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/RotationToCamera.java#L14) | 原生 position、position_delta 和 rotation_to_camera 对越界轴返回 null，保留作者 ?? 回退；普通 BBModel 原校验不变。 | 定向检查通过 |
| P2 · hand_render／armor 槽位边界 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java) | [OpenYSM 手条件](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/functions/ctrl/HandRenderFunction.java#L58-L67)、[OpenYSM 盔甲条件](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/functions/ctrl/Armor.java#L30-L38) | 按来源保留手条件的 false 与 armor 的 null，非法槽位不会被统一为同一个布尔值。 | 定向检查通过 |
| P2 · 私人观看者的额外原版状态 | [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) | [OpenYSM 状态字段](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/network/message/S2CSyncPlayerStatePacket.java#L29-L45)、[OpenYSM 状态读取](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/network/message/S2CSyncPlayerStatePacket.java#L245-L270)、[OpenYSM 远端 food 缓存](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L330) | 用户明确不新增 food／经验／完整 effects／按键输入同步；远端复用原版字段或既有缺省，例如 food=20。本人与观看者因此可能有不同模型表现。 | 已接受，规则 A |
| P2 · 独立 BBModel 扩展导入 | [NativeBbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeBbModel.java) | [Sparkle groups](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L214-L218)、[Sparkle mesh／locator](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L314-L365)、[Sparkle 数值 Bezier](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L764-L811)、[Sparkle 标准 controllers](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L1010-L1033) | 复用完整 Sparkle BB 解析／转换及本地装配来源链，包含 groups、mesh、locator／null_object、数值 Bezier、标准 controllers、贴图、时间线与来源动作预设；本地与私人被动接收共用入口。旧服务器蓝图解析隔离。 | 定向检查通过 |
| P2 · render_layers_first | [NativeRenderPhases](../client/src/main/java/com/simmc/meplayeractions/client/render/NativeRenderPhases.java) | [OpenYSM 图层顺序](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L160-L170)、[OpenYSM 持物图层刷新](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L45) | 作者属性贯通到两组原生延迟命令队列，组末刷新缓冲；按属性切换模型／附件提交顺序。普通 BBModel 队列保留。 | 定向检查通过 |
| P3 · all_cutout／cube culling | [NativeYsmFile](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmFile.java) | [OpenYSM forceCull](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L344)、[OpenYSM cube.cullable](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L408-L415) | 保留 all_cutout 元数据。已核对固定来源 CPU drawBones 同样不消费 cube.cullable，本项目沿该 CPU 行为，不改成 alpha cutout，也不额外引入 GPU 管线。 | 与固定来源 CPU 一致 |
| P3 · native locator 的新增回退 | [YsmItemRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java)、[YsmEquipmentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java) | [OpenYSM 手链有条件绘制](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L45-L63)、[OpenYSM 作者 head 链](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerArmorLayer.java#L58) | 原生绘制采用来源 HandLocator／Head／ElytraLocator 链和变换，移除 MPA Hand／HeadLocator 回退；上游导入时自己的挂点补全仍保留。普通服务器 BBModel 单独兼容。 | 定向检查通过 |

本轮独立复核另补齐两条执行链：[YsmNativeInputState](../client/src/main/java/com/simmc/meplayeractions/client/YsmNativeInputState.java) 沿 [Sparkle InputStateKey](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/input/InputStateKey.java) 优先读取原版挥手 ticks 和正值攻击插值，没有原版值时才使用已有本人脉冲；原生控制器按逐槽条件／状态与事件／权重、再逐骨骼旋转／位置／缩放的来源顺序求值，保留作者表达式副作用和 inactive 槽语义。两者不增加网络字段。

导入 BBModel 的本体使用来源 `ImportedVanillaPoseController` 补齐未被作者旋转接管的部位，排除 head；第一人称按来源只安装手臂控制器，不套用本体姿态回退。导入 BBModel 的 `VANILLA_EQUIPMENT` 持物链与原生 YSM 的 authored 链分别执行；来源内部的装备批次顺序贯通到原版延迟队列，两语义阶段各在末尾刷新，不逐玩家刷新。

### 本轮验证（源码 0.5.1）

实现提交：`8baeb31`（客户端来源迁移）、`56af02f`（服务端伴随贴图验证）；状态规则先由 `3fea59f` 记录。

客户端 `compileJava`／`compileTestJava` 通过，20 个受影响测试类共 205 项定向检查通过。首轮两项失败分别为作者 entry 脚本分号的测试预期错误、原版玩家专用 `shouldRenderFeatures` 绕过父 gate；前者保持来源数据并修正预期，后者补精确玩家 gate，只复跑这两个相关方法，未重复已通过的检查。服务端 `PrivateModelBundleTest` 12 项通过，包含伴随 PNG 的包内解析与预算校验。原始 3 个预设 JSON 及 README／CREDITS 共 5 文件与固定来源字节一致。

检查涵盖导入／私人被动解码、控制器顺序与通道合成、原版帧、附件变换、阶段队列及当前 Minecraft 命名 classfile 接口，不能替代所有模型的实机画面验收，也不是千人 TPS 压测。实际日志、XML、按用例合并的结果与源码哈希保存在 `build/validation-attempts-0.5.1/client-upstream-*`、`build/validation-0.5.1/client-upstream-migration/` 和 `build/validation-0.5.1/server-upstream-bbmodel/`。本轮按用户要求仅提交源码与文档，后续交付需从这些最新源码重新构建发布包。

### 用户已确认的实施规则（2026-10-06）

本轮决策已结束。此前确认的完整原版／本地功能与私人分享范围继续有效，原版没有同步给观看者的状态按下面 A 的明确例外处理。迁移以固定源码的实际执行路径为准，保留来源、许可及 Minecraft 1.21.11 的宿主接口适配；不自行增加模型作者未定义的兼容规则。

#### A · 原版未下发的状态不增加同步

**用户决策：**「A1和A2都不考虑，服务器如果本来就没有下发这些，那就参考食物值回退为 20即可，这些明确表示为不考虑同步。尊重原版逻辑。」

本地本人仍读取真实原版状态；远端复用原版已同步的实体字段，例如血量和最大血量。食物、经验、完整效果 ID／等级、移动输入等原版未提供给普通观看者的信息不新增 MPA 同步包、不要求发布者上报，也不从位移推测为等价输入。保留既有缺省值和原版远端字段，例如私人远端食物值 20。模型若依赖未同步字段，本人和观看者的表现可能不同，这是已接受的规则，不再列为待补的网络缺陷。

来源的 `ysm.input_vertical`／`ysm.input_horizontal` 是按实际位移计算的运动方向比例，沿上游保留；它们与真实按键输入 `ysm.xxa/yya/zza` 不同，不作为额外输入同步。

位置始终复用原版玩家呈现；授权、资源、作者配置和动作事件继续使用既有私人分享协议。此前已修正的服务器权威飞行标志属于既有展示协议，本轮不扩充其字段。

#### B · 复用迁移上游 BBModel 导入源码

**用户决策：**「不是完整推荐B1，需要了解上游项目源代码之后，不是新增，而是复用迁移完整上游代码。」

直接阅读并迁移 Sparkle 固定版本的 BBModel 解析、转换、数据对象与所需辅助源码，保留上游骨架、网格、挂点、数值曲线、控制器、贴图和事件导入行为；仅适配现有资源预算、宿主类型和本项目运行时入口。不得以自行实现一个有限转换器代替完整来源链。源文件对应关系与适配范围记录在第三方说明及本页迁移记录。

本地／私人 BBModel 使用这一来源导入链；服务器蓝图继续走原有模型引擎及普通 BBModel 上下文。ModelEngine 导入蓝图、生成原版资源包，CraftEngine 合并、托管和下发的分工不变。原生 YSM 和 BBModel 曲线各自沿来源，不把同名字段的不同执行路径混为一谈。

#### C · 复用迁移上游附件绘制源码

**用户决策：**「需要了解上游项目源代码之后，不是新增，而是复用迁移完整上游代码。」

按固定上游实际的手持、额外挂点、头部物品和鞘翅绘制入口迁移，保留作者可见链及变换。原生 YSM 不再使用 MPA 自行增加的 Hand 骨骼回退或 HeadLocator 优先级；缺失、隐藏挂点按来源执行。普通服务器 BBModel 保持其既有上下文，不能全局改写作者轴向或挂点规则。

BBModel 导入链自身执行的标准骨架／挂点补全属于上游导入行为，与绘制时凭空添加回退不同，按来源保留。原版玩家、装备与伪装的三个用户显隐控制继续独立；附件绘制不改变实际背包或装备。

### 不作为本项目特有缺陷的项目

本轮核对没有确认 checkbox 选中条件、radio 取整、range 步长／负区间的基本值语义与上游不同，不重复列为 UI bug。多项 geometry 的固定来源同样选第一项，不能据此宣称完整选择支持；公开原生 YSM 的 Bezier 路径在固定来源也回退为线性，blend_via_shortest_path 虽被读取但来源执行端未消费，两者不能单列为 MPA 遗漏。Sparkle 独立 BBModel 的数值曲线烘焙与公开原生 YSM 这条路径须分开讨论。

固定 Sparkle 的独立 BBModel 导入会保留控制器引用字符串，并把条目转换为运行时条件；它自身没有将 UUID 引用全部重写为动画名称，也不保留条目对象里的任意额外 blend 字段。本次完整复制其解析／转换行为，不另造转换规则。非法条件沿实际 `AnimationMapper.parse` 返回 `0`，缺省／空条件才使用默认值。 来源 `walkOutliner` 对根级独立元素会标记已引用，却不绑定骨骼，因此这种没有父组的元素可能丢失；本轮也保留其原行为，没有另造孤立元素回退。非 YSM 的 BBModel ZIP、Figura 与 Bedrock 包自动识别尚未接入本项目选择入口；复制的 `ZipModelSniffer` 辅助源码不代表这些入口已经启用。

三份固定来源的内建谓词 `TransformProviderRecord` 都持久保存通道结束进度，并按最小值更新；作者状态控制器的 `BoneBlendState` 则每次 getter 重置进度。源码的该缓存差别按原样保留，本轮不擅自改变结束策略；它与最终无人接管通道的全局回位不同。

被动授权分发、显式上传、观看者授权、预算、hash／ACK／租期校验是本项目三路径设计；与上游包格式或云端请求流程不同本身不是兼容 bug。保留这些限制，不转成客户端可任意指定服务器模型申请下载的接口。

## 输入条件与边界

| 条件 | 行为 |
| --- | --- |
| 本地输入与分享预算 | 本地 YSM 输入／展开／转换分别限 64 MiB；独立 BBModel 源文件 8 MiB，声明的目录内 PNG 伴随资源及转换后资产分别限 64 MiB；私人原始归档与展开仍限 8 MiB，原生 BBModel 转换派生资产另限 64 MiB，协商可降低，超出分享预算可继续本地使用；文件数、像素、几何、脚本分别有界，见 [导入预算详情](history/CLIENT_0_4_8_GALLERY_IMPORT.md#本地导入与私人分享) |
| 原生时间线 | 单事件最多 256 段／32 KiB UTF-8，保留作者顺序；普通 BBModel 及声音／粒子事件仍限 32 项 |
| AVIF | 未内置可分发解码器：必需 AVIF 材质明确报错，可选作者 AVIF 头像用占位图；WebP 使用首个完整图像帧 |
| 路径与密钥 | 拒绝链接、目录外资源、非自包含或需额外服务端密钥的 `.ysm` 缓存，不绕过授权 |
| 尚无实现入口 | glTF 蒙皮／`poly_mesh`、normal／specular PBR 着色器、其他模组专用联动、云端商店 |
| 网络互通 | 私人同步使用本项目可选协议，与原 OpenYSM／Sparkle 服务器协议不互通；其他服务器模型引擎可实现 [本项目协议](CLIENT_PROTOCOL.md) 复用客户端 |

具体格式条件不在游戏中循环提示。逐版本迁移、离线与实机验收记录见 [历史文档](history/README.md)；源码导航与构建流程见 [CONTRIBUTING](../CONTRIBUTING.md)。这些记录区分编译／单元／离线导入与实际游戏验证，不能以加载成功或历史测试替代所有模型画面与多人行为的验收。
