# 旧兼容模式：客户端资源包格式 v1

[文档索引](README.md) · [当前模型部署](MODEL_DELIVERY.md) · [客户端协议](CLIENT_PROTOCOL.md)

本文保留 **0.4.1 `resource_pack_models` 模式**的格式与离线工具，供维护既有资源包使用。**当前 0.5.0 默认由 MPA 主动推送完整原模型，无需本文索引或合并工具**；ModelEngine／CraftEngine 的原有资源包生成、合并、保护与下发继续用于原版观看者。

旧兼容客户端从已加载的资源包读取模型和 PNG，服务端提供模型身份、hash 与运行状态；原始字节 hash 一致、解析和渲染准备完成后才确认接管，资源缺失或无效时继续由 ME 显示。当前服务端保留此兼容路线，**当前客户端的服务器接管要求协商 `server-push`，不能用资源包索引代替**。本机旧临时预览仍可读取对应包资产。

以下 `0.4.1` 包名、部署路径和命令属于旧模式示例，需使用相应完整原模型；不要求当前用户建立 `plugins/MEPlayerActions/models/`，该目录在现行部署中只是可选覆盖来源。历史包和工具保留。构建工具不调用或修改 ModelEngine，可合并已有完整引擎包或生成独立包。烘焙蓝图不能替代客户端需要的原始骨架、动画、表达式与内嵌 PNG。旧式 `asset_request` 下载另为一条兼容路径，与资源包模式分开。

## 构建命令

使用 Python 3 和 Pillow。Pillow 用于有界 PNG 完整解码及像素等价验证；其余流程使用标准库。如环境没有 Pillow，先运行 `python -m pip install Pillow`。

### 从服务器安装 ZIP 合并

把安装 ZIP 解压到服务器根目录后，先启动服务器并执行 `/meg reload models`，让 ModelEngine 生成包含当前服务器全部模型的完整 `plugins/ModelEngine/resource pack.zip`。然后在服务器根目录运行：

```powershell
python tools/build_client_resource_pack.py `
  --engine-pack "plugins/ModelEngine/resource pack.zip" `
  --model "ysm_01_jk=plugins/MEPlayerActions/models/ysm_01_jk.bbmodel" `
  --model "ysm_02_jk=plugins/MEPlayerActions/models/ysm_02_jk.bbmodel" `
  --output "resourcepacks/MEPlayerActions-0.4.1-merged.zip"
```

将新生成的完整 ZIP 上传为服务器资源包，更新服务器的资源包 URL 和哈希。输出文件已存在时改用新的文件名；工具不会覆盖已有包。

发布附带的完整包是 `ysm_01_jk` / `ysm_02_jk` 两套模型的示例。生产服务器存在其他模型或额外资源时，应以上述服务器自身的完整引擎包作为合并输入，保留其中的全部资源；直接用示例包替换现有包会缺少这些额外资源。若服务器使用了额外的资源包合并流程，`--engine-pack` 应指向该流程产出的完整最终包。

上述命令为完整引擎包保留已有条目，并加入两套示例的客户端原始模型。其他模型需要客户端接管时，为每个模型追加 `--model "模型ID=原始完整.bbmodel路径"`。客户端输入应包含原始骨架、动画、表达式和嵌入 PNG；不能使用已为引擎烘焙成数值动画的蓝图代替。

### 从源码项目构建验收包

在源码项目根目录运行：

```powershell
python tools/build_client_resource_pack.py `
  --engine-pack "build/e2e-server/plugins/ModelEngine/resource pack.zip" `
  --model "ysm_01_jk=examples/models/ysm_01_jk.bbmodel" `
  --model "ysm_02_jk=examples/models/ysm_02_jk.bbmodel" `
  --output "build/unified-resource-pack/MEPlayerActions-0.4.1-e2e-unified.zip"
```

`--engine-pack` 接受任意引擎的完整资源包 ZIP，必须包含根目录 `pack.mcmeta`。引擎原有条目及 `pack.mcmeta` 的内容保持；仅对已完整验证 RGBA 像素一致的引擎 PNG，可在最终副本中统一为原始模型 PNG 的编码，具体改动记录在规范化清单中。输入引擎包不受影响。ZIP 容器使用固定时间、固定权限、排序条目及 DEFLATE 级别 9；相同输入在相同 Python/zlib 环境中输出相同字节，与输入模型参数顺序无关。

生成独立包时省略 `--engine-pack`，显式提供与目标 Minecraft 版本对应的 `--pack-format` 整数；工具不猜测游戏版本。可用 `--description` 设置独立包说明。输出路径必须是新文件，已有输出及输入文件都不会被覆盖；重新构建时指定新的文件名并保留历史包。构建结果输出 JSON，包含最终 ZIP 路径、SHA-256、大小、条目数及模型索引。

## 索引和模型

索引固定为 `assets/meplayeractions/models/index.json`：

```json
{
  "version": 1,
  "models": [
    {
      "modelId": "ysm_01_jk",
      "hashSha256": "输入原始完整bbmodel字节的64位小写SHA-256",
      "modelResource": "meplayeractions:models/ysm_01_jk.bbmodel"
    }
  ]
}
```

模型 ID 与当前客户端及服务端协议一致，为 `[a-z0-9_-]{1,64}`，允许下划线或短横线开头，不允许点号。模型 ID 必须唯一；每包包含 1 至 128 个客户端模型。`modelResource` 是完整资源 ID，解析方式为 `namespace:path` → `assets/namespace/path`，不自动插入 `textures/`、`models/` 或文件后缀。

`hashSha256` 识别输入原始完整 `.bbmodel` 文件，而不是去掉贴图后的元数据 JSON。服务端和客户端使用这一哈希对齐资产。构建时只替换原始 JSON 文本中 `textures[i].source` 的字符串 token，保留其他字符、UTF-8 中文、空白、行尾及字段顺序，因此可以精确恢复原始资产。

生成的模型仍是完整 BBModel 结构，只把贴图字段改为资源 ID：

```json
{"textures":[{"source":"modelengine:textures/entity/models/skin.png"}]}
```

如果输入引擎包中存在字节完全相同的根目录 PNG，优先复用该资源 ID；多个候选按 ZIP 路径排序选择。若仅 PNG 编码不同，则验证尺寸和完整 RGBA 像素一致，并在最终副本中将该引擎 PNG 换为源模型 PNG 字节，客户端仍引用同一个引擎资源 ID。如果同一路径还有引擎覆盖层，则所有覆盖层 PNG 也必须是相同尺寸及 RGBA 像素；通过后一起统一编码，任何像素差异都禁止该候选复用。

没有安全候选时，PNG 存为 `assets/meplayeractions/textures/sha256/<PNG字节SHA-256>.png`，不同模型的相同 PNG 只存一份。已经被某个客户端模型引用的资源不能再次改成另一种 PNG 编码，避免破坏该模型原始资产哈希。因此，两个原始模型若使用像素相同但编码不同的源 PNG，工具优先保证二者原始哈希可恢复；要达到一份贴图，应先在源资产准备阶段统一 PNG 编码。生成文件与引擎文件、目录或不同内容覆盖层冲突时构建失败。

`assets/meplayeractions/models/normalization.json` 记录 `version:1`、输入引擎 ZIP 的 `enginePackSha256`（独立包为 `null`）及 `pngEncodingRewrites` 数组。每条包含 `assetPath`、`originalPngSha256`、`sourcePngSha256`、`width`、`height`、`rgbaSha256`、`pixelsEqual:true`。清单只记录最终副本的 PNG 编码变更；除此之外，原引擎条目内容不变。模型索引的原始 `hashSha256` 不因 PNG 规范化改变。

## 客户端读取与哈希校验

客户端从当前游戏 ResourceManager 读取索引、模型元数据及其引用 PNG。读取 `textures[i].source`，取出 PNG 原始字节，以标准有填充、不换行的 Base64 编码，再恢复成 `data:image/png;base64,<编码>` 的 JSON 字符串 token。替换只针对 `textures[i].source`，避免修改其他字段中恰好相同的字符串。恢复后的 UTF-8 字节必须与 `hashSha256` 相符，然后交给现有 BBModel 解析器；哈希不同、资源缺失或解析失败时模型不可用。

为确保精确恢复，构建工具要求输入为严格 UTF-8 JSON，并拒绝重复 JSON 键、非有限 JSON 数字、超过 64 层的模型 JSON，以及非标准内嵌 PNG、带额外 JSON 转义的贴图源或非规范 Base64。贴图源键必须普通写为 `"source"`，值必须直接写为 `"data:image/png;base64,..."`，不能写成 `"data:image\/png;base64,..."`。模型中其余合法 JSON 转义不受此限制。

资源包重载后应重新读取当前资源；不能沿用旧包中同一路径的 PNG。客户端可保留已校验资产的哈希身份，但实际绘制应等待新资源完成读取、解析及 GPU 准备。是否显示原生人物、是否接管服务端伪装、仅本人可见的本地选择等运行策略不属于资源包构建工具。

## 构建边界及验证

- 输入 ZIP、输出 ZIP及全部展开条目总量各不超过 256 MiB；单条目不超过 32 MiB；最多 65,536 个条目。
- 单个输入模型不超过 8 MiB；每模型 1 至 16 张 PNG，每张编码前不超过 6 MiB，宽高各不超过 4,096，合计像素不超过 16,777,216。
- PNG 签名、IHDR、块长度、CRC、IDAT 与 IEND 完整性由工具检查，并以 Pillow 完整解码模型 PNG及复用候选。模型几何/表达式边界仍由客户端 BBModel 解析器检查。
- ZIP 拒绝绝对路径、驱动器前缀、反斜线、控制字符、空路径段、`.`、`..`、重复或大小写冲突条目、文件/目录冲突、符号链接、加密条目及非 STORED/DEFLATED 压缩方法。工具只读取条目，不解压到文件系统。
- 完成后重新读取最终 ZIP，核验 CRC、排序条目列表及每个条目字节。输入引擎 ZIP 和原始模型始终只读，不删除历史发布包。

独立行为测试：

```powershell
python -m unittest discover -s tools/tests -v
```

测试覆盖引擎条目保留、PNG 复用与去重、像素等价编码规范化及清单、不同像素保留、覆盖层冲突、原始字节/哈希恢复、确定性输出、独立包、路径逃逸、ZIP 碰撞和大小边界、非规范源拒绝及历史文件保护。
