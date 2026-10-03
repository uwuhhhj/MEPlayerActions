# 客户端配置与显示排查（0.4.3）

[文档索引](README.md) · [安装和使用](../README.md) · [模型同步与部署](MODEL_DELIVERY.md) · [YSM 兼容范围](YSM_COMPATIBILITY.md)

## 先区分两类本地模式

**服务器伪装的本地渲染接管默认开启。** 顶层 `enabled=true` 开启客户端模型渲染，`followServerTimeline=false` 让服务器授权的普通动作按客户端原版玩家实体即时生成；服务器的手动动作、模型身份、权限和可见范围仍有效。0.4.3 默认由服务器主动同步完整原模型，授权绑定与接管就绪后才显示客户端模型；未就绪时由服务器模型后端显示，不需要 MPA 资源包索引。

**私人本地外观初始未启用。** `localAppearance.enabled=false` 只表示未用私人模型覆盖自己在本机看到的外观。它不关闭上面的服务器伪装接管。主动选用私人模型后，只有自己看到这份选择，其他玩家仍看到服务器决定的外观。

| 操作或字段 | 私人模型 | 服务器伪装接管 | 其他玩家在服务器上看到的外观 |
| --- | --- | --- | --- |
| 顶层 `enabled=false` | 停止客户端绘制，保留设置 | 停止接管并释放显示 | 由服务器决定 |
| `localAppearance.enabled=false` / `local off` | 关闭私人替换 | 保持可用 | 不改变 |
| 启用私人模型 | 在 CLIENT 来源中开启总渲染，用私人模型显示自己 | 其他玩家的授权模型照常；本人有服务器伪装时还需当前绑定就绪 | 不改变 |
| 新的本人服务器伪装实例 | 自动 SERVER，私人显示和编辑暂时停用，保存内容不变 | 优先使用服务器伪装，加载中也如此 | 由服务器决定 |
| 主页显式切到 CLIENT / SERVER | CLIENT 可用私人选择；SERVER 暂停私人选择 | 只切换本机本人显示来源，不撤销服务器绑定 | 不改变 |
| 解除本人服务器伪装 | 恢复原来保存的私人选择 | 结束该伪装 | 由服务器决定 |
| `showSelf=false` | 不绘制本人模型 | 不绘制本人客户端模型，其他人的模型不受影响 | 不改变 |

新安装默认值不会覆盖已有用户配置；升级仍读取原 JSON 中有效的已保存选项。

## 配置文件与保存

文件位于**当前 Minecraft 实例**的 `config/meplayeractions-client.json`，与服务器的 `plugins/MEPlayerActions/config.yml` 分开。文件不存在时使用内存默认值，在菜单或命令触发保存后写入。菜单应用立即生效；手动编辑完整 JSON 时先关闭游戏，再重启读取。客户端没有 `/mpaclient reload` 命令。

文件须小于 64 KiB。字段使用下表的 JSON 类型；私人外观的数字必须有限且在范围内。私人外观字段缺失、类型/范围/ID 无效，或仍填写旧内置 ID `ysm_01_jk` / `ysm_02_jk` 时，私人配置会回到 `enabled=false`、`openysm_default`、缩放 `1`、XYZ `0`。服务器示例通过服务器授权同步后接管；私人导入须使用本地文件 ID。

读取错误会记录日志。外层读取若在后续字段失败，前面已经读取的选项可能仍然生效；不要将任意损坏文件理解为所有字段都会一起重置。保存操作仅写当前实现认识的字段。

## 完整默认配置

```json
{
  "enabled": true,
  "showSelf": true,
  "interpolationTicks": 2,
  "followServerTimeline": false,
  "showModelIds": false,
  "defaultHeaddress": true,
  "defaultBlueTexture": false,
  "localActionLocked": false,
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

### 顶层选项

| 字段 | 类型 / 默认值 | 含义与操作入口 |
| --- | --- | --- |
| `enabled` | 布尔 / `true` | 全部客户端自定义模型渲染总开关，默认自动接管服务器授权伪装。玩家模型主页的渲染总开关或 `/mpaclient toggle` 切换。 |
| `showSelf` | 布尔 / `true` | 控制本机绘制本人模型，通常在第三人称查看。玩家模型主页的本人显示选项或模型设置“本人”调整；其他玩家不受影响。第一人称仍不显示完整身体。 |
| `followServerTimeline` | 布尔 / `false` | 默认按客户端原版实体即时计算服务器模型位置和普通动作；`true` 改用服务器拖后轨迹和动画层。当前由 JSON 配置，没有独立客户端命令或菜单按钮。私人模型仍按本地实体动作显示。 |
| `interpolationTicks` | 整数 / `2` | 服务器轨迹模式的采样缓冲，读取时限制到 `0–6` tick。默认即时模式不额外拖后位置。 |
| `showModelIds` | 布尔 / `false` | 图库卡片第二行显示模型 ID；默认显示模型来源。图库“ID”按钮保存。 |
| `defaultHeaddress` | 布尔 / `true` | 内置 `openysm_default` 私人模型显示红色蝴蝶结的兼容默认值；已保存的模型表单变量优先。该模型快捷按钮立即保存并更新预览/实际参数。 |
| `defaultBlueTexture` | 布尔 / `false` | 内置默认模型的兼容皮肤默认值；`true` 为蓝色。已保存的 `modelProfiles` 皮肤优先；该模型快捷按钮保存并重新加载私人模型/预览。 |
| `localActionLocked` | 布尔 / `false` | 是否让移动继续保留私人额外动作；CLIENT 轮盘中心或主页轮盘选项调整并保存，不锁定菜单或真实人物姿态。 |
| `modelProfiles` | 对象 / `{}` | 按私人模型 ID 保存皮肤、作者表单变量和 radio 选择，详见下文。 |
| `wheelPreferences` | 对象 / 见默认 JSON | 记忆显式来源选择、CLIENT / SERVER 各自页码和选择后保持轮盘；有效来源仍由当前服务器伪装实例决定。 |
| `favorites` | 字符串数组 / `[]` | 收藏的有效私人模型 ID，最多 64 项。图库卡片右上角或左侧收藏按钮保存；收藏筛选不改变人物模型。 |

### 私人外观选项

| `localAppearance` 字段 | 类型 / 默认值 | 范围与含义 |
| --- | --- | --- |
| `enabled` | 布尔 / `false` | 是否用私人模型替换自己在本机看到的外观。与顶层总开关是不同设置。 |
| `modelId` | 字符串 / `openysm_default` | 内置默认模型及三套酒狐可选 ID 见下文；独立文件用 `local:文件名.bbmodel`，YSM 文件夹用 `ysm:文件夹名`。从图库或 Tab 补全取 ID。 |
| `scale` | 数字 / `1.0` | 用户额外均匀缩放，`0.05–8`；私人 YSM 身体先保留作者初始比例，`1` 不覆盖作者比例。常规 BBModel 的作者初始比例保持单位值。 |
| `offsetX` | 数字 / `0.0` | 世界 X 轴位置偏移，`-32–32` 方块。 |
| `offsetY` | 数字 / `0.0` | 世界 Y 轴位置偏移，`-32–32` 方块；正值向上。 |
| `offsetZ` | 数字 / `0.0` | 世界 Z 轴位置偏移，`-32–32` 方块。 |

模型 ID 最长 128 个字符。文件/文件夹名不允许空名、点号开头、`..`、目录分隔符、控制字符或非法路径字符；它是专用目录内的名字，不能写绝对路径。文件放在 `config/meplayeractions/models/`，图库“文件夹”可打开此目录，“刷新”重新扫描和解码。

## 显示来源、优先级与视角

1. 顶层 `enabled=false` 时不绘制客户端自定义模型；私人设置和轮盘记忆保留。
2. 每个新的本人服务器伪装实例自动选择 SERVER。加载中、校验失败或重载期间也不自动启用私人替换；SERVER 页不显示私人模型编辑控件，本地动作与旧本地命令不会隐式切换来源。
3. 在玩家模型主页明确选择 CLIENT，可浏览和编辑私人模型并使用其本地动作。这个选择在同一伪装实例内保留；切回 SERVER 暂停私人显示。服务器自己的绑定、权限及其他观看者不受影响。
4. 本人服务器伪装仍存在时，手动 CLIENT 的私人显示先等待握手、完整快照和当前本人服务器 ready/ACK，防止与 ME 回退重叠。选择 CLIENT 本身不确认租约，也不把加载失败伪装成成功。
5. 解除服务器伪装后回到 CLIENT，恢复之前保存的私人启用、模型、缩放、XYZ、皮肤和作者变量；不会删除本地模型或重置文件。没有可用私人模型时显示原版人物。
6. 临时旧预览仍只在没有本人服务器绑定时可用，不替代有效服务器资产。

`showSelf=false` 只禁止本人自定义模型绘制，实例脚本和物理仍会推进。原版人物可能已被伪装隐藏，因此总渲染开启时本人仍可能看不见。需要查看自己时在玩家模型主页开启本人显示，再按 F5；客户端选项不能扩大服务器的本人观看权限。

第一人称跳过自己的完整模型。私人 YSM 有可用 `arm/fp_arm` 组件时显示作者手臂，手中物品保留原版路径；缺少组件时回退。任何已识别为服务器伪装的玩家，客户端都隐藏其原版盔甲、披风和鞘翅，包含本人手动 CLIENT 覆盖的情况；手中物品继续显示。没有服务器伪装时，私人模型可按其定位骨骼绘制装备。

### 来源与页码记忆

`wheelPreferences.source` 只接受 `client` / `server`，默认 `client`，保存最近的显式来源选择；新服务器伪装实例仍自动 SERVER，不能用磁盘记忆绕过这条规则。`clientPage`、`serverPage` 分别默认 `0`，有效范围 `0–127`，界面按当前真实目录限制到可用页。`keepOpen=false` 表示选动作后关闭轮盘，与 `localActionLocked` 的移动取消策略分开。

缺少整段时使用轮盘默认值。容器格式错误只重置轮盘段；单字段类型／范围错误或未知来源只对该字段取默认值，保留有效兄弟字段及已保存的私人模型配置。未知未来字段会忽略。

## 图库、保存与恢复

用 J 轮盘右上角设置齿轮或 `/mpaclient settings` 打开玩家模型主页，在 CLIENT 页直接浏览图库，图库按内置、YSM 文件夹和 BBModel 来源分组，只列出安全模型目录内的有效顶层模型；不提供任意文件系统浏览或向服务器上传。点击卡片仅改变待选草稿，主页左侧继续显示当前正在使用的外观。点击“使用模型”才启用私人选择、开启总渲染，并将本人显示设为 `true`；当前保存的缩放和 XYZ 保留。

内置可选模型为 `openysm_default`、`wine_fox_01_taisho_maid`（酒狐）、`wine_fox_02_new_year`（新春酒狐）、`wine_fox_03_astronaut`（宇航员酒狐）。三套酒狐保留参考源码中的原始资源与作者信息，使用 [CC BY-NC-SA 4.0](../THIRD_PARTY_NOTICES.md#openysm-wine-fox-model-assets-cc-by-nc-sa-40)，模型资产仅供非商用，分发与改编须保留署名、许可和相同许可要求。它们是新增可选项，不改变初始或已保存的模型选择；皮肤和可用动作来自各模型自身清单，不能把旧默认模型的选项套到所有模型上。

模型设置中的“本地”“本人”、缩放与 XYZ 是待保存草稿。“保存”应用草稿并保留页面；“保存并预览”明确启用私人外观和本人显示，在世界中切到第三人称。YSM 主页左侧预览当前使用的外观，详情预览所选模型；二者按原版库存尺度采样当前玩家姿态，可拖动视角，保持 GUI 第三人称查询上下文。模型卡片使用独立假人播放作者 `preview_animation`，作者背景／前景和禁止预览旋转参数用于卡片，不覆盖左侧玩家预览。两种预览的时钟、控制器和物理独立，纹理图集共享；YSM 模型经 Minecraft 原生离屏渲染提交，使用颜色／深度附件及逐顶点深度测试，常规 BBModel 继续使用原有二维投影与自动取景。本轮已实测作者初始比例、原生 RGBA8／DEPTH32 附件及 LEQUAL 深度执行，并人工核对默认模型眼白、完整头部及三套酒狐画面，见 [本轮验证范围](YSM_COMPATIBILITY.md#043-本轮验证范围)。GUI 持物状态用于姿态采样，本轮未迁移 GUI 手持物品几何绘制。XYZ 与缩放用于世界中的人物显示。“关闭本地”立即关闭私人替换。默认模型的头饰/皮肤按钮立即保存，不等待页面的“保存”。

私人 YSM 身体与 GUI 的作者初始比例由 `height_scale`／`width_scale` 决定，缺省各为 `0.7`；按上游实际映射，X／Z 使用 `height_scale`，Y 使用 `width_scale`。这层比例与用户 `scale` 分开，身体的手持物品与装备继承身体父变换；原版玩家／用户变换仍位于外层。服务器模型和常规 BBModel 使用单位作者比例；第一人称手臂、载具及投射物没有套用这层 BODY 比例。源码入口为 [YsmRenderScale](../client/src/main/java/com/simmc/meplayeractions/client/model/YsmRenderScale.java)、[ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) 与 GUI [NativeGuiRenderBackend](../client/src/main/java/com/simmc/meplayeractions/client/ui/NativeGuiRenderBackend.java)。

模型设置“恢复默认”恢复私人默认模型、缩放 `1`、XYZ `0`，关闭私人外观，并恢复默认皮肤和显示蝴蝶结。模型设置页还清理当前模型及默认模型保存的作者配置；它不重置渲染总开关、本人显示、轨迹选项、收藏或其他模型配置。`/mpaclient local reset` 只恢复私人 `localAppearance` 配置，不改头饰/皮肤等顶层选项。

普通 YSM 文件夹已接入作者动态表单、动作分类、控制器 `on_entry/on_exit`、原版动作、第一人称手臂及私人组件；完整边界见 [YSM 兼容范围](YSM_COMPATIBILITY.md)。0.4.2 的独立客户端历史实机已验证本地 OGG 流式播放、原版资源包声音、真实粒子、按实例停播，以及资源重载和重置后的释放。外部模组动画集成、加密 `.ysm`、网格绑定和 OpenYSM 原网络协议仍未实现。`ysm_01_jk` / `ysm_02_jk` 服务器示例不内置在客户端 JAR。

### 作者配置、皮肤与按模型保存

模型设置页的“作者配置 / 皮肤…”读取该模型自己的 `config_forms`；J 轮盘的作者配置齿轮在同一轮盘右侧显示关联表单。独立作者配置页的 checkbox/range/radio 修改先作为草稿，点击“应用并保存”才应用；轮盘内关联表单按其交互执行作者脚本并保存。当前应用入口接受表单变量、临时变量和数学表达式，不执行任意世界查询、外部模组或 FX 脚本。皮肤按钮在作者纹理列表中依次切换并立即保存；此操作不需要先启用私人模型。

`modelProfiles` 以模型 ID 为键，每项包含 `textureId`、`variables` 和 `radioSelections`。缺省项使用作者默认值；保存后重新选择模型、进入世界或重启仍可恢复。最多保存 32 个模型，每个模型最多 128 个表单变量、64 个 radio 选择，配置文件仍须小于 64 KiB。无效 `modelProfiles` 会被忽略，不因此更改服务器权限；保存失败会保留原配置。物理状态、查询缓存和控制器运行状态不写入这里。

“重置模型配置”只恢复该模型的作者默认皮肤、表单变量及 radio 选择，不改变私人启停、缩放或 XYZ。所有作者参数仅自己可见，不同步到服务器，也不改变服务器模型权限。

## 按键与客户端命令

按键可在 Minecraft 控制设置中改绑。本模组只注册一个默认 J 轮盘快捷键，F5 仍是原版视角切换。G／Y／N 的独立入口已移除，J 使用新的按键绑定 ID，旧 G 存档不会沿用。经典轮盘按当前页实际动作绘制多边形扇区，可悬停后松键、点击或按数字 1–8 选择；右侧显示作者分类路径、页码、返回及关联表单。右上角单一设置齿轮进入玩家模型主页，CLIENT 中心切换本地动作锁定，SERVER 中心停止手动动作；保持轮盘打开在主页高级选项中调整。

| 命令 | 作用 |
| --- | --- |
| `/mpaclient` | 打开玩家模型主页。 |
| `/mpaclient settings` | 同样直接打开带 CLIENT／SERVER 页的玩家模型主页。 |
| `/mpaclient local` | 打开同一玩家模型主页，不隐式选用私人模型。 |
| `/mpaclient status` | 查看服务器连接与资产模式、接管数量、当前模型 hash 前 12 位、同步阶段、服务器资产来源/原因、即时/轨迹模式、本人显示及私人加载状态。 |
| `/mpaclient toggle` | 切换并保存客户端渲染总开关；重新开启后等待资源和服务器接管就绪。 |
| `/mpaclient local model <模型ID>` | 选择并启用私人模型，保留缩放和 XYZ，并开启渲染总开关；保留已有 `showSelf`。支持模型 ID Tab 补全。 |
| `/mpaclient local play <动画ID>` | 播放已加载私人模型的本地动作，不发送服务器动作请求；支持动作 Tab 补全。 |
| `/mpaclient local stop` | 停止私人手动动作。 |
| `/mpaclient local off` | 关闭私人替换，保留所选模型和参数，不关闭总渲染。 |
| `/mpaclient local reset` | 恢复私人默认配置并关闭私人替换。 |
| `/mpaclient preview <模型ID>` | 临时预览，需在世界中且无本人服务器绑定，不保存私人选择。01/02 旧示例预览仍需含 MPA 索引的资源包；不主动下载服务器模型。内置默认与私人导入预览建议使用图库。 |
| `/mpaclient preview off` | 关闭旧临时预览。 |

客户端命令在连接世界时使用。本地文件 ID 可以含普通空格，`local model` 接收该子命令后面的完整模型 ID。

动作来源在玩家模型主页的 CLIENT／SERVER 顶部按钮切换；轮盘不再放两排来源大按钮或右侧多个跳转按钮。SERVER 页仅展示当前服务器绑定的下发模型及状态，不是任意服务器模型下载列表；SERVER 动作需要服务器同步与本人模型就绪。真实姿态使用服务器 `/meplayeractions pose` 命令。

私人 YSM 使用作者 extra 入口和分类，八个默认作者入口不等同于全部 115 个动画 clip。CLIENT 的本地动作及作者配置不发送服务器动作请求。SERVER 来源暂停本地选择、编辑与动作；先明确切到 CLIENT 后再操作，保存不会改变服务器对其他观看者的外观。

## 常见显示排查

| 现象 | 检查与处理 |
| --- | --- |
| 新安装想自动替换服务器伪装 | 默认已开启；无需先启用私人模型。确认当前实例装有 0.4.3 模组、服务端支持主动模型推送，再看 `/mpaclient status` 是否完成接管。 |
| 配置里 `localAppearance.enabled=false` | 这是私人选择未启用，服务器伪装本地接管仍可开启；检查顶层 `enabled`。 |
| 渲染开启但本人看不到 | 先按 F5 查看第三人称，再检查玩家模型主页的本人显示选项。`showSelf=false` 不会随总开关开启而自动改回；用命令选择私人模型也保留它。图库“使用模型”或“保存并预览”会明确开启本人显示。 |
| 其他玩家模型正常，自己的模型隐藏 | 本人显示开关只作用自己；同时检查服务器是否允许本人观看该伪装。 |
| 仍看到 ME 模型，状态加载中或接管未就绪 | 查看 `/mpaclient status` 的资产模式、同步阶段及服务器资产来源/原因；服务器侧 `/meplayeractions status` 也能查看本人资产。确认完整原模型、当次观众许可和匹配绑定。推送、SHA、解析、纹理或租约失败保留 ME；0.4.3 默认路线不需要旧资源包索引。 |
| 伪装后私人模型和本地编辑不见了 | 新伪装实例默认 SERVER，私人配置仍保留。需要自己的私人选择时在玩家模型主页明确切到 CLIENT；服务器绑定仍未就绪时等待恢复，或解除服务器伪装。 |
| 已手动选 CLIENT，但重载后暂时不显示 | 同一实例的选择保留，私人显示仍需服务器本人绑定重新 ready/ACK，防止与 ME 回退重叠。 |
| 解除伪装后恢复旧私人模型 | 这是保留私人配置的行为；若不再需要，进入 CLIENT 后关闭私人外观。 |
| 伪装时看不到盔甲、披风或鞘翅 | 客户端对服务器伪装隐藏这些装备，手动 CLIENT 本人覆盖也如此；手中物品保留。解除伪装后回到通常装备显示规则。 |
| 图库选择后人物没有变 | 卡片只浏览；等待真实预览加载成功，再点击“使用模型”或保存启用。 |
| 单人或无插件服务器没有服务器动作 | 正常。选用私人模型后使用 CLIENT 动作轮盘；真实服务器姿态操作仍需要相应服务器后端。 |
| 导入模型不出现或加载失败 | 检查专用目录、有效 ID、普通文件/目录、嵌入 PNG 或 YSM 必需文件，点击刷新。路径链接、外部资源路径、加密 `.ysm` 与超出解析范围的资产会拒绝加载。 |
| 模型加载成功但表情／动作与 OpenYSM 不同 | 加载成功不表示完全兼容；核对 [支持矩阵](YSM_COMPATIBILITY.md) 的查询、控制器、定位骨骼和外部模组动画族。未绑定查询可能取 `0`，显式 `null` 使用作者默认表达式；模型仍可能显示但行为不同。 |
| 默认模型皮肤/头饰选项不见了 | 快捷按钮只用于 `openysm_default`；其他 YSM 模型通过“作者配置 / 皮肤…”或配置齿轮使用自身皮肤和表单。 |
| 重启后选项未按预期恢复 | 确认编辑的是当前实例配置；关闭游戏后编辑并重启。检查字段类型/范围及日志，私人配置无效会回退为未启用默认模型；外层字段错误不保证整体重置。 |

## 服务器模型缓存

0.4.3 的服务器模型缓存在当前实例的 `config/meplayeractions/cache/`，按原始文件 SHA-256 命名，离服后保留，总量限制为 128 MiB、最多 512 个有效文件，按最近使用时间裁剪。命中缓存后仍要校验模型、准备纹理并取得当次绑定的服务器确认；缓存文件不授予当前服务器模型使用权，也不会自动加入私人图库。

资源重载会撤销显示接管并重新准备纹理，完整服务器推送模型与有效在途下载仍保留；同一实例的显式 CLIENT／SERVER 来源保留，重新确认后按所选来源恢复显示。断开和世界切换清理当前绑定及下载授权，磁盘缓存继续保留。

私人文件继续放入 `config/meplayeractions/models/`，私人选择继续保存在 `config/meplayeractions-client.json`。三个位置用途不同。缓存和模型同步无需新增 JSON 开关；服务器决定当前可见、授权实例需要的模型，客户端不能选任意服务器模型下载。更多边界见 [模型同步与部署](MODEL_DELIVERY.md)。

## 源码依据

当前字段与保存依据 [ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java)、[LocalAppearanceSettings](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceSettings.java)；命令与按键依据 [MEPlayerActionsClient](../client/src/main/java/com/simmc/meplayeractions/client/MEPlayerActionsClient.java)。显示优先级与普通动作依据 [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java)、[LocalAppearanceVisibility](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceVisibility.java)、[EntityAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/EntityAnimationController.java)；视角过滤依据 [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)。作者动作、表单与按模型保存依据 [ModelActionMenu](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelActionMenu.java)、[ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[ModelConfigScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigScreen.java)。源码链接用于仓库和源码包。
