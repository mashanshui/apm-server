# 项目文档导航

本页只维护文档分类与阅读入口。平台能力、实现状态和环境最近核验统一见[当前实现与验证边界](knowledge-base/00-当前实现与验证边界.md)，不在各目录重复记录。

## 按任务查找

| 需要了解什么 | 首选入口 |
|---|---|
| 平台目标、架构、跨端约定与路线图 | [平台知识库](knowledge-base/README.md) |
| Java 内部实现、数据库、构建和测试 | [后端知识库](../backend/docs/knowledge-base/README.md) |
| 页面、路由、状态管理、联调和测试 | [前端知识库](../frontend/docs/knowledge-base/README.md) |
| HTTP 请求响应、错误语义和统计口径 | [服务端 API](api/README.md) |
| Android 事件构造、上传、持久队列和重试 | [客户端接入](client-integration/README.md) |
| 启停、部署、SSH、ClickHouse 和 Grafana | [部署与运维资料](operations/README.md) |
| 固定样本及人工计算的统计期望 | [固定数据集](datasets/README.md) |
| 带日期、环境与限制的跨端验收证据 | [验收资料](validation/README.md) |
| Android Crash Handler 参考代码 | [Kotlin 联调样例](examples/AndroidCrashHandler.kt) |
| 内存报告 multipart 的最小文件 | [metadata 示例](fixtures/memory-leak-metadata.example.json)、[report 示例](fixtures/memory-leak-report.example.json) |

## 文档维护

- 新文档加入所属分类入口；移动时同步正文、图片、锚点及其他工程中的引用。
- 当前操作方法原位维护；验收资料保留原执行日期、样本和限制，历史记录不代表当前环境仍可用。
- API 维护正式契约，客户端文档维护接入步骤，知识库维护实现与长期约束；详细规则见[仓库协作规则](../AGENTS.md#文档归属与同步)。
