# 客户端配置与使用（0.5.1）

[文档索引](README.md) · [安装](../README.md) · [服务器模型与部署](MODEL_DELIVERY.md) · [YSM 支持范围](YSM_COMPATIBILITY.md) · [开发导航](../CONTRIBUTING.md)

## 日常操作

本模组只注册 **J** 轮盘按键，可在 Minecraft 控制设置中改绑；F5 使用原版视角切换。旧 G／Y／N 入口已移除，旧 G 绑定不会沿用。

- J 轮盘右上角齿轮或 `/mpaclient settings` 打开玩家模型主页。顶部“客户端／服务端”共用图库布局：客户端浏览私人模型，服务端浏览服务器主动列出的可用伪装模型。
- 点击卡片只浏览。客户端等待预览加载完成，再点“使用模型”应用为仅本机外观并撤回当前私人分享；开启总渲染与本人伪装显示，保留玩家／装备显隐、缩放和 XYZ。服务器伪装期间的手动私人覆盖仍保留原分享意愿。服务端“使用模型”发送固定 `/meplayeractions disguise <ID>`，等待服务器校验与绑定；不以点击成功冒充伪装成功。
- 收藏保留在卡片右上角。客户端卡片左上角云朵与左栏“上传分享”会**使用所选模型并主动上传分享**；服务器确认前区分等待、上传与校验，确认后才显示已上传。搜索栏云朵切换“已上传”筛选，只列出本次连接已确认的模型；断线或明确刷新源文件会清空记录，不表示服务器永久模型库。
- 图库沿 [OpenYSM 经典面板](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L330-L331) 使用居中的 420 逻辑像素宽面板与 5×2 卡片；内容区为 238 高，容纳本项目搜索和分页间距。两来源共用同一头部高度，来源按钮与页脚随面板定位；小 GUI 缩减行列与按钮间距，实际物理尺寸受 Minecraft GUI 缩放设置影响。
- 轮盘可悬停后松开 J、点击或按 1–8 选择。普通区域滚轮切页；打开作者配置后，右侧面板内滚轮滚动表单。CLIENT 中心切换动作锁定，SERVER 中心停止手动动作。保持轮盘打开在主页齿轮设置中调整。
- 轮盘左侧“解除私人伪装”关闭私人外观并保留配置；手动 CLIENT 覆盖服务器伪装时切回 SERVER。“解除服务器伪装”发送固定 `/meplayeractions undisguise`，等待服务器权限检查和解绑确认；外部原生模型只结束 MPA 接管。关闭渲染仍可使用解除入口。

默认已开启**服务器伪装的客户端渲染接管**，无需先启用私人模型。私人外观初始未启用，选用后默认仅自己可见。SERVER 动作需有效连接、未超时的本人服务器状态和下发动作目录；**关闭客户端渲染仍可用 SERVER 轮盘与 stop**，解绑、断线或超时后不沿用旧权限。CLIENT 使用作者 extra 入口和分类，不把全部动画 clip 都列为可选动作，也不发送服务器动作请求；真实姿态用服务器 `/meplayeractions pose`。

## 显示来源与三个显隐

服务端图库需两端协商 `server_model_catalog`，并取得 `mact.use` 与 `mact.disguise` 权限；只列出已加载、符合 `models.allowed` 且具有协议安全 ID 的模型。最多 4096 条，超限明确标记。浏览不会下载全部模型，未取得资产的卡片先显示名称；使用后走既有服务器主动下发，已有本次会话资产可复用预览。目录与权限按后台周期刷新，默认在 20 TPS 下最坏约 7 秒，伪装命令仍即时检查真实权限。未更新服务端时保留现有伪装接管与动作功能，目录页显示不可用。

新的本人服务器伪装实例自动选择 SERVER，加载失败或重载时也不自动改为私人模型。在主页明确切到 CLIENT，可用私人模型覆盖本人本机显示；选择在同一实例内保留，需当次完整快照和本人 ready／ACK 后才显示，避免与服务器回退模型重叠。切回 SERVER 暂停私人显示；解除服务器伪装后恢复保存的私人启停、模型及参数，不删除本地文件。SERVER 来源暂停私人编辑和本地动作，旧本地命令不会隐式切换来源。

主页与详情的三个按钮立即保存、各自独立：

| 按钮 / 字段 | 没有本人服务器伪装时 | 本人服务器伪装期间（含手动 CLIENT 覆盖） |
| --- | --- | --- |
| 玩家隐藏／显示：`hideVanillaPlayer` | 控制原版人物，保留伪装与主副手物品 | 沿服务器隐藏规则，按钮禁用 |
| 装备隐藏／显示：`hideVanillaEquipment` | 控制原版盔甲、头戴物、披风、鞘翅；不控制作者自带衣物或手持物品 | 强制隐藏原版装备，按钮禁用 |
| 伪装显示／隐藏：`showSelf` | 只控制本人的伪装几何，脚本与物理继续运行 | 同样只控制本人客户端模型，不能扩大服务器观看许可 |

玩家显示时由原版人物绘制装备；玩家隐藏而装备显示时，使用模型可用挂点绘制一套装备，隐藏伪装也不取消明确显示的装备。渲染总开关 `enabled` 位于主页齿轮设置中：关闭后释放本地显示租约并恢复服务器／原版显示，保留三个显隐、私人配置和服务器动作连接，不是第四个常驻显隐按钮。

第一人称不画本人的完整身体。私人 YSM 有 `arm/fp_arm` 组件时，原生空手或地图左右手绘制入口使用作者手臂；普通方块、武器及使用物品保留原版持物路径。伪装隐藏时不画作者手臂；玩家显示可回退原版手臂，玩家隐藏则隐藏该原生手臂入口，物品路径保留。

## 文件与导入

以下路径均相对于**当前 Minecraft 实例**，与服务器 `plugins/MEPlayerActions/config.yml` 分开：

| 路径 | 用途 |
| --- | --- |
| `config/meplayeractions-client.json` | 本人设置、模型 profile、收藏与轮盘记忆 |
| `config/meplayeractions/models/` | 私人 `.bbmodel`、YSM 文件夹、ZIP 或公开自包含 `.ysm`；图库“文件夹”打开目录，“刷新”重新扫描／解码 |
| `config/meplayeractions/cache/` | 服务器授权模型的 SHA-256 缓存；不会自动加入私人图库 |

图库按内置、YSM 与 BBModel 来源分组，显示专用目录内有效模型和真实 YSM 子目录；各目录页码分别记忆，搜索可递归查找。它不提供任意文件系统浏览，也不会因浏览触发私人上传。

独立 BBModel 用 `local:文件名.bbmodel`，仅限目录根部单文件；YSM 用 `ysm:相对路径`，可含安全子目录。ID 最长 128 字符、最多 9 段；禁止绝对路径、反斜线、空段、首尾空白、点号开头、`..`、控制字符及非法路径字符。普通文件名可含空格，建议从图库或 Tab 补全获取 ID。

内置 ID：`openysm_default`、CC0 原始玩家皮肤模型 `openysm_alex`／`openysm_steve`，以及 `wine_fox_01_taisho_maid`、`wine_fox_02_new_year`、`wine_fox_03_astronaut`。酒狐资产采用 [CC BY-NC-SA 4.0](../THIRD_PARTY_NOTICES.md#openysm-wine-fox-model-assets-cc-by-nc-sa-40)，仅供非商用，分发／改编须保留署名与相同许可；新增模型不改变已保存选择。服务器 `ysm_01_jk`／`ysm_02_jk` 不内置，须由服务器授权同步或自行私人导入。

本地 YSM 原始输入、展开资源、转换结果分别限 64 MiB；独立 BBModel 原文件限 8 MiB，声明的目录内 PNG 伴随资源及转换后资产分别限 64 MiB。私人分享归档和展开资源仍限 8 MiB，BBModel 的原生转换派生资产另限 64 MiB，协商可降低；超过分享预算不取消本地加载。文件、图像、几何和脚本各有独立预算，格式与材质要求见 [YSM 支持范围](YSM_COMPATIBILITY.md)。

服务器缓存离服后保留，最多 128 MiB／512 个有效文件，按最近使用裁剪。命中缓存仍须校验、准备纹理和取得**当次绑定**确认，不能凭缓存申请任意服务器模型。重载撤销显示接管并重建纹理，保留完整推送资产、有效在途下载和同实例来源选择；断线／世界切换清理当前绑定和下载授权，磁盘缓存保留。推送不依赖私人分享开关，详见 [模型同步与部署](MODEL_DELIVERY.md)。

## 本地外观与私人分享

客户端“使用模型”仅本机显示，云朵“上传分享”才使用并上传；再次点“使用模型”撤回当前分享。私人分享保持独立于服务端 ME 伪装，一次发布一个正在使用的私人模型。“已上传”可保留本次连接确认过的其它模型，但不代表它们同时分享或永久保存在服务器。`privateSyncEnabled=false` 默认关闭，旧配置缺字段不会启用。还需服务端 `client-sync.enabled: true`、`client-sync.private-models.enabled: true` 和发布者 `mact.private.upload`／观看者 `mact.private.view` 权限（两项默认均为 `false`，OP 也需显式授予）。观看者须安装支持的客户端，但无需开启自己的分享开关，被动接收服务器授权模型；原版观看者仍看到原版玩家。关闭分享或私人外观撤回展示，保留本地内容和服务器有界资源缓存。

`.ysm` 上传保留完整 geometry、动画、控制器、函数、作者配置、纹理、声音与必要资源；`.bbmodel` 保留原模型、内嵌贴图与已声明的目录内 PNG 伴随资源，本地与观看端复用同一上游转换链；不读取编辑器记录的绝对路径或下载外部 URL。网络上传的是经过验证的完整原生 bundle，不是丢失作者功能的单一转换模型。皮肤、作者参数、radio、额外动作和允许的原生事件可同步；物理／查询缓存、逐帧位置留在各客户端。

本人有服务器伪装时，分享入口暂停，手动 CLIENT 覆盖始终仅供本人；不因暂停、上传失败或撤权修改保存的分享意愿。私人中继可在 Paper 1.21.11／Java 21 运行同一服务端 JAR，无需 ModelEngine 或服务器资源包；使用独立 `meplayeractions:private` v1，不与原 OpenYSM 网络协议互通。

私人观看者只使用原版已经同步的玩家状态；食物、经验、完整效果等级和移动输入不额外广播，未提供的查询使用既有缺省／原版远端字段（例如食物 20）。本人本地仍使用真实状态，依赖未同步字段的模型可能与观看者表现不同，详见 [已确认的状态规则](YSM_COMPATIBILITY.md#a--原版未下发的状态不增加同步)。

状态显示归档、等待授权、上传进度、待校验和已分享，失败使用对应原因。发布成功以服务器确认后的状态为准；资源命中缓存也要取得本次授权。上传提案、完成通知、观看端资源反馈和 ready 未获对应确认时按秒重试，精确 ACK 前不显示远端私人模型。当前参数状态每两秒重发用于恢复丢失更新，相同动作序号不重播。服务器磁盘缓存以上传者和 hash 隔离，离线不等于删除资源，缓存不赋予持续展示权。配置、预算与目录见 [模型部署](MODEL_DELIVERY.md)，身份与租约见 [客户端协议](CLIENT_PROTOCOL.md)。

## 默认配置与保存

文件不存在时使用内存默认值，菜单／命令触发保存后写入；新默认不会覆盖已保存有效值。菜单应用即时生效。手动编辑时先关闭游戏、重启读取，没有 `/mpaclient reload`。

```json
{
  "enabled": true,
  "showSelf": true,
  "hideVanillaPlayer": true,
  "hideVanillaEquipment": true,
  "interpolationTicks": 2,
  "followServerTimeline": false,
  "showModelIds": false,
  "defaultHeaddress": true,
  "defaultBlueTexture": false,
  "localActionLocked": false,
  "privateSyncEnabled": false,
  "favorites": [],
  "modelProfiles": {},
  "wheelPreferences": {
    "source": "client",
    "clientPage": 0,
    "serverPage": 0,
    "keepOpen": false
  },
  "localAppearance": {
    "enabled": false,
    "modelId": "openysm_default",
    "scale": 1.0,
    "offsetX": 0.0,
    "offsetY": 0.0,
    "offsetZ": 0.0
  }
}
```

| 字段 | 类型与含义 |
| --- | --- |
| `enabled`、`showSelf`、`hideVanillaPlayer`、`hideVanillaEquipment` | 布尔；对应总渲染与三个显隐，默认见上文 |
| `followServerTimeline` | 布尔，默认 `false`：普通动作本地计算，服务器手动动作和许可仍生效；`true` 仅改用服务器动画时间线。所有模式的位置、角度和插值均复用原版本帧渲染状态；服务器轨迹只供诊断。仅 JSON 配置，改变后重握手；私人模型使用本地动画 |
| `interpolationTicks` | 整数，默认 `2`，限制 `0–6`；仅服务器轨迹模式使用采样缓冲，即时模式不额外拖后位置 |
| `showModelIds` | 布尔，默认 `false`；图库“ID”切换名称／ID，标题最多两行 |
| `defaultHeaddress`、`defaultBlueTexture` | 布尔，默认 `true`／`false`；仅 `openysm_default` 的红蝴蝶结／蓝色皮肤兼容默认，已保存作者变量／皮肤优先；快捷按钮即时保存 |
| `localActionLocked` | 布尔，默认 `false`；移动是否继续私人额外动作，CLIENT 轮盘中心或主页设置调整，不改变真实姿态 |
| `privateSyncEnabled` | 布尔，默认 `false`；显式私人分享选择，须服务器允许，关闭可随时撤回 |
| `favorites` | 有效私人 ID 的字符串数组，最多 64 项；卡片右上角或左侧收藏保存，不改变模型 |
| `wheelPreferences` | `source` 为 `client`／`server`；两种来源页码分别为 `0–127`，显示时限制至实际页数；`keepOpen` 默认 `false`。记忆显式选择，但新伪装实例仍自动 SERVER |
| `localAppearance` | `enabled` 布尔；有效 `modelId` 字符串；`scale` 有限数字 `0.05–8`；XYZ 有限数字各 `-32–32` 方块，沿世界轴，Y 正值向上。用户缩放 `1` 保留作者初始比例 |
| `modelProfiles` | 按私人模型 ID 保存 `textureId`、作者 `variables` 与 `radioSelections`，见下文 |

配置须小于 64 KiB。私人段缺字段、类型／范围／ID 无效，或仍使用被移除的内置 `ysm_01_jk`／`ysm_02_jk` 时，整段回到上面的未启用默认值。轮盘容器无效重置轮盘段，单字段无效只使用该字段默认值；未知未来字段忽略。外层读取失败会记日志，先读成功的字段可能保留，不保证损坏文件整体重置；保存只写实现认识的字段。

旧配置同时缺少 `hideVanillaPlayer` 与 `hideVanillaEquipment` 时，首次读取将 `showSelf` 迁移为 `true`，新字段各取隐藏默认；已有任一新字段时保留显式 `showSelf`，三个字段不联动。

服务器发现／校验频率属于服务器 YAML，不是客户端 JSON；默认观众发现 40 tick、有效性复查 20 tick、原生伪装发现 100 tick，动画与物理每 tick 推进。增量状态按两端能力协商，旧客户端仍收完整状态，见 [性能配置](PERFORMANCE.md)。

### 作者配置、预览与恢复

“外观设置”中的本地启停、三个显隐、缩放和 XYZ 是草稿；“保存”应用并留在页面，“保存并预览”启用私人外观、恢复伪装显示并切第三人称。默认模型头饰／皮肤快捷按钮立即保存。作者配置读取自己的 `config_forms`：独立页 checkbox／range／radio 点击“应用并保存”才提交，J 轮盘关联表单按交互执行作者脚本并保存；皮肤按钮依作者纹理列表切换且立即保存，无需先启用模型。滑条／数值输入使用作者步长，长表单滚动裁切，组名／标题／说明／选项读取作者语言属性。

`modelProfiles` 最多 32 个模型，每模型最多 128 个作者变量、64 个 radio 选择；缺省用作者默认，无效整段忽略，保存失败保留原配置。数字变量须有限、绝对值不超过 1,000,000；`variable.roaming.<name>` 每模型最多 64 项、名称最多 32 字符。本人 WORLD BODY 的 roaming 脏变更合并原子保存，预览／远端不会回写；控制器运行状态、弹簧物理与查询缓存不持久化。重新选择、进入世界和重启恢复已保存皮肤／变量／radio。

“重置模型配置”只重置当前模型皮肤／作者变量／radio。“恢复默认”还恢复私人默认模型、缩放 `1`、XYZ `0`、默认皮肤／蝴蝶结并关闭私人外观，清理当前及默认模型 profile；不重置总渲染、三个显隐、轨迹、收藏或其他 profile。`/mpaclient local reset` 只重置 `localAppearance`。

## 客户端命令

命令需连接世界；模型／动作 ID 支持 Tab 补全，`local model` 接受子命令后的完整 ID（可含普通空格）。

| 命令 | 作用 |
| --- | --- |
| `/mpaclient`、`/mpaclient settings`、`/mpaclient local` | 打开同一主页，不隐式选用私人模型 |
| `/mpaclient status` | 连接、资产／同步阶段、hash 前 12 位、来源与错误、即时／轨迹、三个显隐、私人加载／分享、已发生查询降级的名称／次数／实际默认值 |
| `/mpaclient toggle` | 保存总渲染开关，重新开启等待资源／接管就绪 |
| `/mpaclient local model <ID>` | 选择并启用私人模型、开启总渲染，保留 XYZ／缩放及已有 `showSelf` |
| `/mpaclient local play <ID>`、`local stop` | 播放／停止私人手动动作，不请求服务器动作 |
| `/mpaclient local off`、`local reset` | 关闭私人外观且保留参数／恢复私人默认配置并关闭 |
| `/mpaclient preview <ID>`、`preview off` | 旧临时预览／停止：需在世界中且无本人服务器绑定，不保存私人选择或主动下载。01／02 示例需旧 MPA 资源包索引；一般使用图库 |

## 排障

| 现象 | 检查 |
| --- | --- |
| 渲染开启但本人看不见 | F5 切第三人称，启用“伪装显示”，再查加载与服务器本人观看许可；玩家／装备按钮不隐藏伪装 |
| 仍显示 ME／接管加载中 | `/mpaclient status` 与服务器 `/meplayeractions status` 查完整原模型、SHA／解析／纹理／租约及当前观众许可；未就绪保留服务器模型，默认主动推送不要求旧资源包索引；当前功能建议两端用 0.5.0 |
| 私人模型／编辑暂时不见 | 新伪装默认 SERVER；手动 CLIENT 需当次 ready／ACK，重载后等待恢复；解除伪装会恢复旧私人选择，可用 `local off` 关闭 |
| 装备缺失或作者衣物仍在 | 私人外观默认装备隐藏，可独立显示；服务器伪装强制隐藏。作者自带几何属于伪装，主副手物品保留 |
| 点卡片后世界没变 | 点击仅预览，加载成功后“使用模型”或保存启用 |
| 无服务器动作／分享按钮不可用 | 单机／无插件服务器用 CLIENT 本地动作；分享需服务器协商、开关与上传权限，服务器伪装时暂停私人分享。观看者只需观看权限，不必分享自己的模型；查看页面状态／status，无自动缺同步聊天提示 |
| 导入不出现／失败 | 检查专用目录、有效 ID、普通文件／目录、自包含资源与预算，点击刷新；链接、外部路径、需额外密钥缓存和不支持几何会拒绝 |
| 加载成功但动作与参考项目不同 | 核对 [支持矩阵](YSM_COMPATIBILITY.md) 的查询、控制器、locator 和外部联动；未绑定查询可能为 `0`，显式 `null` 用作者默认表达式；status 只记录实际发生的降级 |
| 默认皮肤／头饰快捷项不见 | 只用于 `openysm_default`；其他模型用自身“作者配置／皮肤”或轮盘齿轮 |
| 重启未恢复设置 | 确认当前实例配置，关游戏再编辑重启；查类型／范围／日志及 64 KiB 限制 |

## 实现细节与依据

OWNER 预览使用当前玩家库存与世界上下文；SELECTED 左侧草稿和 CARD 卡片各用独立假人、作者预览动画与 profile，不继承当前库存。三个场景的时钟、控制器、物理独立，纹理图集共享；详情预览使用 OWNER 上下文。拖动为 `pitch -= dy`、`yaw += 1.5 × dx`。卡片固定 52×90、55／93 步长，空间足够五列两行，窄屏减少数量；标题在 45 像素内最多两行居中。作者背景／前景覆盖 90 高度，模型视口 76、裁切 70，不影响左侧预览。

YSM／服务器／普通 BBModel GUI 使用原生离屏深度渲染（RGBA8／DEPTH32、LEQUAL、深度写入）；OWNER 持物沿作者骨骼提交。私人 YSM BODY 与 GUI 作者比例缺省 `height_scale=width_scale=0.7`，按来源映射 X／Z 用 `height_scale`、Y 用 `width_scale`；身体持物／装备继承父变换，与用户 `scale` 分开。服务器与普通 BBModel 作者比例为单位值，第一人称手臂、载具／投射物不套 BODY 比例。

配置依据 [ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java)／[LocalAppearanceSettings](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceSettings.java)；操作和来源依据 [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java)／[PlayerModelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/PlayerModelScreen.java)／[AnimationWheelScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/AnimationWheelScreen.java)。源码导航、构建与验证见 [CONTRIBUTING](../CONTRIBUTING.md)；逐版本场景与验收记录见 [历史文档](history/README.md)，历史结果不代替当前实机验收。
