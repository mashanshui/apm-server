## Why

SDK 已能在内存异常时输出 HPROF 分析 JSON，但平台目前只提供 PSS/VSS/Java 堆采样指标，无法接收分析报告并按引用链定位重复问题。需要打通报告上传、持久化、问题聚合和 Java 内存泄漏页的首版闭环。

## What Changes

- **BREAKING** 将报告上报改为统一的 `multipart/form-data` 文件上传：`metadata` 为统一 JSON 参数，`report` 为必填的 `application/json` 文件 part，原始 HPROF 为可选二进制文件 part；不再支持把 `report` 对象嵌入 `application/json` 请求体。
- 服务端读取并解析 `report` 文件，将受限原文和提取字段保存为报告事实；HPROF 文件仍只保存、不解析，不提供内存详情页或下载入口。
- 在 Memory 模块解析运行信息和 GC 引用链，保留受限报告内容，按应用与事件去重，按应用与引用链 signature 聚合问题。
- 参考已查看的 Bugly Java 内存泄漏页，新增筛选、发生次数/影响设备趋势、问题列表及行内引用链展开。
- 明确报告数、问题发生次数和设备数的不同统计口径；异常报告不计入常规内存采样指标。
- 首版不实现 HPROF 解析、内存详情页、泄漏字节数及其分位数、复现率、用户统计、派单、标签、备注与条件对比。

## Capabilities

### New Capabilities

- `memory-leak-reports-server`: 报告与可选附件接收、校验、持久化、幂等、应用隔离及问题/趋势查询。
- `memory-leak-reports-frontend`: Java 内存泄漏页的筛选、趋势、问题聚合列表及引用链展示。

### Modified Capabilities

无。现有内存指标及其他上报协议保持原有要求，新能力使用独立接口。

## Impact

- 后端：Memory 模块新增端口、应用服务、控制器和存储适配器；复用 Identity 鉴权、平台限制与 ClickHouse 客户端，新增增量 SQL 和附件配置。
- 客户端与 API：上传队列需要分别保留 `metadata`、`report` 文件和可选 HPROF 文件，并按原 `eventId` 重试；纯 JSON 上传路径移除。
- 前端：新增应用内路由、导航、API、类型、查询状态和页面组件，沿用 Session 授权；页面不受上传 part 形态影响。
- 文档：实施时同步根/后端/前端知识库、API 目录、客户端接入目录和固定验收数据集；不把尚未实现的提案写为已发布契约。
- 运维：本地附件目录需要容量、保留期限和清理策略，首版以单服务实例为验证边界，不引入对象存储、消息队列或独立分析服务。
