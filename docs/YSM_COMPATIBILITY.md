# YSM 功能与来源（0.5.1）

[文档索引](README.md) · [客户端使用与配置](CLIENT_CONFIG.md) · [同步协议](CLIENT_PROTOCOL.md) · [开发导航](../CONTRIBUTING.md)

本页列出当前代码支持的原版 Minecraft、私人本地功能与私人模型多人同步。私人模型可独立使用；分享须玩家显式开启、服务器许可和观看者安装模组。游戏不自动发送缺同步插件／不支持私人多人同步提示，状态由设置或 `/mpaclient status` 主动查看。

支持矩阵表示已经接入的格式、执行入口和用户功能，不代表其中每条执行语义都已与上游一致。已确认的实现差异和需要决策的范围见下方 [2026-10-06 核对记录](#2026-10-06-核对记录)；本页供开发和排查索引使用，不在游戏内反复弹出兼容提示。

保留三条独立路径：本地私人模型仅自己观看；显式上传并获服务器许可的私人模型由模组观看者本地渲染、原版观看者仍看原版玩家；服务器伪装由模型引擎向原版观看者呈现，模组观看者接管本地渲染。私人分享不转换成 ME 伪装，原版位置同步仍由 Minecraft 负责。

## 来源与许可

| 固定参考 | 提交 | 参考内容 |
| --- | --- | --- |
| [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated/tree/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85) | `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85` | 原版动作、模型、组件、图库、经典轮盘、作者资源 |
| [Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher/tree/b1230a431900a286d2cca198072df7fb43c490b4) | `b1230a431900a286d2cca198072df7fb43c490b4` | 表达式、公开模型解码、动态骨骼／物理、原版状态、表单语义 |
| [ModernYSM](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658) | `a515d44686af77155a311b2a592327ca5d45a658` | 早期查询、函数、生命周期交叉核对 |

软件采用 MIT；模型遵守各自许可，见 [第三方说明](../THIRD_PARTY_NOTICES.md)。内置 CC0 默认及原始 Alex／Steve 模型；三套酒狐的 77 个原始文件保留作者资源，采用 CC BY-NC-SA 4.0，仅供非商用，改编／分发须署名并使用相同许可。服务器 `ysm_01_jk`／`ysm_02_jk` 不内置；玩家皮肤替换只识别可信原始 Alex／Steve 内容，不能以任意文件名冒充。

## 支持矩阵

| 功能 | 当前实现 | 主要源码 |
| --- | --- | --- |
| 导入与解码 | `spec:2` 文件夹、完整 ZIP／ZIP 容器 `.ysm`、公开自包含二进制 `.ysm` v1–32、本地 `.bbmodel`；沿来源 CityHash／MT／XChaCha／Zstd 解码后转换完整资产，逐文件校验路径与预算 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java)、[NativeModelBundle](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeModelBundle.java) |
| 几何与材质 | 作者骨骼、方向四边面、负尺寸、UV、材质索引、geometry identifier 与作者比例；PNG／BMP／JPEG／WebP、原生 RGBA 按实际格式解码。完整多项 geometry 选择和独立 BBModel 扩展格式不由此行保证 | [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java) |
| 表达式 | 字符串、结构、动态骨骼句柄、数组、空值回退、实体子上下文 `->`、`this`、函数参数与来源数学规则；变量／物理／事件按实例隔离。未知原生查询函数或参数数量无效时整条公式按来源降为 `0` 并诊断，危险宿主调用、非法语法与超预算仍拒绝 | [Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java) |
| 动画与控制器 | main／arm／extra、作者状态转换与进出脚本、时间线、生命周期、动态骨骼、一／二阶物理、槽级 `ysm.defer` 与初始化顺序；持物保留作者 ONCE／LOOP／HOLD。`anim_time_update` 及兼容别名、`start_delay`、`loop_delay` 在三份固定来源均无实现，按 [Minecraft 官方动画定义](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/actor_animation.v1.8.0?view=minecraft-bedrock-stable) 补齐，不新增全局配置开关 | [YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[YsmAnimationClock](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationClock.java) |
| 原版状态与查询 | 移动、潜行、睡眠、骑乘、攀梯、飞行／鞘翅、主副手使用／挥手／物品变化、四个盔甲槽、真实输入脉冲；远程读取原版实体，被观看者无需模组；投射物读取实际类型／所有者／状态 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java)、[VanillaYsmAnimations](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmAnimations.java) |
| 渲染与组件 | BODY 比例与持物 locator 共用骨骼变换；原生左右手遮罩与第一人称坐标沿来源，普通持物保留游戏变换；载具／投射物、额外持物 locator、乘员 locator、肩膀鹦鹉读取真实原版状态，不改变碰撞或权限 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)、[YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java) |
| 声音与粒子 | 作者本地 OGG 流式播放、原版资源包声音、真实粒子；提供实例／控制器作用域的停止调用，重载／实例结束释放效果，执行频率与存活数量有界。控制器／播放槽自动退出清理仍有差异，见核对记录 | [YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java) |
| 预览与图库 | OWNER 当前玩家、SELECTED 左侧草稿假人、CARD 卡片假人有独立时钟／控制器／物理／查询；假人不继承人物库存，OWNER 持物沿作者骨骼提交原生深度渲染。点击卡片预览草稿，“使用模型”才改变世界；保留作者卡片尺寸、入场／预览、拖动方向、目录与递归搜索 | [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[ModelGalleryIndex](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelGalleryIndex.java) |
| 表单与皮肤 | checkbox／range／radio 的 read／write 完整脚本、步长／负步长、选中规则、作者函数、嵌套语言和提示；读值不改世界，提交原子保存作者变量／radio／纹理，实时物理与查询缓存不保存 | [ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[AuthorFormWidgets](../client/src/main/java/com/simmc/meplayeractions/client/ui/AuthorFormWidgets.java) |
| 轮盘与显示 | J 居中经典轮盘，作者顺序／分类／分页、滚轮提示、关联配置齿轮、解除模块与统一主页；CLIENT／SERVER 显式切换，玩家／装备／伪装三显隐独立，渲染总开关在设置；SERVER 动作连接不依赖渲染 | [AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java)、[PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java) |
| 私人多人同步 | 独立 `meplayeractions:private` v1；显式发布，服务器验证与授权分发，观看者校验 hash／纹理、ACK 与续租；同步皮肤、作者变量／radio／roaming、extra 动作与原生 sync 事件。服务器伪装优先，手动私人覆盖仅影响本人 | [PrivateModelSyncClient](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) |

普通 BBModel／服务器表达式保持原上下文；原生 YSM 按格式启用上述语义。缺失查询可能取 `0`，显式 `null` 可使用作者默认表达式；诊断仅在实际读取发生后记名称、次数与实际默认值。作者数字 roaming 每模型最多 64 项、名称最多 32 字符，跟踪脏变更并合并 profile；逐帧位置、物理与查询缓存留本机。

原生 YSM 头部查询沿固定来源：`ysm.head_pitch` 取原版俯仰角负值，`ysm.head_yaw` 先环绕到 `[-180,180)`、限制在 `[-85,85]` 后取负；`query.head_x_rotation` 对应 yaw，`query.head_y_rotation` 对应 pitch。自动头部跟随仍接收原版角度，与脚本查询分离，使作者的头发防穿模补偿按原规则执行。

## 2026-10-06 核对记录

本轮以表中固定提交交叉核对源码。以下区分已经调整的代码、确认仍存在的迁移差异和需要用户决定的范围；未进行新一轮游戏场景复验，不把源码结论写成实机验收结论。各项差异有独立触发条件，不能将它们全部认定为本次“向上瞬移”的原因。

### 本轮已调整

| 项目 | 源码与处理 | 验收说明 |
| --- | --- | --- |
| 头发防穿模相关头查询 | 提交 `83ddb07` 已先修正原生头角度符号与 yaw 范围；[AnimationPlayer:183](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L183)。自动骨骼跟随与作者查询保持分离。 | 沿上游查询规则修复；本轮不重新声明模型画面验收。 |
| 私人／服务器接管身体位置 | [NativePlayerPresentation](../client/src/main/java/com/simmc/meplayeractions/client/render/NativePlayerPresentation.java#L28) 从真实 world `entityRenderStates` 读取原版位置、renderer offset 和对应 delta；[ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L283) 使用同一 world camera。查询 origin 保持原版 state.xyz，不叠加外观 offset；第一人称无身体 state 时只生成本人当帧 fallback，不能沿用旧远端快照。 | 对齐原版玩家的位置呈现路径；不再自行用另一组 tick 字段重建身体位置。 |
| 服务器时间线 | [ClientRuntime:862](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java#L862) 与 [原版身体帧:1058](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java#L1058) 分工：服务器时间线驱动动画，不参与 XYZ 插值或位置更新；私人分享不新增实时位置广播。 | 动画时钟与原版位置帧分离；待用户安排后续打包与画面验收。 |
| 私人观看者 flying | [PrivateModelSyncService:286](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java#L286) 及状态刷新读取服务器 `Player.isFlying()`；[PrivateModelSyncClient:396](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java#L396) 只消费服务器状态，旧包没有字段时取 false。玩家上传不能自行伪造该权威标志。 | 只补原版远端实体未提供的展示状态，不修改移动或飞行权限。 |

### 确认待补的实现差异

优先级按动画连续性、原版呈现和导入影响排列；“无需决策”表示目标语义已能由固定来源确定，并不表示此项已经修复。MPA 行号为本轮源码核对入口，后续提交移动行号时以函数／类名继续索引。

| 优先级／待补项 | MPA 证据 | 固定上游证据 | 影响与处理方向 | 需用户决策 |
| --- | --- | --- | --- | --- |
| P1 · 无控制器接管时的回位 | [AnimationPlayer.java:283](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L283) | [OpenYSM AnimationProcessor:104–160](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/processor/AnimationProcessor.java#L104-L160) | MPA 每帧从空姿态开始合成；上游记录最终骨骼通道的最近接管状态，在无人接管后按全局 3 tick 复位。MPA 的单个控制器停止过渡不能替代这层最终通道复位，可能出现动作结束后的姿态跳变。 | 否，按来源补齐 |
| P1 · 新播放槽的起始快照 | [AnimationPlayer.java:311](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L311) | [OpenYSM BoneAnimationQueue:68](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/keyframe/BoneAnimationQueue.java#L68) | 新 Group 从 emptyPose 起步；上游从最终骨骼快照建立过渡起点。低优先级控制器仍在驱动同一骨骼时，新动作的起始姿态可能不同。 | 否，按来源补齐 |
| P1 · builtin predicate 的缩放过渡 | [AnimationPlayer.java:328](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L328)、[AnimationPlayer.java:351](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L351) | [OpenYSM predicate 创建特殊缩放控制器](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L58)、[OpenYSM 起始 scale 处理](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java#L251-L255) | MPA 对缩放统一做 Group 渐变；上游 builtin predicate 在起始过渡中对 scale 使用满进度，位置／旋转仍正常混合，且 TransitionPoint 的权重从 1 过渡到作者 blendWeight。作者用 scale 隐藏部件时可能额外出现缩小过程；仅按该类内置控制器规则补齐，保留作者 controller 的过渡。 | 否，按来源补齐 |
| P1 · 带初始旋转的骨骼混合 | [AnimationPlayer.java:362](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L362) | [OpenYSM predicate 旋转混合](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L236-L239)、[OpenYSM controller 旋转混合](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerRuntime.java#L506) | MPA 对动画增量直接做四元数插值，没有纳入骨骼 initial rotation；上游 nlerpEulerAngles 将初始旋转纳入混合。静态旋转非零的骨骼在切换动作时可能走不同旋转路径。 | 否，按来源补齐 |
| P1 · 控制器／播放退出时停止声音 | [YsmAnimationController.java:87](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java#L87)、[YsmAnimationController.java:259](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java#L259)、[YsmModelEffects.java:107](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java#L107) | [OpenYSM markDirty](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/PredicateBasedController.java#L198)、[OpenYSM stopSound](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java#L389) | MPA 有带作用域的 stop_sound／stop_all_sounds，但 discard／停止／reset 路径没有自动调用相应音频清理；作者没有手动 stop 时，旧动作声音可能继续播放。应按控制器及播放生命周期连接清理。 | 否，按来源补齐 |
| P1 · merge_multiline_expr | [YSMBinaryDeserializer.java:660](../client/src/main/java/com/simmc/meplayeractions/client/model/nativeysm/YSMBinaryDeserializer.java#L660)、[NativeYsmFile.java:226](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmFile.java#L226)、[YsmAnimationController.java:544](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java#L544)、[YsmFolderModel.java:762](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java#L762) | [OpenYSM 属性读取](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java#L201)、[OpenYSM 合并脚本](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L841-L859)；[Sparkle 合并脚本](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bundle/AnimationMapper.java#L210-L228) | 二进制读取了属性，转换及脚本编译没有把它贯通：数组仍逐条编译。开启属性的作者脚本应先按换行合成一个程序，否则跨段语法、return 和临时上下文可能不同。控制器进出脚本与时间线都要统一处理。 | 否，按来源补齐 |
| P1 · 原版姓名／着火／阴影提交 | [EntityRenderManagerMixin.java:18](../client/src/main/java/com/simmc/meplayeractions/client/mixin/EntityRenderManagerMixin.java#L18)、[ModelRenderer.java:458](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L458) | [OpenYSM AvatarRenderer.submit:29](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/fabric/src/main/java/com/elfmcys/yesstevemodel/fabric/mixin/client/PlayerRendererMixin.java#L29)、[OpenYSM 姓名提交](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L178) | MPA 在 EntityRenderManager 的总渲染入口 HEAD 取消原版提交，只另行提交模型及附件，缺少姓名、着火效果和阴影的对应恢复。上游在玩家 renderer 层替换身体，保留管理器流程并显式提交姓名。三层显隐不能一并取消这些附属呈现。 | 否，按原版／来源流程补齐 |
| P2 · 隐身与发光轮廓 | [ModelRenderer.java:330](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L330)、[ModelRenderer.java:352](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L352) | [OpenYSM 可见性传入](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L159)、[OpenYSM outline 选择](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/IGeoRenderer.java#L56-L66) | MPA 按 isInvisibleTo 过滤原生身体，没有与上游对应的 glowing outline 渲染分支；隐身与发光组合的轮廓显示需要补齐，不能把隐身实体一律当作无需提交。 | 否，按原版／来源补齐 |
| P2 · 组件与装备使用同一原版帧 | [YsmComponentRenderer.java:106](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java#L106)、[YsmComponentRenderer.java:140](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java#L140)、[YsmEquipmentRenderer.java:58](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java#L58) | [OpenYSM 向图层传递 state](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L185)、[OpenYSM 图层 state](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L35) | 本轮身体已复用真实 world render-state，组件位置仍另取 getLerpedPos／camera，装备仍 fresh updateRenderState。应在新版 Minecraft 渲染提取流程中统一 state、camera 和 tick delta，尤其包含冻结 tick 和挂载偏移；不能用本轮身体修复代表附件已全部对齐。 | 否，沿同一帧贯通 |
| P2 · 组件 query.life_time | [AnimationPlayer.java:192](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java#L192)、[YsmComponentRenderer.java:127](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java#L127)、[VanillaYsmQueries.java:77](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L77) | [OpenYSM 实例时钟](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/QueryBinding.java#L56) | 组件上下文把实例 lifetime 覆盖成 entity.age。上游该查询取模型实例 seek time，二者在重建组件实例、切换模型或迟加入观看时不同；应保留实体年龄与实例时钟各自的用途。 | 否，按来源补齐 |
| P2 · ysm.entity_type 名称 | [VanillaYsmQueries.java:94](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L94)、[VanillaYsmQueries.java:235](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L235) | [OpenYSM 类型名称规则](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L315-L328) | MPA 普遍 getPath 丢失 namespace；上游 player／maid 有特例，其余返回完整 identifier。组件条件使用 minecraft:zombie 等名称时可能不匹配，不能把所有实体简化为裸路径。 | 否，按来源补齐 |
| P2 · 无效 axis 的空值回退 | [VanillaYsmQueries.java:594](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L594)、[VanillaYsmQueries.java:613](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L613) | [OpenYSM position](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/Position.java#L23)、[OpenYSM position_delta](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/PositionDelta.java#L22)、[OpenYSM rotation_to_camera](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/molang/builtin/query/RotationToCamera.java#L14) | MPA 越界轴抛 IllegalArgumentException；上游返回 null。原生表达式使用空值合并或默认表达式时，应得到作者回退值，不能由异常改变整条执行。 | 否，按来源补齐 |
| P2 · hand_render／armor 槽位边界 | [VanillaYsmQueries.java:541](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L541)、[VanillaYsmQueries.java:575](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L575) | [OpenYSM 手条件](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/functions/ctrl/HandRenderFunction.java#L58-L67)、[OpenYSM 盔甲条件](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/functions/ctrl/Armor.java#L30-L38) | 上游手条件遇到无效／盔甲槽返回 0，armor 遇到无效／手槽返回 null；MPA 共用槽位解析和 boolean 条件，未保留这一区别。须核对非法槽位及上下文回退，不能将 false 和 null 统一处理。 | 否，按来源补齐 |
| P2 · 私人观看者的额外原版状态 | [ClientRuntime.java:930](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java#L930)、[VanillaYsmQueries.java:218](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L218)、[VanillaYsmQueries.java:252](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java#L252)、[PrivateModelSyncClient.java:41](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java#L41) | [OpenYSM 状态字段](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/network/message/S2CSyncPlayerStatePacket.java#L29-L45)、[OpenYSM 状态读取](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/network/message/S2CSyncPlayerStatePacket.java#L245-L270)、[OpenYSM 远端 food 缓存](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/YSMBinding.java#L330) | 私人远端 food 当前回退 20；等级和 xxa／yya／zza 读取远端原版实体，没有上游对应的状态缓存。血量／最大血量已有原版 metadata／属性同步，应复用；食物、经验与完整效果等级按此前迁移目标补齐。需选择的是展示输入意图的同步范围，不广播另一套 XYZ。 | 输入范围，决策 A |
| P2 · 独立 BBModel 扩展导入 | [BbModel.java:684](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java#L684)、[BbModel.java:696](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java#L696)、[BbModel.java:713](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java#L713)、[BbModel.java:945](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java#L945) | [Sparkle groups](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L214-L218)、[Sparkle mesh／locator](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L314-L365)、[Sparkle 数值 Bezier](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L764-L811)、[Sparkle 标准 controllers](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBToRawConverter.java#L1010-L1033) | MPA 拒绝独立 groups、mesh、locator／null_object、Bezier，只导入私有 ysm_animation_controllers；Sparkle 有独立转换入口，支持上述结构与数值曲线烘焙、标准 animation_controllers。此差异属于独立本地 BBModel 导入，不能用它证明公开二进制 YSM 的同名 Bezier 完整可用。 | 是，决策 B |
| P2 · render_layers_first | [NativeYsmFile.java:229](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmFile.java#L229)、[ModelRenderer.java:468](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L468) | [OpenYSM 图层顺序](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L160-L170)、[OpenYSM 持物图层刷新](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L45) | 属性已保留但当前提交固定为模型、手持、装备。作者要求先绘制图层的顺序没有贯通；需按新版提交队列验证实际绘制顺序及缓冲刷新，仅交换 Java 调用顺序未必足够。 | 否，按来源补齐 |
| P3 · all_cutout／cube culling | [NativeYsmFile.java:229](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmFile.java#L229)、[ModelRenderer.java:116](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java#L116) | [OpenYSM forceCull](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L344)、[OpenYSM cube.cullable](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L408-L415) | all_cutout 在固定来源中影响背面剔除标记，不能解释成整张贴图强制 alpha cutout。MPA 未消费此属性、统一 NoCull；固定上游 CPU 路径也未完整贯通 cullable，不能据此断言所有模型画面错误或直接承诺性能收益。应先明确并贯通目标剔除路径。 | 否，需实现端核对 |
| P3 · native locator 的新增回退 | [YsmItemRenderer.java:69](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java#L69)、[YsmEquipmentRenderer.java:144](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java#L144) | [OpenYSM 手链有条件绘制](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerItemInHandLayer.java#L45-L63)、[OpenYSM 作者 head 链](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/layer/CustomPlayerArmorLayer.java#L58) | MPA 无 HandLocator 时回退到 Hand，并优先使用 HeadLocator／Head；这些是本项目增加的兼容规则，并非完整迁移上游的证据。普通／服务器 BBModel 可能需要回退，原生 YSM 是否同样启用须确定。 | 是，决策 C |

### 待用户决定的范围

这里决定的是兼容范围和运行策略。已经明确要求的完整本地功能、私人分享，以及能从固定源码确定的修复目标仍然有效，无需逐项重新授权。以下为候选方案，尚未视为用户选择。

#### A · 私人分享是否同步“输入意图”

**问题是什么：** 模型不仅读取位置，还可能读取饥饿、经验、效果等级和按键方向。当前私人远端食物值回退为 20，经验没有上游对应缓存；作者若根据这些值控制表情、服装或动作，本人与观看者可能看到不同表现。食物、经验和作者需要的完整效果 ID／等级已经属于此前确认的迁移目标，不需要再次决定是否补齐。

1.21.11 的血量通过实体 DataTracker、最大血量通过被跟踪属性同步给观察者，应继续复用。食物和经验包发送给玩家本人；普通观察者的实体对象不能保证具有完整效果 ID／等级。这些字段应逐项选择真实服务器状态或已有原版同步，避免全部重复传送。核对入口为 `LivingEntity.getHealth()`、`EntityTrackerEntry.syncEntityData()`／被跟踪属性，以及 `ServerPlayerEntity` 的 `HealthUpdateS2CPacket`、`ExperienceBarUpdateS2CPacket`、效果包发送路径。

**真正需要选择的是输入意图：** `ysm.xxa/yya/zza` 表示来源维护的移动输入值。玩家按住前进但撞墙时，输入仍存在，实际位移却为零；不能用坐标差代替。这可能影响依赖输入的侧移、姿态或特殊飞行动作，普通位置同步与一般走路判定不因此改成另一套机制。

- **A1 · 支持展示输入同步（推荐）。** 核对目标服务器 API 能提供的输入；不足时，由获准发布私人模型的模组玩家受限上报。只对开启分享且需要该能力的模型／实例启用，变化及时发送，合并同值并限流；依赖无法可靠判定时优先保留兼容。观看者仍被动接收。输入仅供模型动画使用，不能改变真实移动、权限或服务器动作校验。
- **A2 · 暂缓输入同步。** 先补齐服务器可确认的查询，输入依赖模型仍保留与本人不同的可能性。减少额外实时消息与适配工作，但不能将这一部分称为完整上游兼容。

**代价与性能：** A1 增加实时状态消息，数量与玩家输入变化和实际观看人数有关，需测量后确定限频和字节预算。后台发现／资源管理可接受此前的 1–5 秒间隔；短暂按键及松开不能统一延后这么久，否则可能完全漏掉该动作。无需新增位置广播，也不需要客户端申请下载任意服务器模型。

**待确认：** 采用 A1，还是先采用 A2。推荐 A1，限频和预算通过实现与测量确定，不要求用户预先填写 Hz。

#### B · 本地／私人 BBModel 是否增加原生导入路径

**问题是什么：** 当前独立 `.bbmodel` 解析器只接受一部分 Blockbench 结构。独立骨骼表 `groups`、mesh、locator／null_object、数值 Bézier 曲线会被拒绝；标准 `animation_controllers` 也没有被消费。Sparkle 的成熟转换器能将这些结构转为原生运行资产。并非所有 `.bbmodel` 都有问题，触发取决于文件是否使用这些能力。

**玩家会遇到什么：** 一个文件在参考项目可用，在 MPA 可能加载失败；文件包含控制器时也可能被导入却没有相同的动作控制。数值 Bézier 表示作者设计的平滑变化曲线，不能直接等同于当前线性关键帧。以上是独立 BBModel 的差异，公开二进制 YSM 的同名曲线路径要单独核对。

- **B1 · 增加独立原生导入路径（推荐）。** 本地选择与私人上传的 BBModel 参考 Sparkle 解析、转换，保留作者骨架、挂点、支持的曲线和控制器。与现有服务器／普通 BBModel 的上下文、轴向迁移和兼容逻辑隔离；ModelEngine 的蓝图导入及资源包生成流程保持现有分工。
- **B2 · 继续使用当前受限导入。** 保持较小的解析范围，明确哪些结构不支持；使用这些结构的模型需要作者另行导出为受支持格式。降低此次开发和兼容检查范围，但保留与 Sparkle 的导入能力差距。

**代价与兼容：** B1 的转换与验收工作较多，需要对照真实模型核对骨架、贴图、动画和附件；沿用路径、像素、几何、脚本及展开预算。文件扩展名相同不意味着全局切换解释语义，也不会给缺少动画定义的普通模型自动创造 YSM 动作。

**待确认：** 本地与私人上传是否采用 B1。推荐 B1；现有服务器蓝图及旧资产使用独立兼容路径。

#### C · 原生 YSM 缺少挂点时是否自动补附件

**问题是什么：** 挂点（locator）是作者给手持物、头部附件指定的位置和旋转。当前 MPA 在主 HandLocator 没有声明时会尝试 Hand 骨骼，头部附件优先使用 HeadLocator 再使用 Head。上游原生 YSM 的指定链不同：主手持按对应 HandLocator，头部物品按其指定 Head 链。

**玩家会遇到什么：** 作者可能有手骨，却故意不给主持物挂点。例如模型已画出一把武器，自动把原版持物再绑到手骨可能多出一把；同时存在 Head 和 HeadLocator 时，本项目优先级也可能把头上物品放到不同位置。这些是可能的触发例子，并非本轮已做实机复现。已声明但作者隐藏的 HandLocator 目前不会退回 Hand，不能把这项已保留的行为再列为缺陷。

- **C1 · 原生严格、普通 BBModel 保留回退（推荐）。** 原生 YSM 按来源的指定链显示附件，没有所需主挂点时不擅自补画；普通／服务器 BBModel 继续使用其现有回退规则。按 B1 原生转换的资产遵循原生链。
- **C2 · 原生也保留 MPA 回退。** 尽量让缺少标准挂点的模型仍能显示附件，但可能改变作者原本的画面。若保留，应明确为兼容模式，不能称为完全相同的上游规则。

**代价与兼容：** C1 可能让依赖 MPA 回退的原生模型少显示附件，需要作者补正确挂点；它更符合原作者定义。C2 减少这类模型的调整，却继续存在重复持物或位置偏差风险。两种选择只影响附件绘制，不改玩家实际装备和物品。

**待确认：** 采用 C1，还是原生也保留 C2。推荐 C1，普通 BBModel 的已有兼容行为单独保留。

### 不作为本项目特有缺陷的项目

本轮核对没有确认 checkbox 选中条件、radio 取整、range 步长／负区间的基本值语义与上游不同，不重复列为 UI bug。多项 geometry 的固定来源同样选第一项，不能据此宣称完整选择支持；公开原生 YSM 的 Bezier 路径在固定来源也回退为线性，blend_via_shortest_path 虽被读取但来源执行端未消费，两者不能单列为 MPA 遗漏。Sparkle 独立 BBModel 的数值曲线烘焙与公开原生 YSM 这条路径须分开讨论。

被动授权分发、显式上传、观看者授权、预算、hash／ACK／租期校验是本项目三路径设计；与上游包格式或云端请求流程不同本身不是兼容 bug。保留这些限制，不转成客户端可任意指定服务器模型申请下载的接口。

## 输入条件与边界

| 条件 | 行为 |
| --- | --- |
| 本地输入与分享预算 | 本地 YSM 输入／展开／转换分别限 64 MiB，独立 BBModel 8 MiB；私人归档与展开仍限 8 MiB，协商可降低，超出分享预算可继续本地使用；文件数、像素、几何、脚本分别有界，见 [导入预算详情](history/CLIENT_0_4_8_GALLERY_IMPORT.md#本地导入与私人分享) |
| 原生时间线 | 单事件最多 256 段／32 KiB UTF-8，保留作者顺序；普通 BBModel 及声音／粒子事件仍限 32 项 |
| AVIF | 未内置可分发解码器：必需 AVIF 材质明确报错，可选作者 AVIF 头像用占位图；WebP 使用首个完整图像帧 |
| 路径与密钥 | 拒绝链接、目录外资源、非自包含或需额外服务端密钥的 `.ysm` 缓存，不绕过授权 |
| 尚无实现入口 | glTF 蒙皮／`poly_mesh`、normal／specular PBR 着色器、其他模组专用联动、云端商店 |
| 网络互通 | 私人同步使用本项目可选协议，与原 OpenYSM／Sparkle 服务器协议不互通；其他服务器模型引擎可实现 [本项目协议](CLIENT_PROTOCOL.md) 复用客户端 |

具体格式条件不在游戏中循环提示。逐版本迁移、离线与实机验收记录见 [历史文档](history/README.md)；源码导航与构建流程见 [CONTRIBUTING](../CONTRIBUTING.md)。这些记录区分编译／单元／离线导入与实际游戏验证，不能以加载成功或历史测试替代所有模型画面与多人行为的验收。
