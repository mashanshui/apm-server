# 运行期内存峰值：服务端设计与共同契约

## Context

动机及跨仓库范围见 [proposal.md](proposal.md)。当前 `EventSchemaValidator`、`BatchIngestionService` 和 `MemoryEventValidator` 只识别 `memory_sample`；`005_memory_metrics.sql`、双存储适配器和 `ClickHouseMemoryMetricsQuerySql` 保存并统计单次样本。现有概览/趋势、前端类型及 MCP 仍使用 `sampleCount`，历史接收窗口默认 7 天。本设计与当前实现存在上述有意差异，实施前不将目标写成已实现事实。

配套客户端：[设计](../2026-11-08-report-memory-runtime-peaks-android/design.md)、[任务](../2026-11-08-report-memory-runtime-peaks-android/tasks.md)（原为外部检出，现为仓库内引用）。本文件是本次共同协议、固定样例和联合验收的唯一正文；客户端不维护第二份协议表。未来归档时必须更新两端互链到实际归档路径。

## Goals / Non-Goals

**目标：** 使用现有批量链路形成“旧运行快照 → 新进程补报 → 幂等存储 → 峰值统计 → 页面/Agent”的最小闭环，三项峰值保留各自上下文，查询工作量受时间范围和服务端预算限制。

**不在范围：** 内存泄漏报告/HPROF 处理、全样本轨迹、进程退出时间推断、跨进程内存求和、在线上传当前轮峰值、旧样本迁移或双协议兼容、云端部署。这里只能得到 SDK 有效采集期间的观测最大值，不承诺进程完整生命周期的连续真实峰值。

## Decisions

### 1. 共同事件契约

继续使用 `POST /ingest/v1/batches`、JSON/gzip、`X-App-Key`、`X-Schema-Version: 2` 和事件 `schemaVersion=2`。新增语义明确的 `eventType=memory_peak_summary`，唯一专属载荷为 `memoryPeakSummary`；不复用 `memorySample`，避免旧样本和运行汇总同名异义。旧事件类型永久拒绝，旧载荷字段不纳入新事件白名单。其他领域及其 Schema 不变。

| 字段 | 约定 |
|---|---|
| 公共信封 | 保留现有 packageName、eventId、sessionId、UUID v4 processId、anonymousDeviceId、原 appVersion/versionCode/buildId/environment/channel/osVersion/deviceModel；networkType 可选，若提供则为原快照上下文，不在上传时补写 |
| `eventId` | 首个有效快照创建时生成一次 UUID v4，封存和所有重试保持不变 |
| `occurredAt` | 等于 `memoryPeakSummary.lastSampledAt`，Unix 毫秒；不是封存或上传时间 |
| `processName` | 载荷内必填，1..256 字符，原主进程名称 |
| `collectionStartedAt` | 载荷内必填 Unix 毫秒，SDK 本轮开始采集的时刻；不声称是进程创建时刻 |
| `lastSampledAt` | 载荷内必填 Unix 毫秒，快照中最后一次已持久化的有效采样时刻；不声称是进程退出时刻 |
| `pss` / `vss` / `javaHeap` | 载荷内三项独立峰值对象，可省略或 null，但至少一项有值 |
| 每项 `bytes` | 必填整数，0..9007199254740991；沿用 PSS、VmSize、totalMemory-freeMemory 的原始字节口径 |
| 每项 `occurredAt` | 必填 Unix 毫秒，首次观察到该最大值的时间；必须位于 `[collectionStartedAt,lastSampledAt]` |
| 每项 `foreground` | 必填布尔值，属于该项峰值发生时刻 |
| 每项 `scene` | 可选/null，峰值当时的 Activity 完整类名，非空时最长 128 字符；未知为 null，后台不借用上次 Activity |

`collectionStartedAt <= lastSampledAt`，时间戳非负。数值相等保留首次峰值上下文，单项失败不清空其旧峰值；整轮全部失败不形成上传事件。峰值对象只接受上述字段，不能只传时间/场景而没有 bytes。批次结构/字段类型错误沿用整批 400，合法结构中的范围、时间顺序及必填业务错误沿用事件级永久拒绝；未知 App Key、包名不匹配的原子性边界不变。

### 2. 独立补报窗口与有界保留

这是本提案选择的默认值，不是参考对话中已确定的数值：峰值事件专用历史窗口为 90 天，未来容差沿用现有配置；不扩大 Crash、FPS 等事件的 7 天窗口。接收时要求 `now - 90d < lastSampledAt <= now + futureSkew`，边界及错误使用可注入时钟测试，过期沿用 `EVENT_TIME_TOO_OLD`、未来沿用 `EVENT_TIME_IN_FUTURE`。长运行的起点和某项峰值允许早于窗口，因为窗口只约束该运行记录的观测截止时间；其他时间仍须有序且不晚于截止时间。

客户端默认 TTL 同为 90 天，并以原 `lastSampledAt` 判断，封存/重试不能重新计时。服务端持久记录默认以该时间保留 90 天。客户端磁盘配额维持 20 MiB、最多 10 次尝试，均为有界保留，不能承诺永不丢失；current 属于正在采集的有效轮次时不因 TTL 清理，旧轮封存前先判断过期。自定义客户端 TTL 不得被当成服务端必然支持的窗口，接入文档明确配置关系。

选择 90 天以覆盖超过一周后重启，又复用现有服务端数据保留量级；无限期补报会扩大资源与运维边界，仍保留明确过期行为。

### 3. 新表隔离普通样本，沿用幂等

新增版本化 ClickHouse 脚本创建 `apm_memory_peak_summary`，不改写 `005` 或删除旧数据。保存原公共身份/版本、采集起止和三项 Nullable 峰值及时间、foreground、scene；`event_time=lastSampledAt`，按该时间分月，`ReplacingMergeTree(received_time)` 与 `(app_id,event_id)` 排序，保持按应用和时间过滤。相同原始事件的时间不可变，因此跨接收日期仍落同一事件分区；查询使用逻辑去重视图，不能依赖后台合并已完成。

沿用 `(appId,eventId)`、accepted/duplicate 与同步持久化语义，重复事件不更新已接受峰值或身份。查重只按授权 app 和输入 ID 有界查询，不能被当前查询时间窗或接收日期限制。单批重复、并发相同重试、重启重试、跨接收日重试都不能增加查询统计。不能在插入前标记成功或用空结果掩盖存储失败。内存验证模式提供同一结果语义，但重启丢失数据的边界保持明确；生产容量和多实例原子写入不由本次推断。

新查询仅访问峰值表；旧 `apm_memory_sample` 保留但不参与概览/趋势，不把若干旧样本推断为完整运行。无须队列、缓存、对象存储或新服务。

### 4. 查询按运行观测截止归属，按各峰值上下文过滤

保留 summary/trend 路由、指标 `pss|vss|java_heap`、粒度 `hour|day`、默认最近 24 小时、最长 31 天、UTC `[from,to)` 及既有授权/资源上限。

- 先按 app、`lastSampledAt`、原版本/设备/进程过滤并去重；一次运行只归属一个 UTC 桶，不按运行跨度复制到多个桶，也不按上传时间归属。
- `scene`、`foreground` 分别匹配该指标峰值对象；summary 的 PSS/VSS/Java 堆使用各自符合条件的运行集合，数量可以不同。不能因为 PSS 在前台出现，就把同轮后台 Java 堆峰值计入前台。
- 数量统一改为 `runCount`，删除内存统计结果中的 `sampleCount`；不新增两套别名。每项只取每轮一份 bytes 作算术平均和 `h=(n-1)*p` 线性插值，保留五项统计和 `status`、`dataSource`。不按运行时长/设备/采样次数加权，也不通过趋势桶重算概览。
- 空项/空桶为 `runCount=0`、统计 null；真实零参与统计。SQL 内完成所有过滤、去重、聚合和排序，只回传三个概览统计或最多 745 个相交小时桶（31 天非整点窗口），禁止全量载入 Java。复用扫描、执行时间、内存、响应预算，超时/资源超限/存储失败沿用明确错误。

选择统一的观测截止时间让三项代表同一轮运行集合；按各峰值时间分桶会让同一轮三项落入不同趋势日期，本次不采用。文案明确“按记录的观测截止时间归属”，各峰值时间只用于上下文及后续诊断，不新增详情 API。

### 5. 页面与 Agent 同步

Dashboard 路由和三页签保留，卡片及曲线说明为“运行期观测峰值的平均值/分位数”，数量为有效运行数，说明尚未在后续启动上传的运行不会出现。筛选标签为“峰值时 Activity”“峰值时前后台”，时间说明按记录观测截止而不是精确退出时间。MiB、null、时区、请求取消、独立错误态与 PC 宽度约束不变。

Agent HTTP 共用业务查询；MCP `get_memory_summary/get_memory_trend` 保留工具名及筛选，透传 `runCount` 并说明峰值分布和时间口径；不创建新写工具，不从旧 sampleCount 兼容转换。现有工具结果结构是通用对象，按实际边界补测试和必要校验，不无关重构工具框架。

### 6. 共同固定样例

下列是完整合法事件示例，`requestId/events` 外层及 App Key 使用现有批次格式。时间固定用于自动化时钟，实际联调用相对测试基准平移所有时间，不能直接发送已过期的固定值。

```json
{
  "schemaVersion": 2,
  "eventId": "22222222-2222-4222-8222-222222222222",
  "eventType": "memory_peak_summary",
  "occurredAt": 1794013200000,
  "sessionId": "11111111-1111-4111-8111-111111111111",
  "processId": "11111111-1111-4111-8111-111111111111",
  "anonymousDeviceId": "33333333-3333-4333-8333-333333333333",
  "packageName": "com.shanshui.performance",
  "appVersion": "1.0-test", "versionCode": 1, "buildId": "memory-peak-test-build",
  "environment": "test", "channel": "local", "osVersion": "13", "deviceModel": "test-device",
  "memoryPeakSummary": {
    "processName": "com.shanshui.performance",
    "collectionStartedAt": 1794009600000,
    "lastSampledAt": 1794013200000,
    "pss": {"bytes": 200, "occurredAt": 1794009610000, "foreground": true, "scene": "com.shanshui.performance.MainActivity"},
    "vss": {"bytes": 500, "occurredAt": 1794009620000, "foreground": false},
    "javaHeap": {"bytes": 80, "occurredAt": 1794009630000, "foreground": true, "scene": "com.shanshui.performance.MainActivity"}
  }
}
```

共用固定数据在实施阶段更新到 `docs/datasets/memory-fixed-dataset.md` 及关联 fixture，客户端从同一规范构造契约测试，引用按归档时的跨仓库关系标注，当前链接已迁入本仓库：

| 用例 | 输入/预期 |
|---|---|
| 三项分别创新高 | 同轮采样 `(100,500,20)`、`(200,400,80)`、`(150,450,40)` → 峰值 `(200,500,80)`，保留各自首次出现的上下文 |
| 零、缺失与分位数 | 四轮 PSS 为 `0,100,200,300`，第五轮只有 VSS → PSS runCount=4、平均/P50=150、P90=270、P95=285、P99=297 |
| 独立场景 | 一轮 PSS 在前台 A、VSS 在后台、Java 堆在前台 B → `foreground=true,scene=A` 仅 PSS 贡献一次 |
| 时间边界 | 截止在 from 的轮次计入、在 to 的排除；跨日运行按截止桶归属，第三天上传不移动桶 |
| 延迟补报 | 截止距接收 8 天可接受；恰好 90 天/更早永久过期；其他事件仍遵循原窗口 |

### 7. 分仓执行与联合验收

服务端 tasks 1..5 是阶段 S，完成本仓库代码、文档、自动化和合成 HTTP/数据库验证后可交接；不要求客户端已实现。客户端 tasks 1..5 是阶段 C，使用共同样例和 fake sender 完成本地恢复、协议及自动化；设备频率测量是客户端阶段内的完成条件，可与最终设备联调安排在同一次设备会话。最后阶段 J 在两边 task 6 各自记录证据引用，任何一边都不能仅凭另一边勾选判定通过。

本次用户明确指定 Pixel6 模拟器，J1/J7 的设备执行范围据此采用 Pixel_6/API35/arm64-v8a；真实手机功耗、厂商后台策略及跨 ROM 仍待验证，不能由模拟器结果推断。共同验收证据只维护于[验收入口](../../../../docs/validation/memory-runtime-peaks/README.md)。

| 编号 | 操作 | 必须观察的结果 |
|---|---|---|
| J1 | 真机首次运行，制造内存上涨/下降、前后台切换和 Activity 重建，触发 flush/网络恢复 | current 中三项峰值不回退；本轮无峰值请求，未封存当前轮 |
| J2 | 杀进程并重启，启动中断点分别在封存前、封存后/新 current 前再次杀进程 | 上一轮固定 ID 可靠入队，当前轮新 ID；最多重复发送旧 ID，不丢已持久化峰值 |
| J3 | 阻断网络或注入 503，恢复后补报；模拟服务端已写入但响应丢失 | 保留原事件/身份/版本重试；最终 accepted/duplicate 才清理，数据库统计一次 |
| J4 | 上一轮版本 A，覆盖安装版本 B 后启动 | 上报仍归属版本 A 的 buildId/sessionId/processId，B 的当前轮未上传 |
| J5 | 用共同固定集和真实持久库查询 summary/trend、页面和 Agent/MCP | 三类入口峰值分位数/runCount/空桶/独立上下文一致；不存在普通样本混算 |
| J6 | 测试 8 天、90 天边界、部分指标、非法身份、包名、未来时间、存储失败及旧事件 | 接收/拒绝/退避符合契约，旧样本不能进入峰值查询，其他事件窗口不扩大 |
| J7 | 真机比较采样开销和完整快照写盘路径 | 记录候选与基线，确定缩短后的默认间隔；说明未捕获瞬时尖峰和最后未持久化更新的边界 |

真实峰值无需强求三项在可控时刻精确达到固定字节数；精确聚合断言用可注入采样/时钟的自动化，真机用采集日志、快照、请求和数据库事件 ID 对齐。J2/J3 的确定性故障点优先通过自动化注入补强，不把“连续杀进程未出错”当作穷尽证明。

## Risks / Trade-offs

- [下次启动才可见] → UI/文档明确数据延迟；永不重启、清数据、卸载、TTL/配额淘汰均无法保证补报。
- [采样遗漏及突然杀进程] → 仅声明已观测且已持久化的峰值，客户端即时保存新高并周期保存观测截止；不依赖退出回调。
- [旧新协议不能混用] → 先准备服务端，再切客户端；测试环境保留旧表而不混算，不增兼容层。
- [精确分位数开销增长] → 数据库聚合、有界时间/桶和预算，并以至少十万运行记录检查查询计划、扫描量与资源拒绝；不声称完成生产容量验证。

## Migration Plan

1. 阶段 S 新增峰值表迁移，完成接收/查询/前端/MCP/文档及本仓库测试，保留旧表。部署材料登记迁移，但本次规划不部署服务。
2. 阶段 C 客户端切换新模型；旧 `memory_sample` 本地任务隔离并记录原因，不清空其他功能队列，不伪造峰值。封存有效历史快照先于建立新轮。
3. 阶段 J 使用同一测试应用、包名和授权完成上述矩阵；记录两仓库 Git 版本、构建、设备与真实持久化模式，截图和专项证据只维护唯一入口。
4. 回滚按成对版本处理；新旧表均不做破坏性清理。不能把新峰值队列交给旧协议上传器继续发送。具体发布/云端操作另行授权。
5. 联合测试通过后才分别 verify/archive，并修正跨仓库归档互链。不得提前勾选 J 或把独立阶段通过写成端到端完成。

## Open Questions

客户端阶段 C 已通过八段各十分钟的候选/基线实验，选定前台 10 秒、后台 30 秒，完整路径及频率结果唯一入口为[客户端测试页](../../../../android/docs/knowledge-base/11-构建测试与本地发布.md#运行期内存峰值验证)。此选择不改变 wire 契约；模拟器主线程心跳为顺序实验，未测真机功耗和绘制帧因果。
