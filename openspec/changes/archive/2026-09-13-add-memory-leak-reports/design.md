## Context

动机见 proposal.md。现有 Memory 模块提供采样指标，ClickHouse 保存领域事件，Identity 提供 App Key 和 Session 应用授权。新报告属于异常证据，不进入 memory_sample 统计。

已读取用户提供的 hprof.json：classInfos 5 项、gcPaths 4 项、leakObjects 4 项和 runningInfo；数值多为字符串，nowTime 无时区，threadList 是数组内的逗号拼接字符串。size 无可靠字节语义，gcPaths 与 leakObjects 无关联键，不能推导 retained size 或按下标关联。报告触发原因可以是线程异常，不能把整份报告标为已确认泄漏。

2026-09-11 已查看 Bugly 的 Java 内存泄漏页：筛选、趋势、按问题聚合的列表，问题单元格支持引用链展开。只借鉴结构与交互，不保存带 token 的参考 URL、真实设备信息或复制第三方数据作为测试数据。

## Goals / Non-Goals

目标：以同步接收、单份报告事实和查询时聚合完成首版；成功响应意味着报告及本次声明的附件已持久化。

非目标：HPROF 解析、报告/堆内存详情路由、附件下载 UI、全量 Bugly 筛选器、工单管理、跨实例附件共享和生产容量保证。行内引用链属于列表展示，不属于内存详情页。

## Decisions

### 1. 统一 multipart 文件上传与 JSON 参数

POST /ingest/v1/memory-reports 只接受 multipart/form-data；X-App-Key 鉴权并核对包名。请求包含必填的 `metadata` JSON part、必填的 `report` JSON 文件 part 和可选的 `hprof` 二进制文件 part。`metadata` 只承载事件、版本、设备和进程等统一参数，不再包含 `report` 对象；业务字段不散落在多个表单字段中，不采用 Base64 大文件方案。首版不支持纯 application/json、ZIP 或压缩请求体。

metadata 必填 schemaVersion=1、eventId（UUID）、occurredAt（Unix 毫秒）、packageName、appVersion、versionCode、anonymousDeviceId、processName。可选 sessionId、buildId、environment、channel。`report` 文件内容是 SDK 生成的完整 JSON 对象，服务端通过有界流解析并保存受限原文；报告文件名不用于存储路径。appId 由 Key 派生。设备/厂商/场景从 report 的 runningInfo 提取，不重复传入；使用 occurredAt 作为异常时间，receivedAt 为服务端接收时间，nowTime 仅保留原文。

成功 HTTP 200 返回 eventId、status=accepted|duplicate、issueCount（不同 signature 数）、attachmentStatus=absent|stored；attachmentStatus 只表示可选 HPROF 是否已经保存。缺少 report 或 report JSON 无效返回 400，大小超限 413，媒体类型不支持 415，写入失败 503 并带 Retry-After。重试必须保持 metadata、report 文件和 HPROF 内容一致；首版不支持同一事件事后补传附件。已存在事件内容或附件摘要不同返回 409 EVENT_ID_CONFLICT。

### 2. 解析边界与限制

report 必须包含对象 runningInfo 和数组 gcPaths、classInfos、leakObjects，数组允许为空。gcPaths 每项要求非空 signature、gcRoot、leakReason、非空 path 和正整数 instanceCount；path 每项保留 reference、referenceType 和可选 declaredClass。classInfos 保留 className/instanceCount；leakObjects 保留 className/objectId/size/extDetail。整数接受十进制数字字符串或 JSON 整数，不接受负数、小数或越界值；objectId 始终按字符串保留。

默认上限：metadata 2 MiB、report 文件 2 MiB、HPROF 256 MiB、总请求 260 MiB、JSON 深度 32、每数组 1000 项、每条路径 256 节点、每字符串 16 KiB。限制通过配置集中管理；无 Content-Length 也按读取字节限制。未知 report 字段在总限制内保留，未知 metadata 字段拒绝，避免拼错必要参数被静默忽略。

缺失运行指标保留 null；暂不对 pss/rss/vss/jvmUsed 等建立可聚合字节列，原始单位按报告保存。size 不映射到泄漏字节；不拆分 threadList 推断 threadCount。原始内容不写日志，前端按文本渲染；匿名设备标识沿用项目隐私处理，精确类名/进程名不做破坏结构的通用掩码。

### 3. 一份报告事实，按 signature 聚合

新增 apm_memory_report，使用现有 ClickHouse 适配方式与内存测试适配器。每行包含 app_id/event_id、异常/接收时间、版本、设备、进程、场景、厂商、sdkInt、dumpReason、规范化 gcPaths 数组及受限报告 JSON、载荷摘要和附件元数据。保留原始对象与类统计，不新增无当前查询用途的子表。

采用 ReplacingMergeTree，排序键 (app_id,event_id)，按 event_id 的固定哈希分区，使重复事件即使冲突时间不同也不会跨分区失去去重。查询先 FINAL，再应用时间筛选并展开去重 signature；不得对原始多条路径直接 count。报告保留默认 90 天。通过单实例按 appId/eventId 串行临界区、持久化重复检查和内容摘要完成冲突检查；摘要基于字段排序的规范 JSON 及附件 SHA-256，忽略 JSON 对象键顺序，保留数组顺序。进程锁必须释放并有界，不能无限缓存事件锁。

列表问题键为应用内 signature。单报告重复 signature 且路径/原因相同则合并；同一报告相同 signature 对应不同内容返回 400。跨报告相同 signature 聚为同一问题，展示筛选范围内最新报告的引用链和原因（occurredAt、eventId 决定稳定顺序），不宣称服务端重新验证了 SDK 分组算法。

发生次数 = 去重的 (eventId,signature) 数；每问题设备数 = 该问题匿名设备去重数；总设备数 = 筛选后至少含一条问题的报告的设备去重数。发生占比的分母为所有匹配问题发生次数，不限当前分页；设备占比的分母为总设备数，各问题设备占比之和可以超过 100%。instanceCount 不作为次数权重。空 gcPaths 报告保存但不增加问题/趋势统计。

### 4. 报告文件解析与可选 HPROF 仅保存

沿用本地部署，不引入对象存储。目录默认 backend/build/memory-report-artifacts，可用绝对路径覆盖；文件名由应用和事件 UUID 生成，不使用客户端路径。临时流式写入、计算摘要、校验结束后原子移动，再写报告事实，成功后返回；同事件重试复用摘要相同的文件。

`report` 文件不作为独立可下载附件落盘：服务端只在接收阶段读取有界流，解析并把受限原文和规范化摘要写入报告事实。`hprof` 才进入本地附件目录，使用临时文件、摘要校验和原子移动；报告文件名和 HPROF 文件名都不能影响存储路径。

数据库写入结果不确定时保留文件并允许重试恢复，不立即删除可能已关联的文件。定期清理至少 24 小时的临时/无引用附件，查询存储不可用时跳过无引用判定；已提交附件默认保留 7 天，过期后仅清理文件，不改变报告及统计。清理限制在配置根目录，跳过活动上传，并覆盖重启中断恢复。附件权限限制为服务进程可访问，不暴露静态资源和客户端文件路径。

“暂不处理”在本提案中对 HPROF 采用受限保存、不解析的约定。若请求包含 HPROF，附件保存失败必须返回失败，不能假装完整接收；只有 metadata 与 report 文件的上传不依赖附件目录可写。

### 5. 查询与页面

新增 GET /api/v1/apps/{appId}/memory-leaks/issues 和 /trend。均要求 Session 应用查看权限。公共筛选 from/to（occurredAt、UTC 左闭右开）、appVersion、deviceModel、processName、scene、manufacturer、sdkInt、dumpReason、anonymousDeviceId、signature，以及 keyword（类名/引用字段/原因的字面子串）。结构字段精确匹配，不实现正则/任意 SQL/比较表达式。

默认最近 24 小时，最长 31 天。issues 使用 page/pageSize（默认 1/20、最大100），sort=occurrences|affectedDevices|lastOccurredAt，order=asc|desc，默认次数降序、signature 稳定次序；返回 total、总发生/设备数和 items。item 返回 signature、leakClass（路径末节点 reference）、leakReason、gcRoot、path、lastOccurredAt、occurrences、occurrenceRatio、affectedDevices、deviceRatio、versions（筛选内去重版本列表，不按版本字符串推断数值范围）。

trend 使用 interval=5m|hour|day（默认 hour），限制最多 2000 桶；返回每桶 occurrenceCount、affectedDeviceCount。空桶补零，设备跨桶不可相加得到总设备数；首版不支持累计、影响用户、泄漏内存 P50。错误返回真实错误，不转换为空数据。

前端路由 /apps/:appId/memory-leaks，入口在内存指标旁。顶部基础筛选及折叠高级筛选，中部两种趋势指标切换，底部聚合表和分页。列表显示问题类名/原因/signature、引用链摘要及展开、最近发生时间、次数/占比、设备数/占比、受影响版本。明确“SDK 报告的疑似问题”；报告中大数组等阈值问题保留 SDK 原因，不强行归为 Activity 泄漏。查询条件进入 URL，列表/趋势独立加载与重试，切换应用取消旧请求并防止旧结果覆盖。无详情链接及 HPROF 解析入口。

## Risks / Trade-offs

- SDK signature 可能随解析算法变化 → 当前保留其分组语义，不跨 signature 自动合并；算法版本治理属于后续增强。
- JSON 中 size 和 mb 语义不明 → 首版保留原值，不提供泄漏字节指标；未来需要 SDK 源码证据才能增加换算。
- 本地文件与数据库无跨资源事务 → 文件优先、摘要复用、失败可重试、延迟清理；实施时必须故障注入验证。
- 大报告查询开销 → 独立提取查询字段/路径，限制时间范围、请求大小、桶数和分页；真实容量单独验证。
- 单实例幂等锁与本地附件 → 本轮不宣称多实例并发一致性或高可用，部署文档明确该边界。

## Migration Plan

新增下一个未占用编号的 ClickHouse 增量 SQL，不改写历史迁移；同步初始化说明。先部署表和附件配置，再启用后端接口、前端入口并用脱敏固定数据验收。回滚关闭新入口和客户端上传，保留表及附件待正常保留策略清理，不自动删除用户数据。主规格同步和归档在实施验收后另行授权。
