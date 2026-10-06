# 开发工具

当前版本为服务端与客户端 **0.5.1**。从项目根目录运行工具；构建、定向检查和交付流程见[开发说明](../CONTRIBUTING.md)，运行配置见[文档索引](../docs/README.md)。

| 工具 | 职责 |
| --- | --- |
| [package_current.py](package_current.py) | 当前 0.5.1 两端交付入口。读取已完成的构建和定向检查证据，校验资源、许可、版本与 ZIP 内容，输出工作区 `dist/`。 |
| [package_release.py](package_release.py) | 保留早期 `full`、`interactions` 验收流程；同时提供当前入口使用的资源、许可和 ZIP 校验函数。旧实机门槛不用于 0.5.1。 |
| [prepare_models.py](prepare_models.py) | 根据指定研究源模型生成 `examples/models/` 原模型及 `examples/blueprints/` 的 ME 数值动画版本，并更新清单。会写入示例资源；日常客户端或服务器安装无需运行。 |
| [build_client_resource_pack.py](build_client_resource_pack.py) | 已有的可选离线资源包工具，写入客户端模型索引。默认服务器主动推送路线无需运行；ModelEngine 导入蓝图、CraftEngine 合并与下发的职责见[模型分发](../docs/MODEL_DELIVERY.md)。 |
| [tests/](tests/) | 离线资源包工具的行为测试；不代表 Minecraft 画面或性能验证。 |

## 当前打包入口

```powershell
python -B tools/package_current.py --help
python -B tools/package_current.py
```

该入口固定验证 **0.5.1**，不会替你构建、运行测试、启动 Minecraft 或服务器。它要求两端最新 JAR、`build/validation-0.5.1/` 中保存的当轮定向报告、各阶段 `stage.json` 与最终 `build-commands.json`，以及已固定版本的 OpenYSM 参考源码和 Gradle 依赖缓存。可用 `--reference`、`--gradle-cache` 指定本机位置，`--stage-order` 明确报告顺序。

阶段目录以 `server`、`client` 或 `integration` 开头，例如 `server-sharing`、`client-sharing`、`integration`。前两类目录中的 `TEST-*.xml` 按所属阶段确定侧别；跨两端的 integration 阶段须分别放在 `server/`、`client/` 子目录，不修改原始报告。每阶段保存以下元数据，命令与退出码必须来自实际执行：

```json
{"version":"0.5.1","commands":[{"command":"实际定向检查命令","exit_code":0}]}
```

最终 `build-commands.json` 使用相同结构，至少包含成功的 Maven `package -DskipTests` 与 Gradle `build -x test` 命令。打包入口拒绝旧版本元数据、失败或缺失退出码；测试统计按侧别、测试套件和测试名取阶段顺序中的最后结果，保留早期报告，但所有最终结果必须通过。源码包排除服务端 `private-models/` 和任意 `private-fixture*` 目录；正常源码、内置资源和公开示例不受此排除影响。

已有任一同名交付文件时会拒绝输出，不覆盖发布包。后续发布需明确更新版本合同和当轮验证证据；此脚本不是可直接套用的任意版本发布器。

源码包保留 `tools/` 下的入口与依赖；协议唯一来源是 [docs/CLIENT_PROTOCOL.md](../docs/CLIENT_PROTOCOL.md)。`build/`、`target/`、`client/build/` 是忽略的本机输出，包含日志、验收辅助脚本与临时探针；这些内容不会自动成为正式工具或新一轮验证证据。客户端 `src/test/` 下的实机辅助类同样不是安装步骤，按当轮需要选择，不重复执行已通过的旧场景。
