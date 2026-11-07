# 后端符号表实现

## 模块边界

`symbol` 模块对外只暴露 `symbol.api`：`SymbolRegistry` 负责按 `appId + buildId` 固定当前版本租约并为后台 Crash 与同步 Jank 提供 Retrace，`SymbolFileMetadata`、页面结果和符号化结果属于 HTTP/领域契约。数据库、受控文件卷、R8 适配器和事务写入均位于 `symbol.internal`。Crash 和 Jank 不访问符号表内部实体、仓库或文件路径。

## 上传和替换链路

网页请求的 Session 和 CSRF 先由 Spring Security 检查，应用成员角色由 `AppAccessControl` 检查。服务端把 multipart 流写入符号目录同卷临时文件，同时计算 SHA-256 和大小，再使用官方 R8 Retrace mapping API 校验格式；空文件、超限文件、非法 mapping 不会发布。

首次上传在 `app_symbol_file` 建立 `appId + buildId` 活动局部唯一记录和审计记录，并在同一 PostgreSQL 事务唤醒构建任务，发布使用不可覆盖的 UUID 文件名并优先同卷原子移动；文件系统不支持时回退普通移动。相同摘要重复上传返回幂等成功；不同摘要返回 `SYMBOL_CONFLICT`，不自动覆盖。替换必须提交网页确认时看到的 `expectedRevision`，数据库使用 `symbolId + revision` 条件更新，成功后 revision 加一并写入 `app_symbol_file_audit`。相同摘要替换在 revision 校验后返回当前版本，不增加 revision 或审计记录。旧文件使用进程内读取租约计数，最后一个租约释放后才回收；服务启动时只清理数据库未引用且无租约文件，该租约不提供跨实例协调。

数据库提交确认未知时不能根据异常断言回滚：已发布文件保留，避免删除可能已提交引用；未发布暂存仍清理。后续启动按已确认的活动引用清理孤立文件。此时 API 返回可重试失败，调用方用相同构建和字节重试对账，不假报上传成功。

当前列表在 PostgreSQL 过滤应用、活动状态和可选 buildId，以更新时间/symbolId 排序及 keyset 游标分页，默认 50、最大 200。DELETE 必须带 expectedRevision，软删除、删除审计与任务唤醒共同提交；活动列表和新文件租约不再选中删除记录。重传创建新 symbolId/revision=1。在途旧租约仍可完成，文件到最后一次释放后退休。进度查询只按构建主键读管理记录，不扫描遥测。

## R8 Retrace

`R8RetraceEngine` 通过固定版本 `com.android.tools:r8:8.9.35` 的官方 Retrace API 读取 `ProguardMapProducer`、`ProguardMappingSupplier` 和 `RetraceCommand`。上传校验阶段主动触发 mapping 读取；后台 Crash 阶段把完整脱敏异常链转换为 R8 标准文本，保留 `Caused by` 顺序、候选结果和无行号帧。上传校验、后台 Crash 和 Jank 共用有界 Semaphore，输出超过 `apm.symbol.max-output-bytes` 或资源繁忙时明确返回失败类别，日志不写 mapping 内容或异常堆栈。

## Crash 与卡顿语义

- Crash 接收先持久化 pending 原始链和事实，再确认 accepted，不执行 R8。后台使用单批固定文件租约还原；首次成功以单份 retrace_text、resolved-v1 正式指纹和实际来源持久化。详情及分析证据仅读状态，不执行 R8。替换/删除/重传均不修改已有成功结果；选错 mapping 的首次成功无法通过重传纠正。
- Jank ZIP 在 processor 解析回调中取得当前 mapping 租约，解析期间固定该文件；映射后的证据和指纹随事件保存。替换只影响之后上传的 ZIP，已保存事件不回算；无 mapping 注册记录时按可选 mapping 语义解析，已有记录对应文件丢失/不可读时返回可重试 503。

## 后台处理组件与恢复边界

新模型的 `CrashBackgroundProcessor` 使用独立单线程调度和 PostgreSQL 构建租约，默认一批最多 100 个事件，同时执行一个构建。单批固定一个文件租约，下一批重新取得活动文件；候选来自新事实/状态，先取最新版本再筛选，时间上限 30 天。大载荷触发查询预算时批次有限减半，单条仍超预算则延期，不扩大查询预算或把资源失败当作空闲。

后台完整输出经过脱敏后生成 `resolved-v1` 指纹；无行号、内联和多候选保持完整。`parser_busy`、文件暂不可用、数据库/发布失败退避 30 秒；确定性 `output_limit/invalid_stack/empty_output/retrace_failed` 保存 failed 原始材料，并停止针对同一 mapping 热重试。新的 symbolId 或 revision 可以重新选中失败事件。每批最多开始 30 秒内的新事件工作，单次 R8 调用仍受已有文件、共享并发和输出限制；该批次时限不是可强制中断 R8 Java 调用的期限。

`CrashSymbolicationPublications` 在短事务和有效租约下决定唯一首次成功，独立序列分配状态版本。发布确认未知时只重放登记的同版本、同字节载荷，读回完整状态摘要匹配后清空 PostgreSQL 完整载荷。默认最多 100 条、合计 16 MiB 未确认载荷，单份编码载荷硬上限 2 MiB；超限结果不能截断成成功。成功元数据在事件保留期内保留，过期再加 5 分钟最大在途宽限后有界清理，每次最多 1000 条。构建任务无租约、无发布记录且 35 天无更新才进入每次最多 1000 条的清理。

`CrashSymbolicationReconciler` 每次只读取 100 个活动 mapping 元数据，检查点与本页唤醒在同一 PostgreSQL 事务提交。重启继续下一页，轮末回到首部，不以事件发生时间推进永久游标；因此更早发生的迟到事件和 ClickHouse 已存、PostgreSQL 通知失败的事件仍能被重新发现。恢复时先发布已决定结果，再获取当前 mapping；没有 mapping 则稳定等待，不持续重算。

当前接收、详情和 mapping 管理已接入上述组件，隔离数据库/故障注入及真实 HTTP 验证见测试页；新任务与新数据集结构见[持久化主题](03-持久化与数据库迁移.md#后台还原的新结构)。初始批次和并发是资源约束，不构成生产吞吐或完成时间承诺。百万历史数据量证据覆盖后台候选及前台一天窗口混合查询，见[查询基线](crash-query-performance-baseline.md#新模型后台候选基线)。

## 配置

| 配置键 | 环境变量 | 默认值 | 作用 |
|---|---|---:|---|
| `apm.symbol.directory` | `APM_SYMBOL_DIRECTORY` | `build/symbols` | 受控 mapping 文件卷，生产应挂载持久卷 |
| `apm.symbol.max-file-bytes` | `APM_SYMBOL_MAX_FILE_BYTES` | `33554432` | 单文件大小上限 |
| `apm.symbol.max-output-bytes` | `APM_SYMBOL_MAX_OUTPUT_BYTES` | `1048576` | 单次 Crash Retrace 输出上限 |
| `apm.symbol.max-concurrent-operations` | `APM_SYMBOL_MAX_CONCURRENT_OPERATIONS` | `2` | 上传校验、后台还原和 Jank 共享并发许可 |

Spring multipart 默认上限为 32 MiB 文件、34 MiB 请求；Nginx 或其他网关必须同步放行 multipart 请求。首版不连接对象存储、不提供构建自动上传、mapping 下载、原生 SO 和 HPROF 符号化。

本轮 succeededCount 只计确认发布的首次成功，failedCount 计已登记失败尝试（可能重复或写回未确认）；从 idle/waiting_mapping 新唤醒开始新轮。idle 只表示本轮没有可处理候选，并非全量事件已成功；迟到事件仍会触发新轮。

以上 multipart 配置与内存异常报告共用；256 MiB HPROF 是报告业务上限，不是默认部署的可上传文件上限。大附件部署要求见[构建配置](05-构建配置与本地运行.md#二进制堆栈解析配置)。

## 验证边界

已通过官方依赖在线解析、后端 `clean build`、真实 release mapping 的完整 Retrace 测试、Docker PostgreSQL 16 的 Flyway/JPA schema 校验、符号文件卷租约/回收测试和 Crash 异常链格式测试；并已在受控本机环境通过管理员网页替换、Crash 连续详情、Jank v3 新事件解析与后端重启读取验收。端到端 HTTP、浏览器和性能数据见[平台测试记录](../../../docs/knowledge-base/08-测试与质量保障.md#跨端证据索引)及[后端性能测试记录](06-测试与质量保障.md#符号表端到端与性能边界)。性能数据是当前主机的小样本测量，不代表生产容量。
