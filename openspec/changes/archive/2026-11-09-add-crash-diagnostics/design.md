# Crash 诊断上下文技术设计

## Context（现有实现与约束）

动机与已确认范围见 [提案](proposal.md)。本设计涉及 Android、后端、前端、数据迁移与隐私/资源边界，满足编写设计文档的条件；以下均为待实施设计，不代表设备或环境已验收。

| 当前事实 | 对设计的约束 |
|---|---|
| `CrashReporter` 接收 Thread 后只向工厂传 Throwable；3500ms 超时仅包围同步上传 | 必须保留崩溃线程身份，为诊断单独设置等待预算 |
| `FileCrashQueue.enqueue` 拒绝覆盖同 eventId；上传器可周期读取 `events/` | 补充采集记录必须隔离到冻结完成，不能用普通重复入队实现追加 |
| 主异常最多 16 层、合计 200 帧；客户端默认批次 512 KiB，服务端单事件 256 KiB | 增加实际序列化字节预算，不能把辅助帧计入主异常帧配额或只控制批次大小 |
| Crash 事实与状态分开；初始状态先保存，随后保存事实才确认 accepted | 新上下文必须参与同一接收确认和部分写入恢复 |
| 状态从 raw_chain 替换为 retrace_text，首次成功固定 | 非堆栈上下文不能塞进即将被替换的主 stack |
| PostgreSQL publication 保存确定编码，单份 pending_payload 上限 2 MiB | 辅助栈必须进入同一确定编码与读回核对，并有明确总载荷限制 |
| Crash 模块仅依赖 Core/Transport，Metrics 内有内存读取逻辑 | 可参考现有口径，不引入 Metrics 或原生工具的隐式运行依赖 |

实现依据：

- [Android Crash 处理器](../../../../android/performance-crash/src/main/java/com/shanshui/performance/crash/CrashReporter.kt)、[事件工厂](../../../../android/performance-crash/src/main/java/com/shanshui/performance/crash/CrashEventFactory.kt)、[文件队列](../../../../android/performance-crash/src/main/java/com/shanshui/performance/crash/FileCrashQueue.kt)、[上传器](../../../../android/performance-crash/src/main/java/com/shanshui/performance/crash/CrashUploader.kt)。
- [Crash 接收](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashEventProcessor.java)、[状态存储](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/ClickHouseCrashStateStore.java)、[后台处理](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashBackgroundProcessor.java)、[确定编码](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/CrashStateJson.java)、[已有发布表](../../../../backend/src/main/resources/db/migration/V15__background_crash_symbolication.sql)。
- [详情页面](../../../../frontend/src/views/CrashEventDetailView.vue)、[当前分析证据](../../../../backend/src/main/java/com/shanshui/apmserver/agent/internal/AnalysisEvidence.java)。
- 外部参考：xCrash 仓库的 `doc/tombstone_java.txt`、`JavaCrashHandler.java` 和 `Util.java`。仅参考采集逻辑，不新增跨仓库构建依赖，也不复制样例设备数据作测试夹具。

现有规范中的“单份 stack”和“首次成功”继续指主异常。其他线程使用独立的当前栈结果，不把整份 tombstone 作为主异常文本。接入文档对网络线程池的描述与当前 Handler 内同步 `runBlocking` 上传不完全一致；实施时按实际最终行为原位修正，不把已有 3500ms 描述成整个采集流程的硬超时。

## Goals / Non-Goals（设计目标与边界）

**目标：** 在核心异常可靠保存的基础上增加有界诊断；现场身份可区分，资源或权限失败可解释；主异常、辅助栈和指纹以一致状态发布；大查询继续使用轻量投影。

**边界：** 只复用当前 JVM fatal、JSON v2、文件队列、ClickHouse 和既有后台任务。诊断随 Crash 启用，首版不新增逐项开关、任意 shell 命令、线程白名单、持续日志进程或通用采集插件。AI 分析证据仍只冻结主异常，现有证据 3/报告 4 不隐式扩容或升级。

## Decisions（技术决策）

### 1. 使用结构化上下文和必要的有界原文

入站扩展为 `crash.diagnostics`；它可缺失，因此低资源降级记录仍满足核心 Crash 契约。诊断不写入公共 attributes，也不增加附件上传端点。详情增加独立 `diagnostics` 字段，缺失时为 null；`stack.content` 继续只包含主异常材料，不能同时嵌入诊断副本。

各分区共用 `status=ok|partial|unavailable|skipped`、可选安全 `reason`、`truncated` 和可获得的 `capturedAt`。`ok` 可有空数据；`partial` 表示有数据但发生读取失败或裁剪；`unavailable` 表示尝试后无可用内容；`skipped` 表示因预算或资源不足未尝试。原因限定为受支持类别，例如 `permission_denied`、`unsupported`、`timeout`、`io_error`、`memory_pressure`、`interrupted`、`size_limit`、`count_limit`，不传异常原文。

| 分区 | 入站与展示内容 |
|---|---|
| `crashedThread` | Java 线程标识、名称、状态、优先级、daemon、是否主线程和处理器入口取得的系统 TID；不重复主异常帧 |
| `otherThreads` | 有界线程条目和原始帧，保留采集顺序；可获得时提供观测总数与遗漏数，未知数值为空 |
| `logcat` | main/system/events 分别保存状态及有限行文本，只读取当前进程可访问内容 |
| `memory` | 结构化进程摘要，以及有界 meminfo、status、limits 原文；内存字段统一使用带 Bytes 后缀的安全非负整数 |
| `openFiles` | 已知 FD 总数、有限编号和脱敏目标路径；读取竞态或不可读条目单独说明 |
| `process` | 数值 PID、进程名、前后台状态、可取得的进程启动时间与 SDK 初始化时间；时间来源不同需明确标记 |
| `device` | API level、ABI、厂商、品牌、系统构建 fingerprint；已有版本、机型、包名等继续使用公共元数据，详情组合展示 |

Java 线程标识采用十进制字符串，避免前端整数精度损失；只有崩溃线程能直接取得系统 TID 时记录该值，其他线程不把 Java ID 冒充 TID，不依赖隐藏接口、线程名匹配或反射读取 nativePeer。前后台状态无法确认时为空，不将“未观察到 Activity”直接解释为后台。旧 API 无可靠进程启动时间时为空，SDK 初始化时间不得冒充进程出生时间。

内存摘要区分 Runtime 的 Java 堆已用/最大值、Debug 的 Java/Native 堆 PSS、Native 分配量、总 PSS、RSS/VSS/Swap 等各自可取得的口径；字段不可用时保留缺失，不能返回伪造零值，也不复用内存运行峰值事件。数值上限与现有 JSON 安全整数约束一致。读取公开 API 与自身 proc 文件，不引入堆转储。

备选：上传整份 tombstone 文本，解析和大小控制更粗且容易把日志当异常链；全部转换成密集数值字段会膨胀首版契约。因此仅将需要选择、计数和展示的内容结构化，proc 详细文本保留有界原文。

### 2. 核心记录先落盘，冻结后才进入上传队列

在 `performance-crash-reporter/` 下增加队列内部的 `capturing/`，沿用 `AtomicFileWriter` 和同一队列同步边界：

```text
UncaughtExceptionHandler
  --> capturing/eventId.json (core persisted)
  --> bounded diagnostic collection
  --> events/eventId.json (frozen record)
  --> existing uploader

next startup
  --> recover capturing record
  --> events/eventId.json (original identity, interrupted status)
```

处理器入口冻结 Thread、Throwable、身份、发生时间和 TID。先用既有有界异常映射生成核心记录，原子写入 capturing；再提交到初始化时预建的单个诊断工作线程。工作线程只产生受限结果，不写队列、不上传。处理器收取预算内结果、脱敏和裁剪后原子写入 events，成功后删除 capturing。上传器仅枚举 events，因此不存在核心已被确认删除后又被补写的窗口。

启动恢复在安排启动/周期上传前完成：events 已存在则不覆盖，只清理重复 capturing；仅有 capturing 则转换成中断记录后发布；损坏记录沿用安全隔离策略。恢复保留原创建时间和过期窗口，不能用新启动身份、时间或快照替代。同一进程多次异常沿用现有处理门禁；不增加跨进程共享队列写锁框架。

诊断总等待初值为 1500ms，所有 logcat 缓冲区共享最多 500ms 且服从剩余总预算。超时后封闭本次结果收集，主动销毁日志子进程、关闭流；迟到结果不能落盘或再次入队。原有最多一批、3500ms 上传在冻结之后执行，仍由现有 flush 互斥保护，最终在 finally 语义中委托原异常处理器。

`Thread.getAllStackTraces()` 本身会先取得整份映射，线程数量限制只限制输出，不能宣称限制了 JVM 内部的捕获时间和峰值内存；工作线程隔离仅限制处理器等待。受控 OOM/异常链含 OOM 时跳过全线程、外部日志和 FD 明细等高分配操作，优先尝试最小核心记录与廉价内存信息。此路径尽力而为，核心映射、VM 或文件系统失效仍可能无法保存。

备选：直接在 events 覆盖会和上传竞争；先上报后补充会被幂等机制忽略；只在进程退出前拼成一个大对象，会让一次采集失败丢失全部核心异常。capturing 是现有本地队列的一项状态，不是新增上传链路。

### 3. 字节预算优先于逐项数量上限

初始内部限额如下，属于待设备验收的实现默认值，不是已测性能结论：

| 项目 | 初始上限 |
|---|---|
| SDK 完整单事件目标 | 240 KiB，且必须装入实际 maxBatchBytes 的单事件批次 |
| 服务端完整单事件 | 保持 256 KiB |
| 诊断总编码字节 | 128 KiB，按核心事件占用动态缩小 |
| 其他线程 | 64 个、每个最多 32 帧、合计最多 48 KiB 编码字节 |
| logcat | main 200、system 50、events 50 行；合计最多 48 KiB，单行最多 2 KiB |
| 内存原文与摘要 | 合计 16 KiB |
| FD | 最多 128 条、合计 12 KiB；总数可大于条目数 |
| 进程、设备和崩溃线程元数据 | 合计 4 KiB |

所有上限均以脱敏后 JSON 的 UTF-8 编码为准，包含对象键、转义和状态说明；分项上限不是保证获得的配额。先给核心事件和最小状态留出空间，再采集/保留诊断。辅助内容裁剪时优先移除较旧日志、FD 明细和优先级较低的线程，保留可取得的主线程。主异常自身超预算时先缩短消息，再有标记地裁剪帧和异常节点，仍至少保留一个有效异常节点和一帧；裁剪事实保存于 `diagnostics.coreTruncation`，即使没有辅助采集数据也可返回最小说明，详情需提示主异常在源端已裁剪。服务端接收后不再对已接受主异常做隐式裁剪。

冻结前按当前上传器 UUID v4 requestId 的固定编码长度预留单事件请求外壳，检查能装入当前批次；真正上传时仍用实际 requestId 再验证完整批次，小 maxBatchBytes 配置同样生效。捕获阶段写下的核心记录也必须满足该预算。正常批次选择仍保留原行为，只对新诊断捕获过程补上单事件保护，服务端不因为客户端已裁剪而放松恶意输入校验。

logcat 用参数列表启动固定 `/system/bin/logcat`，按当前 PID 筛选，不使用 shell 拼接。API 24 以下走受限读取与 PID 行过滤；不为拿系统日志请求新增特权权限。stdout/stderr 合并或同时有界排空，按字节流分块读取后再切行，禁止先无限 readLine 再截断。各缓冲区使用各自行数，修正参考实现 events 误用 system 行数的细节。崩溃本次 AndroidRuntime 日志可能在委托后才产生，因此不以 logcat 必须含本次 fatal 文本作为验收条件。

备选：只使用 gzip 或批次限额无法阻止服务端单事件拒绝；把完整日志或全部线程先读入内存后裁剪，不能提供实际资源边界。

### 4. 持久化分离不可变上下文与当前线程栈

在下一份版本化 ClickHouse 迁移中增加：

- `apm_crash_fact.diagnostics_payload String CODEC(ZSTD)`：除原始其他线程帧之外的脱敏上下文及线程元数据；无诊断使用明确空值语义。app_start 为空，TTL 沿用事实的发生时间和 30 天保留期。
- `apm_crash_state.thread_stacks_payload String CODEC(ZSTD)`：按 Java 线程标识存放辅助线程当前栈。初始为 `raw_frames`；成功为完整 `retrace_text`；空帧为 `not_applicable`。每项包含独立处理状态、原因和可取得的实际来源，线程顺序保持采集顺序。
- 事实与状态保存稳定的 `capture_sha256`，对确定编码的脱敏核心 Crash 与完整诊断计算，不包含 receivedAt 或上传批次身份。恢复部分写入时检查定位及摘要，拒绝把不同诊断现场拼到已保存的初始状态。

入参 `CrashPayload` 可以携带 diagnostics，但写入主 `stack_payload` 时显式剥离它；事实中的线程元数据不再包含 frames，避免成功后仍有第二份逻辑原始辅助栈。辅助线程的原始材料只随当前状态流转，非堆栈上下文只保存一次。

现有接收顺序保留为“状态确认，再确认事实”。任何一侧失败均不计 accepted；同内容同身份重试修复缺失一侧，确认丢失重试不重复计数。已保存的新事件如果同 eventId/构建/时间却提交不同 capture_sha256，按永久事件错误 `CRASH_CONTENT_CONFLICT` 返回，不能替换首次内容；错误沿用批次现有 index/eventId 定位和部分接受结构。历史无诊断且摘要为空的记录不回填现场，也不从旧表回退；同 eventId 不能通过新诊断请求补齐历史记录。

`CrashFact`、`CrashState`、内存适配器、状态确定编码和读回相等核对均同步扩展；业务详情在原受限定位后组合一次事实上下文和一个状态版本。统计/摘要 SQL 明确只投影需要的事实与状态列，核实 EXPLAIN 和实际读取列，不依赖外层裁剪 SELECT * 的假设。

备选：另建诊断表会增加跨表接收确认；把上下文复制到每个状态版本会增加存储和重放字节。本方案只扩展现有两类载体及确定发布流程。

### 5. 辅助线程随主异常首次成功尝试固定

继续使用现有 appId/buildId 工作、mapping 租约、候选查询和 publication。先还原主异常；主异常未成功时按现有逻辑保留全部原始材料并重试，不启动单独辅助任务。主异常成功后在同一 mapping 租约下处理辅助线程，主异常正式指纹先按原算法计算，辅助输出不能混入其输入。

每个辅助线程保留 `stack={format:raw_frames|retrace_text, content:...}` 与独立 `symbolicationStatus=pending|failed|symbolicated|not_applicable`。有帧时初始 pending；空帧不适用；完整还原成功后用文本替代原始帧；失败、超时或超限则保留原始帧和原因。辅助阶段总等待预算初值为 2000ms，单线程完整输出最多 32 KiB、全部辅助当前栈最多 256 KiB 编码字节；总预算不足时剩余线程固定为 failed/budget_exhausted，不能截断还原文本伪造成功。原始辅助栈总上限 48 KiB，为必要降级结果预留空间。

当前 `SymbolRegistry.retrace` 只有输出上限且同步调用 R8，不能靠调用前后检查时间实现这项等待保证。符号模块增加专用于辅助栈的有界入口：全进程最多一个工作线程、无等待任务队列，忙时立即返回 parser_busy；允许调用方指定更小的输出限制和本次剩余等待时间。工作者持有同一固定 mapping 的独立引用，实际退出前不能释放文件租约或操作许可。等待超时只关闭结果接收并尝试取消，不假称 R8 已停止，也不新建替代线程堆积；工作者最终退出时统一释放资源。后台协调者可据此固定辅助失败并继续发布主异常，迟到结果不接触 publication。保持既有主异常同步入口和共享操作许可总量，补充固定文件引用保活的专项测试。

主异常成功、指纹、实际来源与辅助最终结果进入同一 `CrashState`，通过 publication 分配单一版本并登记确定 JSON 字节，随后重放/读回核对。新增状态字段必须进入摘要和相等性比较。主异常成功后固定全部辅助结果，包括失败结果；后续 mapping 更新不补算。这一取舍避免引入第二套队列、成功终态重新打开或独立 mapping 生命周期。

PostgreSQL publication 的单份登记载荷通过新 Flyway 迁移和应用常量从 2 MiB 调整到明确的 4 MiB，以容纳原主异常最大输出、有限辅助输出与嵌套 JSON 转义；保留既有 16 MiB 在途总字节配置、候选数量、查询响应 8 MiB 及退避机制。登记前按最终 JSON 实际字节执行校验并优先将辅助输出降级为已收集原始帧；新增测试覆盖极端转义，证明辅助失败不会使原本可发布的主异常因新增内容越界。若主异常本身已超原有可处理边界，仍按现有 output_limit 失败，不伪造成功。

备选：成功后继续独立重试辅助栈需要新的调度和来源固定机制；只保存原始辅助帧会降低混淆版本可读性；把所有栈拼成一个字符串处理可能混淆线程边界、指纹与候选。因此采用同租约逐线程处理和一次最终发布。

### 6. 使用现有详情权限和页面组织

`CrashEventDetailResponse.diagnostics` 返回组合视图；原 `stack` 与 symbolicationStatus 表达主异常。页面沿用现有元数据和主异常区域，在其后增加诊断分区：其他线程按身份展开，默认优先显示主线程；logcat 按缓冲区切换；内存展示摘要和可展开原文；FD 展示总数与有限明细；进程/设备展示已知环境字段。

数据全部走现有详情请求及其预算，不新增查询参数、独立列表或批量诊断接口。未知状态/格式由前端和 MCP 当前格式校验拒绝，空数据与缺失分开。应用/事件切换时同步清理线程选择和日志缓冲区；文本使用 Vue 转义，不能使用 v-html 或自动加载内容链接。后台成功标识仅附于主异常或对应线程。

Agent HTTP/MCP 的现有详情输出可包含同一 diagnostics，沿用应用授权与响应上限并补充格式回归；本地分析任务仍显式选择现有主异常片段，不把新详情字段自动复制进证据，证据大小、签名和版本维持现状。界面不宣称分析任务已经使用其他线程或日志。

### 7. 隐私、排障与文档同步

客户端和服务端各自对诊断文本执行有界脱敏。新增服务端凭据规则，覆盖 Bearer、Authorization、Cookie、token、password、secret、API key 等明确形式；规则逐行有界运行，保留结构化 ID 和内存数值。路径仅保留资源类别或安全后缀，自有私有数据文件名以占位替换；不原样保存可能带用户 ID 的路径。规则脱敏不能宣称识别任意业务秘密，接入文档须说明边界。

成功日志记录 capture/freeze/enqueue/accepted/published 等阶段的安全数量、字节、耗时和状态；失败只记录固定原因，不打印 Throwable 原文、完整请求、日志行、路径或设备唯一标识。测试可使用合成哨兵秘密核对持久化记录、接口和排障输出。

实施时按 [任务清单](tasks.md) 原位更新根数据/可靠性/隐私主题、各端实现与测试说明、API/错误码/客户端接入；不新增日常操作流水。当前源码、自动化、模拟器冒烟与目标限制分别表述，最近环境核验只在根状态页更新。

## Risks / Trade-offs（风险与取舍）

- 全线程捕获仍可能造成 ART 暂停和瞬时分配；以单工作线程、OOM 跳过和受控线程规模实验降低影响，明确 1500ms 是等待预算，不承诺 VM 调用可被强制中断。
- logcat/Debug/proc 受 ROM、API 和资源压力影响；逐分区降级，至少覆盖最低支持 API、Android 10 和当前高版本，未执行设备范围明确保留待验收。
- Crash 处理器、上传线程或磁盘锁处于异常状态时仍可能无法写入；先写最小核心、上传前冻结，验证关键写入点的中断恢复，不引入绝对可靠性承诺。
- 辅助结果失败在主异常成功后不再补算；详情保留原始帧及原因，后续需要重处理时另行设计显式能力，不能暗中改写首次成功结果。
- 较大诊断与状态输出增加存储、扫描及发布在途字节；分项/总量双重上限、ZSTD、轻量投影、保持在途总预算，并用真实数据库与序列化压力夹具验证。
- 迁移只给历史行增加空默认值，不能重建历史现场；发布保持后端先于新 SDK，不能将旧事件缺失诊断当作采集故障。

## Migration Plan（实施发布与回退）

1. 新增 ClickHouse 版本化迁移，为事实/状态补充有默认空值的字段；新增 Flyway 迁移调整既有 publication 检查约束，不改写已发布脚本。保留既有 TTL，不回填或删除遥测。
2. 先验证迁移、内存与数据库适配器、含新载荷的发布确认丢失恢复，再发布后端和详情消费者；缺少所需列时启动检查明确失败。
3. 发布 Android SDK/受控测试 APK，验证主线程与后台线程异常、断网重启补传、采集中断和 Release mapping；实际环境升级属于后续明确授权的发布任务。
4. 紧急回退先停止新 SDK 采用，保留服务器读取能力；新增数据库列可保留。已有新格式 publication 未确认载荷不能交给旧发布器处理：先停止后台、备份并完成/核对新载荷，再降级应用。不得靠清空 pending_payload、删除队列或删除事件回退。
5. 规划本轮不执行迁移、构建、设备操作或部署；实施验收完成后才更新“已验证”状态。
