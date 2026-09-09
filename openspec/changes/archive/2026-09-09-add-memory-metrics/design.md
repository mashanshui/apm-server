## Context

动机与产品范围见 [proposal.md](proposal.md)。现有 `ingest` 编排 JSON/gzip 批次，`identity` 提供应用 Key 和成员授权，Crash/Jank 各有独立领域存储。前端已有浅色 PC 布局、应用切换、查询状态和 ECharts。

Bugly 页面已通过侧边浏览器确认：指标概览为平均值、P50、P90、P95、P99，趋势支持分位数切换。页面未证明其后台采集和分位数算法；本设计独立规定口径，不声称与 Bugly 数值完全一致。

## Goals / Non-Goals

目标：复用既有安全和同步处理链路，以同一统计契约贯通客户端上传、两种存储和网页展示。

非目标：不修改 Android SDK 仓库，不增加队列、缓存或通用指标框架，不提供跨进程内存总量推导。其余排除项见提案。首版采用现有元数据加进程、Activity 名称（`scene`）和前后台筛选；不复制 Bugly 的 SDK 版本、业务自定义下钻、时间类型切换、对比列表和导出。

## Decisions

### 1. 独立领域，复用上传入口

新增 `memory/api` 与 `memory/internal`，由 `ingest` 通过公开 API 调用；禁止依赖 Crash/Jank 内部实现。扩展 `EventEnvelope` 和白名单校验，新增互斥 `memorySample` 载荷，事件类型为 `memory_sample`。复用公共 schemaVersion 和批次大小、gzip、Key、包名与事件级结果规则。

不新增独立上传路由，以便客户端继续使用既有持久化和批次重试机制；不将内存事件塞入 Jank，避免领域语义混杂。

### 2. 每事件表示一个进程的一次采样

公共 `occurredAt` 为采样时刻的 Unix 毫秒。复用 eventId、sessionId、anonymousDeviceId、packageName、appVersion、osVersion、deviceModel 等公共字段。`memorySample`：

| 字段 | 规则 |
|---|---|
| pssBytes | 可选，进程 PSS，非负整数 |
| vssBytes | 可选，进程虚拟地址空间大小，非负整数 |
| javaHeapUsedBytes | 可选，Java 堆已使用字节，非负整数 |
| processName | 必填，非空，最长 256 字符 |
| foreground | 必填布尔值，采样时应用前后台状态 |
| scene | 可选，表示采样时当前应用的 Activity 名称；非空时最长 128 字符，缺失不参与指定 Activity 筛选 |

三个指标至少提供一个；缺失或 null 表示未采到，不作为零参与统计。数值上限为 `9007199254740991`，保证网页 JSON 整数精度；浮点、小于零和超限值拒绝。零为合法测量值。不施加 PSS、VSS、Java 堆的跨字段大小关系校验。

客户端接入文档规定 Java 堆口径为 `Runtime.totalMemory() - Runtime.freeMemory()`；不是已分配总量或最大堆上限。PSS/VSS 采集值须统一转换为字节，接口不接收客户端计算的平均值或分位数。采集频率由 SDK 集成决定并记录为验证边界，不在本仓库实现定时采集。

### 3. 精确且一致的样本统计

所有统计基于筛选后去重的采样事件，各指标分别剔除缺失值。平均值为样本算术平均，不按设备或时长加权。排序为 x[0..n-1]，分位数使用线性插值：h=(n-1)*p，结果为 x[floor(h)] 与 x[ceil(h)] 的线性插值。n=1 返回唯一值；n=0 返回 null。P50/P90/P95/P99 对应 p=0.5/0.9/0.95/0.99。

API 返回字节和每个指标的 sampleCount；前端除以 1048576 并明确标注 MiB，显示两位小数。不从趋势桶平均值或分位数二次计算整体概览。不同进程样本进入同一统计总体时只表示样本分布，不表示应用多进程总内存。

选择原始采样而非客户端汇总，可统一筛选与分位数；选择明确插值算法而非未声明的近似分位数，便于固定数据集验证两种适配器。

### 4. 存储和可靠性

新增领域专属 ClickHouse 采样表，复用平台 HTTP 客户端和项目现有初始化方式，Nullable 数值列存储缺失。按采样时间月份分区，以 appId、eventId 为稳定排序去重键，使用 ReplacingMergeTree 和 FINAL；重试使用相同事件 ID 和载荷，因此跨接收日期仍进入同一采样分区。同 ID 不同载荷不属于合法重试，不承诺覆盖更新。不能仅凭应用层预查承诺并发幂等；必须通过并发重复、重启后重复及跨接收日期重复测试确认统计只计一次。

ClickHouse 在数据库侧完成聚合，采用与上述公式一致的精确插值分位数能力，实施时通过固定数列核对 SQL 函数行为；不全量拉回 JVM。内存适配器使用同一公式，用于测试及本地运行，明确重启丢失。存储错误沿用可重试错误语义，不把失败转成成功或空数据。

### 5. 两个查询端点

`GET /api/v1/apps/{appId}/memory-metrics/summary` 返回三项指标各自的 sampleCount、averageBytes、p50Bytes、p90Bytes、p95Bytes、p99Bytes，以及 status、dataSource、from、to。

`GET /api/v1/apps/{appId}/memory-metrics/trend?metric=pss&interval=hour` 返回桶开始时间、sampleCount 和同样的五项统计。metric 白名单为 pss/vss/java_heap；interval 为 hour/day。趋势一次返回全部分位数，切换分位数不重新上传或重新聚合。

共有筛选：from/to、appVersion、osVersion、deviceModel、processName、scene、foreground，字符串精确匹配；其中 `scene` 按采样时当前应用的 Activity 名称解释。默认最近 24 小时，最大 31 天，左闭右开，使用采样时间。UTC 整点/零点分桶，页面明确标识展示时区；只保留与查询区间相交的桶。无样本桶补 null 和 sampleCount=0，折线断开。未知参数、非法指标、时间和布尔值返回明确 400；沿用现有查询超时上限与安全参数绑定方式。

查询采用 Session 和应用成员授权，非成员与应用不存在保持现有一致语义；App Key 不可用作查询凭据。没有多维下钻接口。

### 6. 页面结构和查询状态

新增 `/apps/:appId/memory-metrics` 和导航“内存 / 指标分析”。复用控制台布局，依次展示三个页签、筛选面板、五张统计卡片、趋势图及 P50/P90/P95/P99 切换。默认 PSS/P50；不展示最大值卡片。

时间范围、筛选、指标和粒度写入 URL，刷新可恢复，非法 URL 显示可纠正的参数提示。应用切换清理旧应用筛选和数据，使用取消或请求序号屏蔽旧响应。概览和趋势分别支持加载、失败重试及无数据；查询失败不展示伪造零值。1280/1440/1920 宽度验证沿用既有 PC 范围。

## Risks / Trade-offs

- 采样频率不同影响样本均值 → 文档明确按样本加权，保留样本数，不宣称设备平均或时长平均。
- 精确分位数增加查询内存 → 限制时间范围、数据库执行时间，验证 ClickHouse 聚合路径；生产容量不以单次冒烟代替。
- 重复写入可能跨日期或并发发生 → 必须在存储与查询测试中证明最终统计幂等，不依赖单机锁。
- 参考页面部分能力不在首版 → 页面和文档明确仅提供上述筛选及概览/趋势，不预埋下钻接口。

## Migration Plan

1. 增量新增 ClickHouse 表和初始化说明，不改写既有已发布迁移；本功能不新增 PostgreSQL 业务表。
2. 部署支持新事件和查询的后端，再部署前端，最后由客户端按接入文档接入。
3. 回退时先停客户端新信号上传或保留待重试队列，再回退前端/后端；保留新增表，不删除数据，明确旧后端不支持新事件。
4. 完成单元、接口、前端构建及固定数据集测试；真实 ClickHouse 冒烟单独记录环境和结果，不能以内存模式代替。Android 真机采集和生产容量仍为外部验收。
