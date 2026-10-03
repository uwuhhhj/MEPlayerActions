# 客户端配置与显示排查（0.4.2）

[文档索引](README.md) · [安装和使用](../README.md) · [模型同步与部署](MODEL_DELIVERY.md) · [YSM 兼容范围](YSM_COMPATIBILITY.md)

## 先区分两类本地模式

**服务器伪装的本地渲染接管默认开启。** 顶层 `enabled=true` 开启客户端模型渲染，`followServerTimeline=false` 让服务器授权的普通动作按客户端原版玩家实体即时生成；服务器的手动动作、模型身份、权限和可见范围仍有效。0.4.2 默认由服务器主动同步完整原模型，授权绑定与接管就绪后才显示客户端模型；未就绪时由服务器模型后端显示，不需要 MPA 资源包索引。

**私人本地外观初始未启用。** `localAppearance.enabled=false` 只表示未用私人模型覆盖自己在本机看到的外观。它不关闭上面的服务器伪装接管。主动选用私人模型后，只有自己看到这份选择，其他玩家仍看到服务器决定的外观。

| 操作或字段 | 私人模型 | 服务器伪装接管 | 其他玩家在服务器上看到的外观 |
| --- | --- | --- | --- |
| 顶层 `enabled=false` | 停止客户端绘制，保留设置 | 停止接管并释放显示 | 由服务器决定 |
| `localAppearance.enabled=false` / `local off` | 关闭私人替换 | 保持可用 | 不改变 |
| 启用私人模型 | 开启总渲染开关，用私人模型显示自己 | 其他玩家的授权模型照常；本机自己的显示优先用私人模型 | 不改变 |
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
| `enabled` | 布尔 / `true` | 全部客户端自定义模型渲染总开关，默认自动接管服务器授权伪装。动作列表“客户端渲染”或 `/mpaclient toggle` 切换。 |
| `showSelf` | 布尔 / `true` | 控制本机绘制本人模型，通常在第三人称查看。动作列表“第三人称本人”或模型设置“本人”调整；其他玩家不受影响。第一人称仍不显示完整身体。 |
| `followServerTimeline` | 布尔 / `false` | 默认按客户端原版实体即时计算服务器模型位置和普通动作；`true` 改用服务器拖后轨迹和动画层。当前由 JSON 配置，没有独立客户端命令或菜单按钮。私人模型仍按本地实体动作显示。 |
| `interpolationTicks` | 整数 / `2` | 服务器轨迹模式的采样缓冲，读取时限制到 `0–6` tick。默认即时模式不额外拖后位置。 |
| `showModelIds` | 布尔 / `false` | 图库卡片第二行显示模型 ID；默认显示模型来源。图库“ID”按钮保存。 |
| `defaultHeaddress` | 布尔 / `true` | 内置 `openysm_default` 私人模型显示红色蝴蝶结的兼容默认值；已保存的模型表单变量优先。该模型快捷按钮立即保存并更新预览/实际参数。 |
| `defaultBlueTexture` | 布尔 / `false` | 内置默认模型的兼容皮肤默认值；`true` 为蓝色。已保存的 `modelProfiles` 皮肤优先；该模型快捷按钮保存并重新加载私人模型/预览。 |
| `localActionLocked` | 布尔 / `false` | 是否让移动继续保留私人额外动作；G 轮盘“动作锁定”调整并保存，不锁定菜单或真实人物姿态。 |
| `modelProfiles` | 对象 / `{}` | 按私人模型 ID 保存皮肤、作者表单变量和 radio 选择，详见下文。 |
| `favorites` | 字符串数组 / `[]` | 收藏的有效私人模型 ID，最多 64 项。图库卡片右上角或左侧收藏按钮保存；收藏筛选不改变人物模型。 |

### 私人外观选项

| `localAppearance` 字段 | 类型 / 默认值 | 范围与含义 |
| --- | --- | --- |
| `enabled` | 布尔 / `false` | 是否用私人模型替换自己在本机看到的外观。与顶层总开关是不同设置。 |
| `modelId` | 字符串 / `openysm_default` | 内置默认模型；独立文件用 `local:文件名.bbmodel`，YSM 文件夹用 `ysm:文件夹名`。从图库或 Tab 补全取 ID。 |
| `scale` | 数字 / `1.0` | 在原模型尺寸上缩放，`0.05–8`。 |
| `offsetX` | 数字 / `0.0` | 世界 X 轴位置偏移，`-32–32` 方块。 |
| `offsetY` | 数字 / `0.0` | 世界 Y 轴位置偏移，`-32–32` 方块；正值向上。 |
| `offsetZ` | 数字 / `0.0` | 世界 Z 轴位置偏移，`-32–32` 方块。 |

模型 ID 最长 128 个字符。文件/文件夹名不允许空名、点号开头、`..`、目录分隔符、控制字符或非法路径字符；它是专用目录内的名字，不能写绝对路径。文件放在 `config/meplayeractions/models/`，图库“文件夹”可打开此目录，“刷新”重新扫描和解码。

## 显示优先级与视角

1. 顶层 `enabled=false` 时，客户端不绘制自定义模型，服务器后端或原版人物继续显示；私人设置仍保留。
2. 总开关开启、私人模型完成解析和纹理准备后，本机自己的显示优先使用私人模型，其他玩家仍用其服务器授权模型。
3. 在支持同步的服务器上，私人替换先等待握手和完整快照；已知本人服务器伪装存在时，还需本人服务器显示接管就绪。服务器模型尚未同步或恢复期间不会用私人模型叠在 ME 回退显示上，设置会保留并等待恢复。
4. 没有启用可显示的私人模型时，有效服务器绑定使用当次授权推送或经校验的服务器缓存；哈希和纹理校验通过、租约有效后本地接管，否则保持服务器后端显示。
5. 旧临时预览只在没有本人服务器绑定时显示，并低于已启用的私人外观；没有可用自定义模型时回到服务器显示或原版人物。

`showSelf=false` 只禁止本人自定义模型绘制，实例动画脚本和物理仍会推进。原版人物可能已被当前伪装隐藏，所以即使“客户端渲染：开启”，本人仍可能完全看不见。需要查看本人时，把“第三人称本人”改为显示并按 F5。服务器自身的可见权限仍有效，客户端设置不能让服务器授予未允许的绑定。

第一人称跳过自己的完整模型，`showSelf=true` 也不会显示身体。私人 YSM 有可用 `arm/fp_arm` 组件时绘制作者手臂，第一人称物品继续使用原版路径；缺少组件时回退。服务器伪装接管按其隐藏规则处理原版手臂。

## 图库、保存与恢复

按 Y 打开图库，点击卡片仅改变浏览草稿。点击“使用模型”才启用私人选择、开启总渲染，并将本人显示设为 `true`；当前保存的缩放和 XYZ 保留。

模型设置中的“本地”“本人”、缩放与 XYZ 是待保存草稿。“保存”应用草稿并保留页面；“保存并预览”明确启用私人外观和本人显示，在世界中切到第三人称。GUI 模型预览自动居中，并读取作者 `preview_animation`、旋转及背景／前景参数；作者禁止旋转时无法拖动视角。XYZ 与缩放用于世界中的人物显示。“关闭本地”立即关闭私人替换。默认模型的头饰/皮肤按钮立即保存，不等待页面的“保存”。

图库/模型设置“恢复默认”恢复私人默认模型、缩放 `1`、XYZ `0`，关闭私人外观，并恢复默认皮肤和显示蝴蝶结。模型设置页还清理当前模型及默认模型保存的作者配置；它不重置渲染总开关、本人显示、轨迹选项、收藏或其他模型配置。`/mpaclient local reset` 只恢复私人 `localAppearance` 配置，不改头饰/皮肤等顶层选项。

普通 YSM 文件夹已接入作者动态表单、动作分类、控制器 `on_entry/on_exit`、原版动作、第一人称手臂及私人组件；完整边界见 [YSM 兼容范围](YSM_COMPATIBILITY.md)。本轮独立客户端实机已验证本地 OGG 流式播放、原版资源包声音、真实粒子、按实例停播，以及资源重载和重置后的释放。外部模组动画集成、加密 `.ysm`、网格绑定和 OpenYSM 原网络协议仍未实现。`ysm_01_jk` / `ysm_02_jk` 服务器示例不内置在客户端 JAR。

### 作者配置、皮肤与按模型保存

模型设置页的“作者配置 / 皮肤…”读取该模型自己的 `config_forms`；动作列表和 G 轮盘的齿轮可直接进入关联配置组。checkbox/range/radio 的修改先作为草稿，点击“应用并保存”才应用。当前应用入口接受表单变量、临时变量和数学表达式，不执行任意世界查询、外部模组或 FX 脚本。皮肤按钮在作者纹理列表中依次切换并立即保存；此操作不需要先启用私人模型。

`modelProfiles` 以模型 ID 为键，每项包含 `textureId`、`variables` 和 `radioSelections`。缺省项使用作者默认值；保存后重新选择模型、进入世界或重启仍可恢复。最多保存 32 个模型，每个模型最多 128 个表单变量、64 个 radio 选择，配置文件仍须小于 64 KiB。无效 `modelProfiles` 会被忽略，不因此更改服务器权限；保存失败会保留原配置。物理状态、查询缓存和控制器运行状态不写入这里。

“重置模型配置”只恢复该模型的作者默认皮肤、表单变量及 radio 选择，不改变私人启停、缩放或 XYZ。所有作者参数仅自己可见，不同步到服务器，也不改变服务器模型权限。

## 按键与客户端命令

按键可在 Minecraft 控制设置中改绑。默认 Y 为图库，N 为动作列表，G 为动作轮盘，F5 为游戏视角切换。轮盘可悬停后松开入口键、点击或按数字 1–8 选择，左右箭头/Page Up/Page Down/滚轮翻页；“保持打开”只保留菜单；“动作锁定”决定移动时是否取消私人额外动作，不改变真实姿态。

| 命令 | 作用 |
| --- | --- |
| `/mpaclient` | 打开动作列表。 |
| `/mpaclient settings` | 打开私人模型图库。 |
| `/mpaclient local` | 同样打开私人模型图库。 |
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

动作列表和轮盘会根据私人启用状态及本人服务器模型就绪情况选择初始页，也可以手动切换“本地动作”/“服务器动作”。私人 YSM 按作者 extra 入口及分类显示动作，而不是把全部 115 个主库 clip 都塞入轮盘；内置默认模型有八个作者入口，配置关联显示齿轮。私人动作和配置只影响自己看到的动画；服务器动作会请求服务器并同步给其他观看者。服务器动作和真实坐下、爬行、重置按钮需要服务器同步与本人服务器模型就绪。只启用私人模型不会满足这些服务器条件。

## 常见显示排查

| 现象 | 检查与处理 |
| --- | --- |
| 新安装想自动替换服务器伪装 | 默认已开启；无需先启用私人模型。确认当前实例装有 0.4.2 模组、服务端支持主动模型推送，再看 `/mpaclient status` 是否完成接管。 |
| 配置里 `localAppearance.enabled=false` | 这是私人选择未启用，服务器伪装本地接管仍可开启；检查顶层 `enabled`。 |
| 渲染开启但本人看不到 | 先按 F5 查看第三人称，再检查 N 页“第三人称本人：显示”。`showSelf=false` 不会随总开关开启而自动改回；用命令选择私人模型也保留它。图库“使用模型”或“保存并预览”会明确开启本人显示。 |
| 其他玩家模型正常，自己的模型隐藏 | 本人显示开关只作用自己；同时检查服务器是否允许本人观看该伪装。 |
| 仍看到 ME 模型，状态加载中或接管未就绪 | 查看 `/mpaclient status` 的资产模式、同步阶段及服务器资产来源/原因；服务器侧 `/meplayeractions status` 也能查看本人资产。确认完整原模型、当次观众许可和匹配绑定。推送、SHA、解析、纹理或租约失败保留 ME；0.4.2 默认路线不需要旧资源包索引。 |
| 私人设置已启用却显示“等待服务器显示接管” | 当前服务器快照或本人伪装接管尚未就绪；先恢复服务器模型同步，或解除本人服务器伪装再使用私人外观。不要据此关闭渲染总开关。 |
| 图库选择后人物没有变 | 卡片只浏览；等待真实预览加载成功，再点击“使用模型”或保存启用。 |
| 单人或无插件服务器没有服务器动作 | 正常。选用私人模型后使用“本地动作”；真实服务器姿态操作仍需要相应服务器后端。 |
| 导入模型不出现或加载失败 | 检查专用目录、有效 ID、普通文件/目录、嵌入 PNG 或 YSM 必需文件，点击刷新。路径链接、外部资源路径、加密 `.ysm` 与超出解析范围的资产会拒绝加载。 |
| 模型加载成功但表情／动作与 OpenYSM 不同 | 加载成功不表示完全兼容；核对 [支持矩阵](YSM_COMPATIBILITY.md) 的查询、控制器、定位骨骼和外部模组动画族。未绑定查询可能取 `0`，显式 `null` 使用作者默认表达式；模型仍可能显示但行为不同。 |
| 默认模型皮肤/头饰选项不见了 | 快捷按钮只用于 `openysm_default`；其他 YSM 模型通过“作者配置 / 皮肤…”或配置齿轮使用自身皮肤和表单。 |
| 重启后选项未按预期恢复 | 确认编辑的是当前实例配置；关闭游戏后编辑并重启。检查字段类型/范围及日志，私人配置无效会回退为未启用默认模型；外层字段错误不保证整体重置。 |

## 服务器模型缓存

0.4.2 的服务器模型缓存在当前实例的 `config/meplayeractions/cache/`，按原始文件 SHA-256 命名，离服后保留，总量限制为 128 MiB、最多 512 个有效文件，按最近使用时间裁剪。命中缓存后仍要校验模型、准备纹理并取得当次绑定的服务器确认；缓存文件不授予当前服务器模型使用权，也不会自动加入私人图库。

资源重载会撤销显示接管并重新准备纹理，完整服务器推送模型与有效在途下载仍保留；重新确认后恢复显示。断开和世界切换清理当前绑定及下载授权，磁盘缓存继续保留。

私人文件继续放入 `config/meplayeractions/models/`，私人选择继续保存在 `config/meplayeractions-client.json`。三个位置用途不同。缓存和模型同步无需新增 JSON 开关；服务器决定当前可见、授权实例需要的模型，客户端不能选任意服务器模型下载。更多边界见 [模型同步与部署](MODEL_DELIVERY.md)。

## 源码依据

当前字段与保存依据 [ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java)、[LocalAppearanceSettings](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceSettings.java)；命令与按键依据 [MEPlayerActionsClient](../client/src/main/java/com/simmc/meplayeractions/client/MEPlayerActionsClient.java)。显示优先级与普通动作依据 [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java)、[LocalAppearanceVisibility](../client/src/main/java/com/simmc/meplayeractions/client/LocalAppearanceVisibility.java)、[EntityAnimationController](../client/src/main/java/com/simmc/meplayeractions/client/EntityAnimationController.java)；视角过滤依据 [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java)。作者动作、表单与按模型保存依据 [ModelActionMenu](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelActionMenu.java)、[ModelConfigSchema](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigSchema.java)、[ModelConfigScreen](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelConfigScreen.java)。源码链接用于仓库和源码包。
