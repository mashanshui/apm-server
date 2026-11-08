# memory-metrics-server Specification

## Purpose

为 Android 客户端提供统一的 PSS、VSS 与 Java 堆采样上传契约，使平台能够可靠去重并保存进程级内存测量，为授权用户提供统计口径一致、可解释且可验证的内存概览和时间趋势。

## Requirements

### Requirement: 接收运行期内存峰值汇总

系统 MUST 通过 `POST /ingest/v1/batches` 接收 `schemaVersion=2`、`eventType=memory_peak_summary` 和唯一对应的 `memoryPeakSummary` 载荷，沿用公共事件字段、JSON/gzip、应用 Key 和整批包名校验。系统 MUST 停止接收 `memory_sample`，不得将普通样本或 `memorySample` 兼容转换为运行峰值。内存泄漏报告不进入本统计。

载荷 MUST 包含 processName（1..256 字符）、非负 Unix 毫秒 collectionStartedAt/lastSampledAt，满足 collectionStartedAt<=lastSampledAt，信封 occurredAt MUST 等于 lastSampledAt。pss/vss/javaHeap 三项峰值对象 MUST 至少一项非 null；每项提供时 MUST 包含 bytes（0..9007199254740991 的整数）、occurredAt（位于采集闭区间的 Unix 毫秒）、foreground（布尔值），可含 scene（非空时1..128字符的 Activity 名称）。每项可独立缺失，合法零值不得视为缺失，未知字段及不匹配的专属载荷 MUST 拒绝。PSS、虚拟地址空间、totalMemory-freeMemory 的字节口径 MUST 保持，不能接受客户端分位数或跨进程求和。

峰值历史窗口 MUST 独立可配置，默认90天，按 lastSampledAt 判断，恰好90天及更早返回不可重试 `EVENT_TIME_TOO_OLD`；未来容差沿用公共配置，超出返回 `EVENT_TIME_IN_FUTURE`。运行起点或单项峰值早于历史窗口但位于合法采集区间时 MUST 不因此拒绝。其他事件历史窗口 MUST 保持不变。结构/类型错误沿用批次400，合法结构的业务校验错误沿用事件级永久拒绝，不影响同批其他合法事件。

#### Scenario: 部分指标采集成功
- **WHEN** 合法事件只提供 PSS 峰值，其时间和上下文合法
- **THEN** 系统接受原值，其他指标缺失，不补零或借用 PSS 上下文

#### Scenario: 三项峰值来自不同时刻
- **WHEN** 同轮 PSS、VSS、Java 堆分别在不同场景与前后台发生峰值
- **THEN** 系统分别保留三项时间与上下文，不要求它们相等

#### Scenario: 拒绝无效载荷
- **WHEN** 三项均缺失、值负数/浮点/超限、峰值对象残缺、起止倒置、峰值越界、occurredAt不等于lastSampledAt或同时带其他载荷
- **THEN** 系统依照既有结构与业务校验边界永久拒绝，不保存非法峰值

#### Scenario: 拒绝错误应用凭据或包名
- **WHEN** 应用 Key 无效或批内包名不匹配该 Key
- **THEN** 系统沿用既有鉴权和整批包名拒绝语义，不写入任何该批内存事件

#### Scenario: 延迟启动补报与窗口边界
- **WHEN** 截止时间距接收8天的峰值事件补报，另有恰好90天的峰值事件与超过原窗口的普通领域事件
- **THEN** 前者接受，90天峰值返回永久过期，其他事件不会因峰值窗口而被放宽

#### Scenario: 长运行和未来偏差
- **WHEN** 运行起点早于90天而截止在窗口内，或截止超过允许未来容差
- **THEN** 前者按有序采集区间接受，后者返回永久未来时间错误

#### Scenario: 旧样本不能进入峰值链路
- **WHEN** 收到 memory_sample 或以 memorySample 充当峰值载荷
- **THEN** 系统永久拒绝，不混入运行峰值统计

### Requirement: 重试不改变统计数量

系统 MUST 按 appId/eventId 去重；客户端封存、跨启动补传及重试 MUST 使用原 ID、载荷、时间、版本和身份，不得使用上传进程信息改写。相同事件的批内重复、并发、服务重启及跨接收日期重试 MUST 在查询中只贡献一次运行，重复请求不得覆盖已接受的峰值。系统 MUST 在持久化成功后才返回 accepted，重复返回 duplicate，存储暂时失败返回可重试错误，不得返回虚假的 accepted/no_data。系统 MUST 提供持久化与本地内存验证模式，并标明数据来源。持久峰值默认以 lastSampledAt 保留90天；历史普通样本 MUST 不参与新统计，也不得自动推断为运行峰值。

#### Scenario: 并发与跨日重复上传
- **WHEN** 同一封存事件并发上传，或跨服务重启、跨接收日期再次上传
- **THEN** 首次可靠写入接受，重试得到重复确认，概览和趋势只计一次运行

#### Scenario: 响应丢失后重试
- **WHEN** 数据已写入而客户端未收到响应，以同一原事件重试
- **THEN** 系统识别重复且保留原身份/版本/峰值，不新增统计

#### Scenario: 存储不可用
- **WHEN** 峰值事件无法写入持久化存储
- **THEN** 响应按既有存储错误协议指示可重试，不能声称已持久化

#### Scenario: 新旧数据隔离
- **WHEN** 相同查询时间范围同时存在旧普通样本与新峰值记录
- **THEN** 运行峰值概览与趋势只使用去重后的新记录，旧表数据不计入

### Requirement: 概览统计遵循固定样本口径

系统 MUST 保留 `GET /api/v1/apps/{appId}/memory-metrics/summary`，返回 PSS/VSS/Java 堆各自的 runCount、averageBytes、p50Bytes、p90Bytes、p95Bytes、p99Bytes、status，以及查询区间和 dataSource；内存统计 MUST 删除 sampleCount 旧字段且不提供别名。每个统计值 MUST 基于过滤后去重的每轮单项峰值，按运行记录算术平均，分位数按升序值 `h=(n-1)*p` 线性插值。scene/foreground MUST 分别匹配各指标自己的峰值上下文，因此三个runCount可以不同。空指标 MUST 返回 runCount=0、status=no_data、统计null；非空为ok。不得按设备、时长或采样次数加权，不得跨进程求和或由趋势分位数重算总体。

所有持久化过滤、逻辑去重和统计 MUST 在数据库完成，应用不得加载窗口内全部运行记录后聚合。查询 MUST 复用服务端时间、扫描量、内存和响应预算，超时/资源超限/存储失败 MUST 返回明确错误，不能伪装空统计或静默截断。

#### Scenario: 验证分位数和缺失样本
- **WHEN** 四轮PSS峰值为0、100、200、300，第五轮缺少PSS
- **THEN** PSS runCount=4，平均/P50=150、P90=270、P95=285、P99=297，第五轮不参与PSS统计

#### Scenario: 单样本与空指标
- **WHEN** PSS只有一轮峰值100，Java堆没有有效峰值
- **THEN** PSS五项统计均100，Java堆五项均null，各自返回runCount和状态

#### Scenario: 指标分别匹配场景和前后台
- **WHEN** 同轮PSS在前台A、VSS在后台、Java堆在前台B，查询foreground=true且scene=A
- **THEN** 只有PSS贡献一轮，不能让VSS或Java堆因同轮PSS匹配而被计入

#### Scenario: 大量运行与资源拒绝
- **WHEN** 持久库存在远多于返回结果的运行记录，或者查询超过资源预算
- **THEN** 服务只接收数据库统计；预算耗尽明确失败，不全量加载记录或返回部分统计

### Requirement: 趋势与筛选使用同一时间和授权边界

系统 MUST 保留 `GET /api/v1/apps/{appId}/memory-metrics/trend`，metric仅pss/vss/java_heap，interval仅hour/day，默认pss/hour。概览与趋势 MUST 支持from/to、appVersion、osVersion、deviceModel、processName、scene、foreground；文本精确匹配，scene/foreground对应被统计指标峰值当时的上下文，其他维度使用原运行元数据。

时间 MUST 按lastSampledAt而不是上传时间或单项峰值时间进行UTC左闭右开过滤，默认最近24小时、最长31天；一轮只归属其截止时间所在小时/日桶，不按运行跨度复制。趋势 MUST 返回相交桶及runCount、五项统计和状态，空桶runCount=0、统计null；持久化分桶与聚合在数据库完成，31天非整点小时窗最多745桶，并复用查询预算。未知参数与非法值返回400；Session/目标应用成员资格以及只读Agent既有授权 MUST 保持，App Key不得绕过查询授权，不新增内存多维下钻。

#### Scenario: 时间边界和空桶
- **WHEN** 运行截止恰好在from/to，且某小时无截止记录
- **THEN** 计入from、排除to，空小时runCount=0且统计null

#### Scenario: 跨日运行延迟上传
- **WHEN** 运行第一天开始、第二天最后采样、第三天补报
- **THEN** 该记录仅归属第二天截止时间的桶，不落入第一天、上传日或各峰值时间桶

#### Scenario: 筛选进程和前后台
- **WHEN** 查询指定processName和foreground=false
- **THEN** 只统计该原进程且各自峰值发生于后台的指标，未指定条件不限制

#### Scenario: 非法查询和越权
- **WHEN** 请求32/64位参数、非法metric、超过31天或调用者无目标应用权限
- **THEN** 参数错误400，未认证/越权沿用既有授权错误，不泄露其他应用数据
