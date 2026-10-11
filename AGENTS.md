# 项目协作要求

修改前阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，按修改范围完成构建、验证与交付；保留现有工作区修改、历史发布文件以及第三方版权和来源说明。

## Code Style

- Declare types explicitly; do not use `var`. Minimize `Optional` usage.
- Always use braces for control structures. Keep braces and logic on separate lines, and leave at least one blank line between methods.
- Qualify instance fields with `this.`; use `super.` when explicitly accessing superclass fields.
- Do not add comments unless requested, except for core logic in complex methods, which should be explained with `//` comments.
- Use parentheses around nested ternary expressions in complex ternary operations to improve readability.
- When dependencies can be introduced directly, try as much as possible to avoid using reflection for integration. Even if reflection is used, it should be optimized by caching certain reflection objects in advance.
- Minimize splitting it into a large number of small methods in order to improve readability.

这些规范适用于服务端、客户端和测试 Java 代码。大括号独占一行；方法之间至少保留一个空行。已有版权和来源注释须保留。可选插件的接入须继续支持依赖缺失时启动，保留确有必要的公开 API 契约；内部实现避免不必要的 `Optional` 包装和重复反射查找。
