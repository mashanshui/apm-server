## Why

现有方案已经具备统一事件信封、批量上报、ClickHouse 分析存储和 Grafana 展示的基础，但 Crash 仍未纳入第一条数据闭环，无法回答版本崩溃率、受影响设备和具体堆栈等排障问题。现在补充 Java/Kotlin 崩溃能力，可以复用既有上报与分析边界，先以较小范围完成从采集到统计下钻的闭环。

## What Changes

- 新增 `eventType=crash` 的 Java/Kotlin JVM 致命崩溃事件协议，包含异常链、结构化堆栈、会话、版本和构建信息。
- 支持服务端对 Crash 事件进行校验、脱敏、规范化、服务端指纹计算和有限重复处理。
- 在 ClickHouse 保存 Crash 原始字段、堆栈明细和按小时聚合数据。
- 以 `app_start` 会话数作为分母，提供崩溃事件数、崩溃会话数、受影响设备数、每千会话崩溃率和无崩溃会话率。
- 新增 Crash 趋势、问题排行、版本对比和堆栈下钻所需的查询能力。
- 使用 Grafana 提供首期统计和堆栈下钻，并复用项目、版本、渠道、环境、系统和设备筛选变量。
- 保留 `buildId`、原始堆栈和符号化状态，为后续 R8 mapping 反混淆和 NDK 能力预留扩展点。
- 首期不支持 NDK/native Crash、非致命异常、Crash 问题生命周期管理、Vue 产品页面和 Kafka。

## Capabilities

### New Capabilities

- `jvm-crash-monitoring`: Java/Kotlin 致命崩溃的采集、校验、存储、统计、查询和 Grafana 堆栈下钻。

### Modified Capabilities

无。当前 `openspec/specs/` 中没有已发布的能力规格。

## Impact

- Android SDK：需要在崩溃处理器中生成事件、写入本地持久队列，并在后续批量上报时发送。
- Spring Boot：影响 `ingest`、`storage` 和 `query` 模块，新增 Crash 事件转换、校验、聚合查询和详情查询。
- ClickHouse：新增 Crash 类型字段、Crash 明细存储和小时/问题聚合视图或表。
- Grafana：新增 Crash Dashboard、变量筛选、问题排行和事件详情下钻链接。
- API：扩展批量上报协议，并新增 Crash 总览、趋势、问题和事件详情查询接口。
- 安全与隐私：异常消息和堆栈需要脱敏、大小限制、项目隔离，日志中不得输出完整 Crash 事件。
- 测试：需要覆盖重复上报、非法事件、指纹归组、`app_start` 分母、项目隔离和固定数据集统计结果。
