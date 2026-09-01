## Context

见 `proposal.md`。当前接收链路由单一 `CrashIngestionService`、`CrashEventValidator` 和 `CrashEventProcessor` 驱动，公共事件信封只包含 Crash 专用载荷；`EventRepository` 只暴露全量查询和按事件查询，Crash 统计在 JVM 内对原始事件计算。该结构适合当前小规模 Crash 开发闭环，但不适合高频帧指标、设备日二阶段聚合和大型采样堆栈详情。

现有批量接口已经具备项目 Key、JSON/JSON+gzip、压缩前后大小限制、部分接受、`projectId + eventId` 去重和可重试 503 语义，应保持兼容。网页查询已经使用 Session、CSRF 和项目成员授权；本变更只提供服务端接口，不新增前端消费者。

## Goals / Non-Goals

**Goals:**

- 以稳定、版本化、可由固定数据集验证的契约承接未来 Android 卡顿数据。
- 让卡顿个例、FPS 和挂起率保持各自的数据粒度、排序方向和分母语义。
- 让高频统计在 ClickHouse 完成，同时保留内存实现用于快速契约测试。
- 明确精确消息耗时、估算堆栈耗时、采集质量和算法版本的证据边界。
- 复用现有认证、错误响应和批次可靠性语义，不破坏 JVM Crash 能力。

**Non-Goals:**

- 不实现 Android 采集、btrace 集成、客户端队列或上传调度。
- 不实现 Vue、Grafana、告警、Issue 状态/负责人、数据导出或版本差异检测页面。
- 不实现 R8 mapping、符号化、Protobuf、Kafka、对象存储或生产网关。
- 不以本变更中的结构测试替代真实 ClickHouse 容量、故障恢复和生产权限验收。

## Decisions

### 1. 在公共信封中增加三个互斥的版本化专用载荷

保留 `/ingest/v1/batches` 和公共身份、时间、版本、环境字段，新增：

- `eventType=jank` + `jank`：单个主线程卡顿个例及采样证据。
- `eventType=frame_scene_summary` + `frameSceneSummary`：一次场景活跃渲染区间的汇总记录。
- `eventType=foreground_suspension_summary` + `foregroundSuspensionSummary`：一段前台区间的挂起汇总。

服务端按 `eventType` 分派到独立 validator、sanitizer 和 processor；公共校验只执行所有事件共享的约束。三个载荷互斥，未知字段继续失败，以便尽早发现移动端与服务端契约漂移。

替代方案是把所有值放入 `measurements` 和 `attributes`。该方案无法可靠约束堆栈引用、单位、算法版本和统计分母，因此拒绝。

### 2. 卡顿详情使用相对时间和堆栈字典

`occurredAt` 继续表示可查询的 UTC 墙上时间；主线程消息和采样时间使用纳秒单位的相对偏移。服务端只校验 `0 <= sampleOffsetNs <= messageDurationNs`，不比较不同设备的单调时钟绝对值。

事件内以完整、可独立解码的 `stackId -> frames` 字典保存堆栈，每个样本只引用 `stackId`。不采用依赖前一条样本的增量栈编码，避免丢失或乱序后导致后续样本不可解码。载荷同时保存期望/尝试/成功/丢弃计数、采样间隔和估算算法版本。

### 3. 精确区间与采样估算使用独立字段

主线程消息的开始/结束由客户端检测，服务端把 `messageDurationNs` 作为精确个例耗时，但仍验证其范围和算法版本。调用树估算由服务端根据样本偏移重建：只在同一线程、同一消息、相邻合法样本之间建立有限覆盖，遇到大间隔、乱序、丢样或事件边界即断开。

输出使用 `exactMessageDurationNs`、`estimatedDurationNs`、`estimatedUnattributedDurationNs`、`coveredDurationNs` 和 `uncoveredDurationNs` 等不同字段。未归属估算时间只表示没有归属给直接子节点的估算覆盖，不能命名为 CPU self time。

替代方案是用 `sampleCount × sampleInterval` 直接计算每个方法耗时或把消息总时长分摊给根节点。该方案会把空洞和未采样区间伪装为连续执行，因此拒绝。

### 4. 服务端生成确定性的版本化 Issue 指纹

首期指纹版本为 `jank-v1`。服务端从估算覆盖最大的主线程应用调用路径中选择关键路径，对类名和方法名执行白名单、去行号、去地址和动态值规范化，再将项目、应用及规范化路径输入 SHA-256。覆盖相同时使用最早出现顺序和规范化文本排序作为确定性决胜规则。

客户端指纹只可作为诊断属性，不能作为 Issue 主键。事件保存 `fingerprintVersion`；未来 mapping 或算法升级生成新版本时，不覆盖原始堆栈、事件 ID 或旧指纹。

### 5. 卡顿事实、详情和指标使用独立 ClickHouse 对象

新增单独版本的 ClickHouse 脚本，建议对象为：

- `apm_jank_event`：事件、场景、精确耗时、指纹、算法版本和采集质量强类型列。
- `apm_jank_detail`：压缩的堆栈字典、样本、调用树和证据摘要，仅供详情下钻。
- `apm_jank_issue_hourly`：Issue、版本、场景和小时粒度的计数、去重设备及分位数状态。
- `apm_frame_scene_summary`：客户端场景汇总记录和帧分布。
- `apm_device_suspension_segment`：前台时长、完整累计挂起时长、次数、阈值和算法版本。
- `apm_device_suspension_daily`：按匿名设备、UTC 日期和稳定维度合并后的设备日状态。

事实与详情使用 `ReplacingMergeTree(received_time)`，查询在需要逻辑去重时使用 `FINAL` 或等价的确定性最新版本子查询。所有对象按月分区，排序键以 `project_id`、事件/日期、常用维度开头。TTL 配置沿用“原始短、聚合长”的原则，生产值留待容量数据确认。

不把高频帧数据全部塞入 `apm_event_raw`，避免继续扩宽 Crash 原始表和让查询扫描不相关列。公共接收结果仍保持统一，但持久化按事件类型路由到专用仓库。

### 6. 写入与查询分离，ClickHouse 负责生产聚合

将当前 Crash 命名的接收编排重构为通用批次接收服务和按类型处理器，但保持外部响应不变。卡顿领域建立独立写入仓库与聚合查询仓库：内存实现用于固定数据集、错误和权限测试；ClickHouse 实现执行受白名单约束的参数化查询。

生产查询不调用 `findAll()` 后在 JVM 内扫描。Issue、分位数、去重设备和设备日二阶段聚合均由 ClickHouse 查询或物化状态完成。数据库查询设置项目条件、最大范围、行数和超时；维度字段只能从枚举映射，不能拼接任意请求字符串。

### 7. FPS 由客户端提供版本化汇总，服务端只合并兼容记录

Android 更接近帧时钟、动态刷新率和“真实 UI 刷新”判定，因此场景记录携带 `normalizedFps60`、原始刷新率/活跃时长/帧数、帧耗时分布和 `fpsAlgorithmVersion`。服务端校验数值范围和内部基本一致性，但不在缺少逐帧数据时伪造另一个归一化算法。

查询只合并同一兼容算法版本。FPS 分位数按值从高到低定义，因此 `P50 >= P90 >= P99`；响应必须返回算法版本、总记录数和有效记录数。静止期间没有真实 UI 刷新的记录不进入统计。

替代方案是在服务端仅用平均刷新率重算归一化 FPS。动态刷新率和客户端实际帧判定无法从汇总数据还原，该结果可能错误，因此拒绝。

### 8. 挂起率采用设备日二阶段聚合

客户端上报前台区间及所有超过阈值帧间隔的完整累计时长；首期基准阈值为 200ms，具体算法由 `suspensionAlgorithmVersion` 标识。服务端按 `project + anonymousDeviceId + UTC date + 稳定查询维度` 合并去重区间，计算：

`suspensionSecondsPerHour = suspensionDurationMs / 1000 / (foregroundDurationMs / 3600000)`。

平台分位数在设备日结果上按值从低到高计算。前台时长为零的记录拒绝；查询没有有效设备日分母时返回 `denominator_insufficient`。UTC 日期作为首期稳定口径，避免依赖尚未建模的项目或设备时区。

### 9. 查询接口沿用项目授权并提供稳定状态语义

新增：

- `/api/v1/projects/{projectId}/janks/overview`
- `/api/v1/projects/{projectId}/janks/trend`
- `/api/v1/projects/{projectId}/janks/issues`
- `/api/v1/projects/{projectId}/janks/issues/{fingerprint}/events`
- `/api/v1/projects/{projectId}/janks/events/{eventId}`
- `/api/v1/projects/{projectId}/jank-metrics/fps`
- `/api/v1/projects/{projectId}/jank-metrics/suspension-rate`
- `/api/v1/projects/{projectId}/jank-metrics/dimensions`

查询复用现有 Session 和项目成员授权、ISO-8601 时间、最大 31 天范围、limit/cursor/timeout 约束。响应显式区分 `ok`、`no_data` 和 `denominator_insufficient`；分位数字段无有效样本时为 `null`，不能用零代替。

### 10. 协议资产与固定数据集作为移动端对接契约

仓库新增三个 JSON Schema、中文上报/查询文档、错误码、完整 gzip/identity 示例和固定数据集。固定数据集覆盖同 Issue 不同行号、不同 Issue、重复事件、跨设备、跨 UTC 日、多个场景、FPS 降序分位数、挂起率二阶段聚合、无数据和无分母。

自动化测试以同一数据集验证内存实现、HTTP 响应和统计公式；真实 ClickHouse 冒烟使用相同事件并记录环境与结果。文档只将自动化覆盖、外部冒烟和目标能力分别表述。

## Risks / Trade-offs

- [客户端汇总算法有缺陷会污染平台指标] → 要求原始计数、算法版本和范围校验；不同版本禁止静默合并，并在固定设备数据上联调。
- [堆栈与样本导致事件过大] → 使用字典、gzip、配置化数量/深度/体积限制、详情独立 TTL，并返回不可重试大小错误。
- [事实表与详情表没有跨表事务] → 先写可查询事实与详情的同一批次操作，记录一致性指标，使用相同事件 ID 幂等补偿，并安排真实故障演练。
- [ReplacingMergeTree 合并前出现重复] → 聚合查询使用 `FINAL` 或确定性去重子查询，并用重复固定数据验证所有统计。
- [高基数场景或设备维度拖慢查询] → 场景长度与字符集限制、白名单维度、时间/行数/超时限制和聚合状态表；以容量测试决定进一步预聚合。
- [历史 Crash 行为被通用接收重构破坏] → 保留既有响应契约和全部 Crash 测试，分类型逐步接入，禁止在同一任务中顺带改变 Crash 统计口径。
- [UTC 设备日与用户本地日不同] → 响应和文档明确首期 UTC 口径；未来新增时区口径必须使用新算法版本或查询参数。

## Migration Plan

1. 先合入协议资产、领域模型和固定数据集，默认不接收新事件类型。
2. 部署新增 ClickHouse 对象并使用幂等初始化验证 schema；旧 Crash 表和 API 不变。
3. 启用卡顿个例接收，在内存与真实 ClickHouse 中验证重复、部分接受、事实/详情一致性和查询结果。
4. 开放卡顿 Issue 查询 API，完成固定数据集与项目隔离验收。
5. 再启用场景帧指标和前台挂起汇总，验证算法版本隔离及设备日聚合。
6. 移动端接入前发布最终 Schema、示例、错误码和服务端支持版本；生产采样率与流量由客户端后续控制。

回滚时先关闭新事件类型接收和卡顿查询路由，保留新增 ClickHouse 表以避免丢失已接收数据；旧 `app_start`、Crash 上报和查询继续工作。确认无回放或审计需求后，再通过独立运维变更处理新表，应用回滚不删除数据。

## Open Questions

- 原始卡顿详情和聚合数据的生产 TTL、预计事件大小及峰值写入量需要在移动端方案确定后压测确认。
- 真实 ClickHouse 环境采用物化视图还是查询时聚合，由固定数据规模与性能测试结果决定，但不改变对外统计语义。
- 首批允许的 FPS 与挂起率算法版本集合由移动端联调前配置确认，服务端必须保持显式版本白名单。
