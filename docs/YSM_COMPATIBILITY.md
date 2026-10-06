# YSM 功能与来源（0.5.0）

[文档索引](README.md) · [客户端使用与配置](CLIENT_CONFIG.md) · [同步协议](CLIENT_PROTOCOL.md) · [开发导航](../CONTRIBUTING.md)

本页列出当前代码支持的原版 Minecraft、私人本地功能与私人模型多人同步。私人模型可独立使用；分享须玩家显式开启、服务器许可和观看者安装模组。游戏不自动发送缺同步插件／不支持私人多人同步提示，状态由设置或 `/mpaclient status` 主动查看。

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
| 几何与材质 | 作者骨骼、方向四边面、负尺寸、UV、材质索引、多 geometry identifier 与作者比例；PNG／BMP／JPEG／WebP、原生 RGBA 按实际格式解码 | [YsmFolderModel](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmFolderModel.java)、[BbModel](../client/src/main/java/com/simmc/meplayeractions/client/model/BbModel.java) |
| 表达式 | 字符串、结构、动态骨骼句柄、数组、空值回退、实体子上下文 `->`、`this`、函数参数与来源数学规则；变量／物理／事件按实例隔离。未知原生查询函数或参数数量无效时整条公式按来源降为 `0` 并诊断，危险宿主调用、非法语法与超预算仍拒绝 | [Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java)、[AnimationPlayer](../client/src/main/java/com/simmc/meplayeractions/client/model/AnimationPlayer.java) |
| 动画与控制器 | main／arm／extra、作者状态转换与进出脚本、时间线、生命周期、动态骨骼、一／二阶物理、槽级 `ysm.defer` 与初始化顺序；持物保留作者 ONCE／LOOP／HOLD。`anim_time_update` 及兼容别名、`start_delay`、`loop_delay` 在三份固定来源均无实现，按 [Minecraft 官方动画定义](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/actor_animation.v1.8.0?view=minecraft-bedrock-stable) 补齐，不新增全局配置开关 | [YsmAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationController.java)、[YsmAnimationClock](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmAnimationClock.java) |
| 原版状态与查询 | 移动、潜行、睡眠、骑乘、攀梯、飞行／鞘翅、主副手使用／挥手／物品变化、四个盔甲槽、真实输入脉冲；远程读取原版实体，被观看者无需模组；投射物读取实际类型／所有者／状态 | [VanillaYsmQueries](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmQueries.java)、[VanillaYsmAnimations](../client/src/main/java/com/simmc/meplayeractions/client/VanillaYsmAnimations.java) |
| 渲染与组件 | BODY 比例与持物 locator 共用骨骼变换；原生左右手遮罩与第一人称坐标沿来源，普通持物保留游戏变换；载具／投射物、额外持物 locator、乘员 locator、肩膀鹦鹉读取真实原版状态，不改变碰撞或权限 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)、[YsmComponentRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/YsmComponentRenderer.java) |
| 声音与粒子 | 作者本地 OGG 流式播放、原版资源包声音、真实粒子；声音按实例／控制器作用域停止，重载／实例结束释放效果，执行频率与存活数量有界 | [YsmModelEffects](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmModelEffects.java) |
| 预览与图库 | OWNER 当前玩家、SELECTED 左侧草稿假人、CARD 卡片假人有独立时钟／控制器／物理／查询；假人不继承人物库存，OWNER 持物沿作者骨骼提交原生深度渲染。点击卡片预览草稿，“使用模型”才改变世界；保留作者卡片尺寸、入场／预览、拖动方向、目录与递归搜索 | [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java)、[ModelGalleryIndex](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelGalleryIndex.java) |
| 表单与皮肤 | checkbox／range／radio 的 read／write 完整脚本、步长／负步长、选中规则、作者函数、嵌套语言和提示；读值不改世界，提交原子保存作者变量／radio／纹理，实时物理与查询缓存不保存 | [ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[AuthorFormWidgets](../client/src/main/java/com/simmc/meplayeractions/client/ui/AuthorFormWidgets.java) |
| 轮盘与显示 | J 居中经典轮盘，作者顺序／分类／分页、滚轮提示、关联配置齿轮、解除模块与统一主页；CLIENT／SERVER 显式切换，玩家／装备／伪装三显隐独立，渲染总开关在设置；SERVER 动作连接不依赖渲染 | [AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java)、[PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java) |
| 私人多人同步 | 独立 `meplayeractions:private` v1；显式发布，服务器验证与授权分发，观看者校验 hash／纹理、ACK 与续租；同步皮肤、作者变量／radio／roaming、extra 动作与原生 sync 事件。服务器伪装优先，手动私人覆盖仅影响本人 | [PrivateModelSyncClient](../client/src/main/java/com/simmc/meplayeractions/client/network/PrivateModelSyncClient.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) |

普通 BBModel／服务器表达式保持原上下文；原生 YSM 按格式启用上述语义。缺失查询可能取 `0`，显式 `null` 可使用作者默认表达式；诊断仅在实际读取发生后记名称、次数与实际默认值。作者数字 roaming 每模型最多 64 项、名称最多 32 字符，跟踪脏变更并合并 profile；逐帧位置、物理与查询缓存留本机。

原生 YSM 头部查询沿固定来源：`ysm.head_pitch` 取原版俯仰角负值，`ysm.head_yaw` 先环绕到 `[-180,180)`、限制在 `[-85,85]` 后取负；`query.head_x_rotation` 对应 yaw，`query.head_y_rotation` 对应 pitch。自动头部跟随仍接收原版角度，与脚本查询分离，使作者的头发防穿模补偿按原规则执行。

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
