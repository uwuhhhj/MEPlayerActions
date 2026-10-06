# 开发工具

当前版本为服务端与客户端 **0.6.0**。从项目根目录运行工具；构建、定向检查和交付流程见[开发说明](../CONTRIBUTING.md)，运行配置见[文档索引](../docs/README.md)。

| 工具 | 职责 |
| --- | --- |
| [package_current.py](package_current.py) | 当前 0.6.0 两端交付入口。读取已完成的构建和定向检查证据，校验资源、许可、版本与 ZIP 内容，输出工作区 `dist/`。 |
| [package_release.py](package_release.py) | 资源、许可和 ZIP 校验辅助函数；其 `full`、`interactions` 流程不作为当前交付入口。 |
| [prepare_models.py](prepare_models.py) | 根据指定研究源模型生成 `examples/models/` 原模型及 `examples/blueprints/` 的 ME 数值动画版本，并更新清单。会写入示例资源；日常客户端或服务器安装无需运行。 |
| [build_client_resource_pack.py](build_client_resource_pack.py) | 已有的可选离线资源包工具，写入客户端模型索引。默认服务器主动推送路线无需运行；ModelEngine 导入蓝图、CraftEngine 合并与下发的职责见[模型分发](../docs/MODEL_DELIVERY.md)。 |
| [tests/](tests/) | 离线资源包与打包证据门槛的行为测试；合成报告只验证工具规则，不代表 Minecraft 画面、实际构建或性能验证。 |

## 当前打包入口

```powershell
python -B tools/package_current.py --help
python -B tools/package_current.py
```

该入口固定验证 **0.6.0**，不会替你构建、运行测试、启动 Minecraft 或服务器。它要求两端最新 JAR、`build/validation-0.6.0/` 中保存的当轮定向报告、各阶段 `stage.json` 与最终 `build-commands.json`，以及已固定版本的 OpenYSM 参考源码和 Gradle 依赖缓存。可用 `--reference`、`--gradle-cache` 指定本机位置，`--stage-order` 明确报告顺序。

阶段目录以 `server`、`client` 或 `integration` 开头，例如 `server-sharing`、`client-sharing`、`integration`。前两类目录中的 `TEST-*.xml` 按所属阶段确定侧别；跨两端的 integration 阶段须分别放在 `server/`、`client/` 子目录，不修改原始报告。每阶段保存以下元数据，命令与退出码必须来自实际执行：

```json
{"version":"0.6.0","commands":[{"command":"实际定向检查命令","exit_code":0}]}
```

测试阶段保存真实整数退出码，早期失败不得改写为 0。首次包含多个测试类时，只重跑后来修改或失败的类；其余已通过的用例保留当时的证据。阶段按侧别、测试套件和测试名取最后结果，每个已报告用例最终必须通过，且两端都有当轮报告；XML 的 tests／failures／errors／skipped 数量须与实际 outcome 节点一致。失败的编译尝试可以没有 XML，但不能因此增加通过数；成功测试阶段必须提供报告。

`summary.json` 保留各阶段的原始命令、退出码与报告，并明确记录 `failed_command_attempts`、`earlier_report_failures`、`earlier_report_errors`，不能把最终通过表述为每次尝试都通过。需要纠正的结果放入新阶段，不覆盖原 XML；`--stage-order` 必须恰好列出全部阶段。

最终 `build-commands.json` 使用相同结构，至少包含 Maven `package -DskipTests` 与 Gradle `build -x test`，其中所有构建命令都必须退出 0。打包入口拒绝旧版本元数据、缺失／伪造类型的退出码、最后仍失败或跳过的用例，以及失败的最终构建。源码包排除服务端 `private-models/` 和任意 `private-fixture*` 目录；正常源码、内置资源和公开示例不受此排除影响。

证据门槛的合成回归可单独执行，不运行模型或完整旧测试矩阵：

```powershell
python -B -m unittest discover -s tools/tests -p test_package_current_evidence.py -v
```

已有任一同名交付文件时会拒绝输出，不覆盖发布包。后续发布需明确更新版本合同和当轮验证证据；此脚本不是可直接套用的任意版本发布器。

源码包保留 `tools/` 下的入口与依赖；协议唯一来源是 [docs/CLIENT_PROTOCOL.md](../docs/CLIENT_PROTOCOL.md)。`build/`、`target/`、`client/build/` 是忽略的本机输出，包含日志、验收辅助脚本与临时探针；这些内容不会自动成为正式工具或新一轮验证证据。客户端 `src/test/` 下的实机辅助类同样不是安装步骤，按当轮需要选择，不重复执行已通过的旧场景。
