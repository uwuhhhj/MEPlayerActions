# 0.4.8 图库草稿预览与本地导入补丁

[文档索引](../README.md) · [客户端配置](../CLIENT_CONFIG.md) · [YSM 范围](../YSM_COMPATIBILITY.md) · [0.4.7 原生与同步记录](CLIENT_0_4_7_NATIVE_SYNC.md)

本次仅更新客户端至 **0.4.8**，服务端继续使用 **0.4.7**。补丁修正图库左侧预览选择，以及本地 YSM 导入与私人网络传输混用大小预算的问题。

## 图库预览与使用

在 CLIENT 图库点击模型卡片，所选模型立即成为待选草稿，左侧加载并预览这份草稿，使用该模型的作者预览动画、纹理、profile 和参数。浏览不保存选择、不启用私人外观，也不改变世界中正在使用的模型。加载完成后，点击图库的“使用模型”才应用所选私人外观；加载中或失败不显示旧模型冒充当前选择，失败保留错误反馈。

左侧显示“预览 · 点击使用模型应用”；只有所选内容 hash 与世界当前 binding 一致时标为“当前使用”。这一区分取决于实际内容，不能仅凭模型 ID 推断已经应用。

待选左侧使用独立的 `SELECTED` 假人及预览时钟、控制器和查询，读取所选模型的 profile、纹理与作者参数，不继承当前人物库存或世界动画。所选内容确实已用于世界时，左侧切到 `OWNER` 当前玩家上下文；模型卡片使用另一份 `CARD` 假人。收藏、搜索与目录分页不会自动应用模型；详情页原有显式保存行为继续保留。

## 本地导入与私人分享

本地公开自包含 `.ysm`、ZIP 和 spec 2 文件夹使用独立、有界的本地导入预算。私人同步归档继续受 0.4.7 服务端的 8 MiB 上限及双方协商限制，低 payload 可进一步降低上限。模型能在本机加载，不表示它的完整归档能上传；超出分享预算时记录不可同步原因，保留本地加载与配置。

| 阶段或格式 | 大小上限 |
| --- | --- |
| 本地 YSM 目录资源、手动 ZIP／`.ysm` 原始输入 | 64 MiB |
| 本地 ZIP 展开资源总量、公开 crypto3 解压二进制、导入后源资源 | 各阶段 64 MiB |
| 本地转换后的主模型与组件 JSON 合计 | 64 MiB |
| 本地图片解码输入的编码字节 | 64 MiB，另须通过原图像限制 |
| 独立 `local:` `.bbmodel` | 8 MiB |
| 私人网络 ZIP／完整归档及其展开资源 | 8 MiB，协商可降低 |

文件数、模型／动画及作者脚本仍有数量限制；纹理保留 16,777,216 总像素、8192 像素边长与单张 PNG 6 MiB 等原限制。提高本地字节预算不取消这些检查，也不放宽网络解析入口。

旧版本把 8 MiB 网络预算用于本地导入，导致用户提供的公开 crypto3 `DS鲸鱼娘flash.ysm`（14,375,483 字节）在读取入口失败。原文件的只读解码扫描已确认公开 crypto3、payload 格式 v32、解压后 15,846,023 字节；进一步发现 `parallel3` 某时间戳包含 179 段程序、共 8,155 字节 UTF-8，超过旧版人为设置的 64 段限制。这解释了大小限制以外的第二处导入失败。

本轮原生 YSM 时间线单事件上限改为 256 段，保留每事件 32 KiB UTF-8 文本预算，并完整保留作者程序顺序，不截断内容；普通 BBModel 及声音／粒子事件的 32 项限制保持。固定 Sparkle 的 [二进制读取](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/YSMBinaryDeserializer.java) 与 [AnimationMapper](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bundle/AnimationMapper.java) 按原序保留事件数组，未设置旧版 MPA 的 64 段限制；0.4.7 的旧限制仍属于前版历史记录。

原版 body 动画族也改为沿作者清单顺序装配 `main`／`arm`／`extra`，同名动画由后出现的族覆盖；不再固定顺序并拒绝合法重名。所有原始族资源保留，明确的 `fp_arm` 独立。该规则来自固定 [Sparkle ModelAssemblyFactory](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/model/ModelAssemblyFactory.java) 与 [OpenYSM ModelAssemblyFactory](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/model/ModelAssemblyFactory.java)。

随后完整离线导入检查成功：130 个动画、几何及纹理完成加载，并完成预览网格采样。源文件 SHA-256 为 `ad31abb9a716ae670d63e8d55328ed5ff364753afb09745e83367283a069d7f9`，检查前后相同；原文件不修改，也不收录到安装包或源码 ZIP。这是离线加载与采样结果，没有运行游戏，也没有证明 GPU 绘制或实际画面。

该模型私人分享仍明确因 8 MiB 网络归档预算不可用，本地使用不受这项网络失败阻止。

原生格式和必需资源仍须通过路径、文件数、图像像素、几何及表达式预算检查；需要服务端密钥的缓存不属于公开自包含入口。必需 AVIF 材质仍拒绝，可选 AVIF 作者头像使用占位图。

## 安装与验证范围

退出游戏，移除该实例 `mods/` 中旧 MEPlayerActions 客户端 JAR，放入 0.4.8 客户端 JAR，再启动游戏。原客户端配置和模型目录继续使用，导入自己的文件后在图库显式刷新。已有 0.4.7 服务端和协议保持不变，本轮不生成新服务端安装包。

中央已运行 21 项必要定向单元，全部通过：失败、错误、跳过均为 0；真实原文件完整离线导入及原始 SHA-256 不变也已确认。客户端 Gradle `build -x test` 已通过。最终 ZIP 完整性检查结果见本版交付验证 JSON。

本轮未启动游戏或服务器，不复验历史场景。0.4.7 及更早历史记录不作为 0.4.8 实机通过证明，画面与交互由用户实机测试。
