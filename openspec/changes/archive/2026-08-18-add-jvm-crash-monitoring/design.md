## Context

本变更基于知识库中的模块化单体、公共事件信封、批量上报、ClickHouse 原始事件与聚合数据、Grafana 优先的展示边界。当前工程仍处于 Spring Boot 基础骨架阶段，尚无已发布的上报、查询或 ClickHouse 实现，因此设计需要明确未来模块边界，但不依赖已有业务代码结构。

首期只处理 Java/Kotlin JVM 致命 Crash。Android 端负责在进程终止前持久化事件，服务端负责校验、脱敏、指纹和存储；Crash 统计使用 `app_start` 的去重会话作为分母，趋势和详情使用事件发生时间而不是接收时间。

## Goals / Non-Goals

**Goals:**

- 复用 `/ingest/v1/batches` 完成 Crash 批量上报，不建立第二套接入协议。
- 保留结构化异常链和原始堆栈，支持按问题和事件下钻。
- 提供可复现的 Crash 事件数、崩溃会话数、受影响设备数和会话崩溃率。
- 使用服务端生成的稳定指纹归组，并避免重复重试放大统计结果。
- 通过 Grafana 完成首期趋势、问题排行、版本对比和详情入口。
- 为后续 R8 mapping 符号化保留 `buildId`、原始堆栈和状态字段。

**Non-Goals:**

- NDK/native Crash、非致命异常和 ANR 事件的处理。
- Crash 问题的负责人、评论、状态、忽略和解决版本管理。
- Vue 产品页面、对外多租户 Grafana 和完整的 mapping 上传/符号化流水线。
- Kafka、跨系统绝对事务和全量事件回放。

## Decisions

### 1. 沿用公共批量上报协议

Crash 使用 `eventType=crash`，在公共事件信封中加入 `crash` 载荷。`crash.kind` 固定为 `jvm`，`crash.fatal` 固定为 `true`。载荷使用 `throwableChain` 保存异常类型、脱敏消息和结构化堆栈帧，避免把堆栈全部塞入通用属性 Map。

选择复用批量接口而不是新增 Crash 专用接口，是因为 Android SDK 已经需要处理批量、gzip、重试、大小限制和 Schema 版本；新增接口会产生重复的鉴权、错误和可靠性语义。

客户端必须先写本地持久队列，再尝试发送。服务端返回永久错误时丢弃该事件并记录拒绝计数；返回限流、ClickHouse 暂时不可用等临时错误时保留事件并允许使用原始 `eventId` 重试。

### 2. 使用统一原始表加 Crash 明细表

`apm_event_raw` 保留公共字段，并增加服务端抽取的 Crash 查询列：

```text
crash_kind
crash_fatal
crash_exception_type
crash_fingerprint
fingerprint_version
symbolication_status
```

较大的异常链和堆栈写入 `apm_crash_detail`，以 `project_id + event_id` 定位。这样高频趋势查询只扫描轻量字段，堆栈下钻才读取明细数据。

统计层沿用按小时聚合的思路：

- 通用事件聚合保存 `event_type=app_start` 和 `event_type=crash` 的会话、设备和事件统计。
- Crash 问题聚合额外按 `crash_fingerprint` 保存问题排行所需的数据。
- 原始表和明细表按事件月分区；查询排序优先覆盖项目、事件类型、事件时间，详情查询覆盖项目和事件 ID。
- `occurredAt` 用于趋势和统计，`receivedAt` 用于上报延迟和数据质量分析。

ClickHouse 聚合可以使用可合并的去重状态以支持任意时间范围查询。固定验收数据集必须验证逻辑上的 `eventId`、`sessionId` 和设备 ID 去重结果。

### 3. 服务端生成指纹，保留原始和符号化轨迹

首期指纹输入为项目、App、JVM 类型、异常类型、规范化后的前若干个应用堆栈帧和脱敏消息摘要。规范化时移除行号、内存地址、UUID、数字参数和其他动态值。指纹由服务端生成，客户端不能直接指定。

保存 `fingerprint_version`，避免指纹算法升级后无法解释历史数据。首期只需要原始指纹；未来 mapping 可产生符号化指纹，但不得覆盖原始堆栈或改变历史事件 ID。

重复上报仍遵循整体“至少一次”传递语义，但 Crash 统计必须按 `eventId` 只计一次。问题排行再按去重后的事件、会话和设备统计。

### 4. 以 app_start 建立会话分母

查询服务在相同项目、时间范围和筛选条件下分别聚合：

```text
startedSessions = distinct(sessionId where eventType = app_start)
crashedSessions = distinct(sessionId where eventType = crash)
crashEvents = distinct(eventId where eventType = crash)
affectedDevices = distinct(anonymousDeviceId where eventType = crash)
```

计算：

```text
crashRatePer1000Sessions = crashedSessions / startedSessions * 1000
crashFreeSessionRate = 1 - crashedSessions / startedSessions
```

当分母为空时返回“分母不足”状态和空的比率字段，不返回 0。版本对比必须使用相同的筛选条件和相同的时间口径。

### 5. API 与 Grafana 分工

查询模块提供以下语义化接口：

```text
GET /api/v1/projects/{id}/crashes/overview
GET /api/v1/projects/{id}/crashes/trend
GET /api/v1/projects/{id}/crashes/issues
GET /api/v1/projects/{id}/crashes/issues/{fingerprint}/events
GET /api/v1/projects/{id}/crashes/events/{eventId}
```

接口必须执行项目权限校验、时间范围和返回行数限制、分页或游标、查询超时和事件详情数量限制。

Grafana 首期使用只读 ClickHouse 数据源展示聚合趋势和问题排行，问题行通过数据链接进入受权限保护的事件详情接口。Grafana 仅面向内网或受控用户使用；未来对外多租户时改为数据源隔离、行级策略或后端查询代理，不能只依赖隐藏项目变量。

### 6. 符号化采用可延后的状态模型

首期不阻塞原始 Crash 入库，也不要求 mapping 已经存在。事件详情至少展示原始堆栈和以下状态之一：

```text
raw_only
waiting_artifact
symbolicated
failed
```

`buildId` 作为未来匹配 R8 mapping 的唯一关联依据之一。后续符号化任务只新增或更新派生字段，不修改原始 Crash 事件。

### 7. 关键替代方案

- **独立 Crash 上报接口**：拒绝。会重复项目 Key、Schema、限流、错误和重试逻辑。
- **所有堆栈直接放入统一事件 JSON**：拒绝。会增加常规趋势查询的扫描成本，并降低字段治理能力。
- **使用 PostgreSQL 保存 Crash 原始事件**：拒绝。与知识库中 ClickHouse 面向高体量追加写和多维聚合的职责冲突。
- **首期引入 Kafka**：拒绝。当前只需要在 ClickHouse 写入成功后确认，尚未出现削峰、回放或多消费者条件。
- **首期实现完整 Crash Issue 管理**：拒绝。Grafana 先验证统计价值，状态、评论和负责人属于后续 Vue 产品能力。

## Risks / Trade-offs

- [Crash 发生后进程可能在本地队列完全落盘前被终止] → Crash Handler 采用最小同步持久化路径，限制事件大小，并通过固定测试验证异常场景；不能保证设备突然断电时的绝对零丢失。
- [异常消息和堆栈可能包含敏感信息] → SDK 与服务端双重脱敏、白名单字段、长度限制，日志只记录事件 ID 和错误类别。
- [任意时间范围的 distinct 查询可能消耗较高] → 默认查询小时聚合，使用可合并去重状态；原始事件仅用于详情和短时间下钻。
- [客户端时钟错误会影响趋势分桶] → 同时保存事件时间和接收时间，限制允许的事件时间窗口，并在数据质量 Dashboard 展示异常时间和延迟。
- [后续 mapping 可能改变问题归组] → 保存原始指纹和指纹版本，符号化指纹作为派生结果，不回写原始事件。
- [Grafana 直接连接 ClickHouse 存在项目隔离风险] → 使用专用只读账号和内网访问；多租户或对外使用前强制切换到真实数据隔离方案。

## Migration Plan

1. 创建 ClickHouse Crash 原始字段、明细表和聚合视图，先用固定数据集验证统计口径。
2. 部署服务端 Crash Schema 校验、脱敏、指纹和写入能力，并保持现有非 Crash 事件行为不变。
3. 部署 Crash 查询接口，验证项目权限、时间范围、分页、重复事件和分母不足响应。
4. 导入 Grafana Crash Dashboard，验证趋势、问题排行、版本对比、无数据和堆栈下钻。
5. Android SDK 灰度发送 JVM 致命 Crash，观察接受率、拒绝率、重复率、上报延迟和 Dashboard 可见延迟。
6. 若需要回滚，停止接收或展示 Crash 能力，保留已写入的 Crash 表和原始数据；不回滚或改写既有事件协议和其他事件数据。
