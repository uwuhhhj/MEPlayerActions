# 文档索引

当前服务端与客户端：**0.4.9**。安装和基本使用从 [项目 README](../README.md) 开始；每项配置与行为集中在下列对应文档。

## 玩家与服务器管理员

| 内容 | 文档 |
| --- | --- |
| 安装与基本使用 | [项目 README](../README.md) |
| 客户端默认配置、模型导入、图库、轮盘、显隐与排查 | [客户端配置](CLIENT_CONFIG.md) |
| 服务器配置、原模型来源、主动推送与缓存 | [模型同步与部署](MODEL_DELIVERY.md) |
| 后台发现频率、增量同步与性能边界 | [性能配置](PERFORMANCE.md) |
| YSM 格式、动画、查询、作者配置和组件支持 | [YSM 兼容说明](YSM_COMPATIBILITY.md) |
| 内置模型、移植源码与解码库的来源和许可 | [第三方说明](../THIRD_PARTY_NOTICES.md) |
| 版本变更与验证范围 | [版本历史](history/README.md) |

## 开发与适配

| 内容 | 文档 |
| --- | --- |
| 模块职责与渲染接管生命周期 | [架构](../ARCHITECTURE.md) |
| 源码索引、构建、检查与交付 | [开发指南](../CONTRIBUTING.md) |
| 服务器伪装 v3、私人分享 v1、权限与限流 | [客户端协议](CLIENT_PROTOCOL.md) |
| 旧兼容模式的资源包格式与离线工具 | [旧客户端资源包格式](CLIENT_RESOURCE_PACK.md) |

## 许可原文

- [酒狐资产 CC BY-NC-SA 4.0](licenses/CC-BY-NC-SA-4.0.txt)
- [OpenYSM 软件 MIT](../client/src/main/resources/assets/meplayeractions/builtin/openysm_default/LICENSE.OpenYSM.txt)

历史补丁和验证数字只在 [版本历史](history/README.md) 保留；对应 tests ZIP／build JSON 是各次交付的检查证据。
