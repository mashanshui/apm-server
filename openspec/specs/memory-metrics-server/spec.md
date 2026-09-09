# memory-metrics-server Specification

## Purpose

为 Android 客户端提供统一的 PSS、VSS 与 Java 堆采样上传契约，使平台能够可靠去重并保存进程级内存测量，为授权用户提供统计口径一致、可解释且可验证的内存概览和时间趋势。

## Requirements

### Requirement: 接收进程级内存采样

系统 MUST 通过 `POST /ingest/v1/batches` 接收 `eventType=memory_sample` 和唯一对应的 `memorySample` 载荷，沿用公共事件字段、JSON/gzip、应用 Key 和整批包名校验。载荷 MUST 包含 processName（1 至 256 字符）、foreground（布尔值），可包含 scene（1 至 128 字符）；scene 表示采样时当前应用的 Activity 名称。pssBytes、vssBytes、javaHeapUsedBytes MUST 至少有一项非 null，已提供值 MUST 为 0 至 9007199254740991 的整数。三个值分别表示进程 PSS、虚拟地址空间、Java 堆已使用量；Java 堆 MUST 按 totalMemory 减 freeMemory 口径构造。occurredAt MUST 表示采样时刻 Unix 毫秒。

#### Scenario: 部分指标采集成功
- **WHEN** 合法事件仅提供 pssBytes，其他指标缺失或 null
- **THEN** 系统接受事件，仅将其计入 PSS 有效样本，其他指标保持缺失

#### Scenario: 拒绝无效载荷
- **WHEN** 三项指标均缺失，或数值负数、浮点、超限，或进程缺失，或同时携带其他专用载荷
- **THEN** 系统返回不可重试事件级校验错误，批内其他合法事件仍按既有部分接受协议处理

#### Scenario: 拒绝错误应用凭据或包名
- **WHEN** 应用 Key 无效或批内包名不匹配该 Key
- **THEN** 系统依照已有鉴权和整批包名拒绝语义处理，内存事件不得写入

### Requirement: 重试不改变统计数量

系统 MUST 按 appId 与 eventId 去重，客户端重试 MUST 使用同一 ID 和载荷；重复采样上传不得增加有效样本数或影响分位数。系统 MUST 支持持久化存储与本地内存模式，并明确数据来源；存储暂时失败 MUST 返回可重试错误，不得返回虚假的 accepted 或 no_data。

#### Scenario: 并发与跨日重复上传
- **WHEN** 同一事件并发上传，或跨服务重启、跨接收日期再次上传到持久化模式
- **THEN** 查询中该事件仅贡献一次样本

#### Scenario: 存储不可用
- **WHEN** 内存事件无法写入持久化存储
- **THEN** 响应按既有存储错误协议指示可重试，不能声称已持久化

### Requirement: 概览统计遵循固定样本口径

系统 MUST 提供 `GET /api/v1/apps/{appId}/memory-metrics/summary`，返回三个指标分别的 sampleCount、averageBytes、p50Bytes、p90Bytes、p95Bytes、p99Bytes、status，以及查询区间和 dataSource。各指标 MUST 基于筛选后去重的非缺失样本，按样本算术平均；分位数 MUST 对升序样本按 h=(n-1)*p 线性插值。空指标 MUST 返回 sampleCount=0、status=no_data、统计值=null；非空为 ok。不得按设备平均、按时长加权、汇总不同进程为应用总内存或用趋势分位数计算整体分位数。

#### Scenario: 验证分位数和缺失样本
- **WHEN** 去重后某指标样本为 0、100、200、300 字节，另有一条缺失该指标的事件
- **THEN** sampleCount 为 4，平均值和 P50 为 150，P90 为 270，P95 为 285，P99 为 297，缺失样本不参与计算

#### Scenario: 单样本与空指标
- **WHEN** PSS 只有一个值 100，Java 堆没有有效值
- **THEN** PSS 五项统计均为 100，Java 堆五项统计均为 null，两个指标分别返回自身样本数和状态

### Requirement: 趋势与筛选使用同一时间和授权边界

系统 MUST 提供 `GET /api/v1/apps/{appId}/memory-metrics/trend`，metric 仅接受 pss/vss/java_heap，interval 仅接受 hour/day，默认 pss/hour。概览和趋势 MUST 支持 from/to、appVersion、osVersion、deviceModel、processName、scene、foreground；文本精确匹配，其中 scene 按采样时当前应用的 Activity 名称筛选。时间范围 MUST 为采样时间的左闭右开区间，默认最近 24 小时，最大 31 天；趋势 MUST 按 UTC 小时或日边界分桶，返回与区间相交的桶及每桶样本数、五项统计和状态。空桶 MUST 补 null 而非零。未知参数与非法值 MUST 返回 400。两个查询 MUST 要求 Session 和目标应用成员资格，禁止用 App Key 绕过查询授权。系统 MUST 不提供内存多维下钻查询。

#### Scenario: 时间边界和空桶
- **WHEN** 事件恰好在 from、to 上，且中间某小时无样本
- **THEN** 计入 from 上事件，排除 to 上事件，空小时返回 sampleCount=0 和 null 统计

#### Scenario: 筛选进程和前后台
- **WHEN** 查询指定 processName 和 foreground=false
- **THEN** 概览与趋势只包含该进程后台采样，未指定的条件不额外限制数据

#### Scenario: 非法查询和越权
- **WHEN** 请求包含 32/64 位参数、非法 metric、超出 31 天范围，或调用者无目标应用权限
- **THEN** 参数错误返回 400；未认证或越权沿用平台既有授权错误，不能泄露其他应用数据
