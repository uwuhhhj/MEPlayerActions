# YSM 功能与来源（0.6.1）

[文档索引](README.md) · [客户端使用与配置](CLIENT_CONFIG.md) · [同步协议](CLIENT_PROTOCOL.md) · [开发导航](../CONTRIBUTING.md)

本页说明原版 Minecraft、本地模型与私人模型多人分享的当前能力、执行规则和限制。格式支持不等于所有模型都具有相同画面表现，也不代表与上游网络协议互通。

三条使用路径相互独立：本地私人模型仅自己观看；显式上传并获服务器许可的私人模型由获准的模组观看者本地渲染，原版观看者仍看原版玩家；服务器伪装由模型引擎向原版观看者呈现，模组观看者可接管本地渲染。私人分享不转换成 ME 伪装，位置同步由 Minecraft 负责。

## 来源与许可

| 固定参考 | 提交 | 参考内容 |
| --- | --- | --- |
| [OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated/tree/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85) | `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85` | 原版动作、模型、组件、图库、经典轮盘、作者资源 |
| [Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher/tree/b1230a431900a286d2cca198072df7fb43c490b4) | `b1230a431900a286d2cca198072df7fb43c490b4` | BBModel 导入、表达式、公开模型解码、动态骨骼／物理、原版状态、表单语义 |
| [ModernYSM](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658) | `a515d44686af77155a311b2a592327ca5d45a658` | 早期查询、函数、生命周期交叉参考 |

迁移软件沿各自 MIT 许可，资源许可独立，完整署名及源码对应关系见 [第三方说明](../THIRD_PARTY_NOTICES.md)。内置默认及 Alex／Steve 模型为 CC0；三套酒狐的原始资源为 CC BY-NC-SA 4.0，须非商用、署名并按相同许可分发改编。BBModel 动作预设保留 Mojang／Microsoft Bedrock 来源及 splatty 的 CC BY 署名。服务器 `ysm_01_jk`／`ysm_02_jk` 不内置；玩家皮肤替换只识别可信原始 Alex／Steve 内容。

## 支持矩阵与源码导航

| 功能 | 当前实现 | 主要源码 |
| --- | --- | --- |
| 导入与解码 | `spec:2` 文件夹、完整 ZIP／ZIP 容器 `.ysm`、公开自包含二进制 `.ysm` v1–32、本地 `.bbmodel`；沿来源 CityHash／MT／XChaCha／Zstd 解码并转换资产，逐文件校验路径与预算 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java)、[NativeModelBundle](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeModelBundle.java) |
| 几何与材质 | 作者骨骼、方向四边面、负尺寸、UV、材质索引、geometry identifier 与作者比例；PNG／BMP／JPEG／WebP、原生 RGBA 按实际格式解码 | [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java) |
| BBModel 资产 | 本地、私人分享、服务器接管、磁盘缓存和资源包共用格式解释；作者文件复用 Sparkle 解析、转换与装配链，包含 groups、mesh、locator／null_object、数值 Bézier、标准 controllers、贴图、时间线、动作预设与未接管部位的原版姿态；带运行资产标记的文件不重复转换 | [BbModelAsset](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModelAsset.java)、[NativeBbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeBbModel.java)、[nativebbmodel](../client/src/main/java/com/simmc/meplayeractions/client/model/nativebbmodel/) |
| 表达式 | 字符串、结构、动态骨骼句柄、数组、空值回退、实体子上下文 `->`、`this`、函数参数与来源数学规则；变量／物理／事件按实例隔离。未知原生查询函数、参数数量或条件语法无效时沿来源降为 `0` 并诊断，危险宿主调用与超预算仍拒绝 | [Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java) |
| 动画与控制器 | main／arm／extra、作者状态转换与进出脚本、时间线、生命周期、动态骨骼、一／二阶物理、槽级 `ysm.defer` 与初始化顺序；持物保留作者 ONCE／LOOP／HOLD；支持 `anim_time_update` 及兼容别名、`start_delay`、`loop_delay` | [YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[YsmAnimationClock](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationClock.java) |
| 原版状态与查询 | 移动、潜行、睡眠、骑乘、攀梯、飞行／鞘翅、主副手使用／挥手／物品变化、四个盔甲槽、本人输入脉冲；远端复用原版实体，投射物读取实际类型／所有者／状态 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java)、[VanillaYsmAnimations](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmAnimations.java) |
| 渲染与组件 | BODY 比例与持物 locator 共用骨骼变换；原生左右手遮罩、第一人称坐标和附件链沿来源；载具／投射物、额外持物 locator、乘员 locator、肩膀鹦鹉读取真实原版状态，不改变碰撞或权限 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)、[YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java) |
| 声音与粒子 | 作者本地 OGG 流式播放、原版资源包声音、真实粒子；按实例／控制器作用域停止，控制器与播放槽退出自动清理，重载／实例结束释放效果；执行频率与存活数量有界 | [YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java) |
| 预览与图库 | OWNER 当前玩家、SELECTED 左侧草稿假人、CARD 卡片假人各有独立时钟／控制器／物理／查询；点击卡片预览，“使用模型”才应用。保留作者卡片、入场／预览、拖动方向、目录与递归搜索；经典 420 宽面板整体居中，小 GUI 缩减行列 | [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[ModelGalleryIndex](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelGalleryIndex.java)、[GalleryPanelLayout](../client/src/main/java/com/simmc/meplayeractions/client/ui/GalleryPanelLayout.java) |
| 表单与皮肤 | checkbox／range／radio 的 read／write 脚本、步长／负步长、选中规则、作者函数、嵌套语言和提示；读值不改世界，提交原子保存作者变量／radio／纹理，实时物理与查询缓存不保存 | [ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[AuthorFormWidgets](../client/src/main/java/com/simmc/meplayeractions/client/ui/AuthorFormWidgets.java) |
| 轮盘与显示 | J 居中经典轮盘，作者顺序／分类／分页、滚轮提示、关联配置齿轮、解除模块与统一主页；客户端／服务端显式切换，玩家／装备／伪装三层显隐独立，渲染总开关在设置；服务器动作连接不依赖渲染 | [AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java)、[PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java) |
| 私人多人分享 | 独立 `meplayeractions:private` v1；显式发布，服务器验证与授权分发，观看者校验 hash／纹理、ACK 与续租；同步皮肤、作者变量／radio／roaming、extra 动作与原生 sync 事件。本人服务器伪装与私人模型互斥 | [PrivateModelSyncClient](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) |

## 状态与渲染规则

本地本人读取真实原版状态；远端读取原版已同步的实体字段，例如血量和最大血量。食物、经验、完整效果 ID／等级、移动按键输入等原版未提供给普通观看者的信息不增加 MPA 同步包，不要求发布者上报，也不把位移视为等价输入；保留既有缺省，例如私人远端食物值 `20`。依赖这些字段的模型可能在本人和观看者处表现不同。

`ysm.input_vertical`／`ysm.input_horizontal` 沿来源按实际位移计算方向比例，与真实按键输入 `ysm.xxa/yya/zza` 不同。原版挥手 ticks 与正值攻击插值优先，没有原版值时才使用已有本人脉冲。私人协议中的飞行展示标志取服务器 `Player.isFlying()`，不由上传者声明，也不修改实际飞行权限。

身体、组件和装备复用真实 world `entityRenderStates` 的原版位置、逐实体 delta、renderer offset、相机与光照；查询 origin 使用未叠加外观偏移的 state.xyz。服务器时间线仅驱动动画，不参与 XYZ 插值，私人分享不新增位置广播。第一人称没有身体 state 时仅使用本人当帧 fallback。原版姓名／计分牌、火焰、阴影、隐身与发光轮廓继续参与提交。入口见 [NativePlayerPresentation](../client/src/main/java/com/simmc/meplayeractions/client/render/NativePlayerPresentation.java)。

原生头部查询沿来源：`ysm.head_pitch` 取原版俯仰角负值；`ysm.head_yaw` 先环绕到 `[-180,180)`、限制在 `[-85,85]` 后取负。`query.head_x_rotation` 对应 yaw，`query.head_y_rotation` 对应 pitch。自动头部跟随接收原版角度，与作者脚本查询分离。

明确带 `mpa_runtime` 来源标记的服务器 YSM 派生资产也使用同一头部查询函数，不依赖本地／私人模型的 profile。读取这些资产时移除旧导出工具注入的 Head 查询加项，复用原版自动跟随；作者头发、弹簧和特殊动作表达式保持原样。完整资产的下载、已有磁盘缓存与离线预览均在资产解析入口应用此规则，不改原版位置、服务器动画层、模型尺寸或普通 BBModel 语义。已烘焙的 ME 数值蓝图不含这些作者公式，角度修复不能还原丢失的脚本；须由服务器提供完整原资产，见 [模型来源与部署](MODEL_DELIVERY.md#服务器伪装原模型的正确放置)。入口见 [YsmHeadQueries](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmHeadQueries.java) 与 [BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java)。

同一完整文件在相同姿态、参数与动作输入下，坐标轴、作者表达式、控制器与物理规则不因本地或服务器来源而改变。普通作者 BBModel 经过同一导入链；显式 `ysm_format_version` 或对象型 `mpa_runtime` 表示已准备的运行资产，保留字节并直接解析。服务器使用原始文件 hash 授权和缓存，转换结果留在内存；渲染所需的 profile 与预览动画随资产保存，不再由绑定来源决定。服务器权限、命令缩放、动作选择与服务器时间线继续由对应绑定控制。

BBModel 静态骨骼和 locator 旋转使用 Blockbench 项目坐标。固定 Sparkle 转换器把项目 X／Y 旋转当作 Bedrock 几何反转；BBModel 专用适配层恢复作者初始角度，并按 ZYX 合成。依据为 [Blockbench 4.10.4 项目预览](https://github.com/JannisX11/blockbench/blob/v4.10.4/js/outliner/outliner.js#L575) 与 [Bedrock 导出](https://github.com/JannisX11/blockbench/blob/v4.10.4/js/io/formats/bedrock.js#L816)；[5.0 格式迁移](https://github.com/JannisX11/blockbench/blob/v5.0.0/js/io/formats/bbmodel.js#L67)只迁移动画通道，不反转静态骨骼。这项适配仅作用于作者 BBModel 导入，原生二进制／文件夹 YSM 与已转换资产保持各自格式规则。入口见 [NativeBbModelBasis](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeBbModelBasis.java)。

作者 BBModel 动画按原文件版本保留通道语义：4.x 的位置 X、旋转 X／Y 反号，5.x 保持各轴正值，均按项目 ZYX 合成。版本标记随作者动画、别名与第一人称副本保留，在表达式求值后适配，不重写作者 Molang 程序；生成动作预设与原生 YSM 不使用这个标记。数值 Bézier 的时间／值挂柄按关键帧端点偏移解释，双数据点保留前／后顺序。依据为 [4.10 动画预览](https://github.com/JannisX11/blockbench/blob/v4.10.4/js/animations/timeline_animators.js#L331)、[5.0 动画预览](https://github.com/JannisX11/blockbench/blob/v5.0.0/js/animations/timeline_animators.js#L369)和 [Bézier 挂柄](https://github.com/JannisX11/blockbench/blob/v5.0.0/js/animations/keyframe.js#L209)。入口见 [NativeBbModelAnimations](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeBbModelAnimations.java)。

## 来源执行语义

- 动画从已解析的最终骨骼姿态建立开始快照；最终无人接管的通道在 3 tick 内回位，其他播放槽继续输出。predicate 控制器保留来源的缩放过渡、结束进度缓存和作者权重；作者状态控制器使用其自身进度规则。原生旋转混合计入骨骼 initial rotation。见 [NativeYsmAnimationProcessor](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmAnimationProcessor.java)。
- 控制器按逐槽条件／状态、事件／权重，再逐骨骼旋转／位置／缩放的来源顺序求值，保留表达式副作用与 inactive 槽语义。`merge_multiline_expr` 贯通二进制、文件夹、手臂和组件，以换行合并时间线及 entry／exit 数组。见 [NativeYsmScriptArrays](../client/src/main/java/com/simmc/meplayeractions/client/model/NativeYsmScriptArrays.java)。三个动画时间字段按 [Minecraft 官方动画定义](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/actor_animation.v1.8.0?view=minecraft-bedrock-stable) 执行。
- 原生查询 `query.life_time` 使用模型实例 seek time；player／maid 使用来源类型别名，其余 living entity 返回完整 `namespace:identifier`。越界 position／position_delta／rotation_to_camera 轴返回 `null`；非法手条件返回 `false`，非法 armor 槽返回 `null`，保留作者回退。缺失查询的名称、次数与实际默认值仅在读取后记入诊断。
- 各路径的作者 BBModel 共用 Sparkle 导入链和同一 BBModel 坐标适配，保留来源骨架、网格、挂点、数值曲线、控制器、贴图与事件；本体姿态只补未被作者旋转接管的部位并排除 head，第一人称只装配来源手臂控制器。ModelEngine 导入蓝图并生成原版资源，CraftEngine 合并、托管及下发。
- 作者数值 Bézier 的烘焙采样点不替代 Catmull 的原始邻点，HOLD 与 LOOP 均保留原曲线的控制键。原始键索引在加载时建立，原生与未烘焙路径保留原采样开销。
- 一／二阶函数物理在公共采样入口按模型实例推进，无控制器的 BBModel 也会更新弹簧；同帧不会重复推进。`mpa_runtime` 的显式并行物理脚本保留原有 0.01 秒积分规则。
- 原生附件使用来源的 HandLocator／Head／ElytraLocator 链与变换，没有指定挂点时不额外回退到 Hand 或优先使用 HeadLocator；导入链自身的骨架／挂点补全仍沿来源保留。`VANILLA_EQUIPMENT` 与原生 authored 持物分别执行；作者 `render_layers_first` 控制模型／附件阶段顺序，各阶段末尾刷新原版延迟队列。见 [YsmItemRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmItemRenderer.java)、[YsmEquipmentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmEquipmentRenderer.java)、[NativeRenderPhases](../client/src/main/java/com/simmc/meplayeractions/client/render/NativeRenderPhases.java)。
- 作者数字 roaming 每模型最多 64 项、名称最多 32 字符，按脏变更合并 profile；逐帧位置、物理与查询缓存留本机。声音管理器按本体／组件实例隔离，GUI 采样不触发世界效果。

## 格式条件与限制

| 条件 | 行为 |
| --- | --- |
| 输入与分享预算 | 本地 YSM 输入／展开／转换分别限 64 MiB；独立 BBModel 源文件 8 MiB，目录内声明的 PNG 伴随资源及转换后资产分别限 64 MiB。私人原始归档与展开限 8 MiB，原生 BBModel 派生资产另限 64 MiB，协商可降低；超出分享预算可继续本地使用。文件数、像素、几何与脚本亦受预算约束 |
| 原生时间线 | 单事件最多 256 段／32 KiB UTF-8，保留作者顺序；普通 BBModel 及声音／粒子事件限 32 项 |
| geometry 与曲线 | 多项 geometry 沿固定来源选择第一项；公开原生 YSM 的 Bézier 路径沿来源回退为线性，`blend_via_shortest_path` 的读取不表示执行。Sparkle 独立 BBModel 的数值曲线烘焙为另一条路径 |
| BBModel 来源限制 | 控制器引用不额外将 UUID 重写为动画名称，不消费条目任意额外 blend 字段；非法条件取 `0`，缺省／空条件使用默认值。根级无父组的独立元素按来源可能无法绑定骨骼。非 YSM 的 BBModel ZIP、Figura 与 Bedrock 包自动识别未接入选择入口 |
| CPU 材质行为 | 保留 `all_cutout` 元数据；固定来源 CPU 绘制不消费 `cube.cullable`，不将其解释成 alpha cutout 或 GPU 管线 |
| AVIF 与 WebP | 未内置 AVIF 解码器：必需 AVIF 材质报错，可选 AVIF 作者头像用占位图；WebP 使用首个完整图像帧 |
| 路径与密钥 | 拒绝链接、目录外资源、非自包含或需额外服务端密钥的 `.ysm` 缓存，不绕过授权 |
| 无实现入口 | glTF 蒙皮／`poly_mesh`、normal／specular PBR 着色器、其他模组专用联动、云端商店 |
| 网络互通 | 私人分享使用本项目可选协议，与原 OpenYSM／Sparkle 服务器协议不互通；模型下载受上传授权、观看者权限、预算、hash／ACK／租期约束。其他服务器模型引擎可实现 [本项目协议](CLIENT_PROTOCOL.md) 复用客户端 |

使用与排查见 [客户端配置](CLIENT_CONFIG.md)，源码与构建见 [开发导航](../CONTRIBUTING.md)，版本变更见 [历史文档](history/README.md)。
