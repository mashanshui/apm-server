## Why

当前平台已提供 Crash 和卡顿分析，但无法接收和展示客户端内存采样，难以观察版本发布后的内存变化。参考已查看的 Bugly 指标分析页面，建立 PSS、VSS、Java 堆上传、存储和统计展示的最小闭环。

## What Changes

- 扩展现有批量上传入口，接收 `memory_sample`，复用应用 Key、包名校验、事件级结果和幂等语义。
- 新增独立内存领域，支持 ClickHouse 持久化和本地内存适配器，提供概览与趋势查询。
- 新增内存指标页面，仅包含 PSS、VSS、Java 堆三个页签；展示平均值、P50、P90、P95、P99，以及可切换分位数的趋势。
- 提供时间、应用版本、系统版本、机型、进程、Activity 名称（`scene`）、前后台筛选；不提供 32/64 位筛选。
- 不包含 FD 触顶率、多维下钻、堆转储、泄漏分析、客户端 SDK 采集实现。对比列表和导出不纳入首版。
- 同步五类文档及必要的服务端、前端与固定数据集验证。

## Capabilities

### New Capabilities

- `memory-metrics-server`：内存采样上传、校验、幂等存储、授权查询和统计口径。
- `memory-metrics-frontend`：内存指标导航、筛选、概览、趋势及交互状态。

### Modified Capabilities

无。已有鉴权与其他信号契约不变，新增信号行为由上述能力定义。

## Impact

- 后端：`ingest` 公共信封、协议校验和分派；新增 `memory` 领域与数据库初始化资源；装配、异常翻译和架构测试。
- API：扩展 `POST /ingest/v1/batches`；新增 `/api/v1/apps/{appId}/memory-metrics/summary` 与 `/trend`。
- 前端：路由、应用导航、类型、API 客户端、查询状态和 ECharts 页面。
- 文档：根知识库、后端知识库、前端知识库、`docs/api/`、`docs/client-integration/`；规划产物不代表实现已完成。
- 无新增基础设施或外部依赖；保留现有工作区中的其他修改。
