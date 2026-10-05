# memory-leak-reports-server Specification

## Purpose

为 Android SDK 提供内存异常分析报告和可选原始附件的可靠接收能力，按应用隔离并持久保存报告，以明确的报告去重和引用链聚合口径支持内存泄漏问题列表与趋势查询，不依赖服务端解析 HPROF。

## Requirements

### Requirement: 接收统一报告信封

系统 MUST 提供 POST /ingest/v1/memory-reports，且只接受 multipart/form-data。请求 MUST 包含 `metadata` JSON part 和 `report` JSON 文件 part；`hprof` 为可选二进制文件 part。metadata MUST 包含 schemaVersion=1、UUID eventId、Unix 毫秒 occurredAt、packageName、appVersion、非负整数 versionCode、anonymousDeviceId、processName，允许 sessionId、buildId、environment、channel，且不得包含 report 对象或未知外层字段。report 文件 MUST 是 SDK 生成的完整 JSON 报告；文件名不得影响解析或存储。系统 MUST 通过 X-App-Key 确定应用并验证包名，拒绝未经授权写入。首版不支持 application/json 请求体或压缩请求。

#### Scenario: 仅上传报告文件
- **WHEN** SDK 使用匹配包名的 Key 上传合法 metadata JSON part 和 report JSON 文件，不提供 HPROF
- **THEN** 系统保存报告并返回 HTTP 200、eventId、status=accepted、issueCount 和 attachmentStatus=absent

#### Scenario: 报告与 HPROF 一起上传
- **WHEN** SDK 在同一 multipart 请求中提供合法 metadata、report 文件和 HPROF 文件
- **THEN** 系统解析并保存报告，保存 HPROF 后返回 attachmentStatus=stored，不启动 HPROF 解析

#### Scenario: 缺少报告文件
- **WHEN** multipart 请求只提供 metadata 或只提供 HPROF 而没有 report 文件
- **THEN** 系统返回明确的 400 校验错误，不保存报告事实

#### Scenario: 应用身份无效
- **WHEN** Key 无效或 packageName 与应用不匹配
- **THEN** 系统沿用既有鉴权/包名错误语义拒绝请求，不保存报告

### Requirement: 有界解析 SDK 报告

系统 MUST 从必填 report JSON 文件中读取并要求 runningInfo 对象及 gcPaths、classInfos、leakObjects 数组，允许空数组；保留其受限原始内容及未知报告字段。每条 GC 路径 MUST 包含 signature、gcRoot、leakReason、正整数 instanceCount 和非空 path，节点包含 reference/referenceType 及可选 declaredClass。计数字段接受十进制数字字符串或整数并校验范围；objectId 保持字符串。默认 metadata 文件上限 2 MiB、report 文件上限 2 MiB、深度 32、数组 1000 项、路径 256 节点、字符串 16 KiB，限制 MUST 可配置并覆盖无 Content-Length 请求。系统 MUST 使用 occurredAt 而非无时区 nowTime 作为异常时间，不将 size 推断为 retained bytes，不按数组位置关联对象和引用链。

#### Scenario: 样例结构解析
- **WHEN** 报告含字符串计数、未知运行字段和不同长度的对象/引用链数组
- **THEN** 系统保留原值及独立数组，正确提取路径，不生成虚假泄漏字节或对象关联

#### Scenario: 非法或超限报告文件
- **WHEN** report 文件必填结构缺失、计数非法、路径超过上限或文件超过 2 MiB
- **THEN** 系统分别返回明确的 400 或 413 错误，不保存不完整报告

### Requirement: HPROF 附件可选且不参与解析

系统 MUST 接收可选 HPROF 并受限保存，不解析文件内容、不提供内存详情或下载入口。report 文件只在接收阶段解析并保存到报告事实，不作为独立下载附件。默认 HPROF 上限 256 MiB、请求上限 260 MiB，均可配置；不得信任客户端文件名作为存储路径。提供 HPROF 时，系统 MUST 在报告和 HPROF 均持久化后返回 attachmentStatus=stored；附件写入失败返回可重试错误，只有 metadata 与 report 文件的上传不得依赖附件目录可写。默认附件保留 7 天，清理不得删除活动上传或改变报告统计。

#### Scenario: 提供合法大小附件
- **WHEN** 合法 metadata、report 文件与 HPROF 一同上传
- **THEN** 返回成功时附件已经保存，未启动任何 HPROF 解析工作

#### Scenario: 保存附件失败
- **WHEN** 附件目录不可写或空间不足
- **THEN** 返回 503 和 Retry-After，不声称完整接收；不带 HPROF 的 metadata 与 report 文件仍可正常上报

### Requirement: 重试与存储失败语义

系统 MUST 按 appId/eventId 去重，单实例并发、跨重启、跨接收日期重试不得增加查询统计。相同规范 JSON 和附件摘要返回 status=duplicate；已接受 ID 的内容或附件变化返回 409 EVENT_ID_CONFLICT，不支持事后补传。报告默认保留 90 天，存储不可用返回 503 和 Retry-After，不返回虚假 accepted 或空查询。系统 MUST 提供持久化和内存验证模式，并标明查询数据来源。

#### Scenario: 重试与冲突
- **WHEN** 同一报告成功后重试，随后以同 ID 修改报告再上传
- **THEN** 重试返回 duplicate，修改请求返回 409，统计仅包含原报告一次

#### Scenario: 附件已保存但报告写入响应丢失
- **WHEN** 客户端以同一事件、报告和附件重试
- **THEN** 系统恢复或识别已完成写入，不重复统计，不因上次响应不确定而删除有效附件

### Requirement: 聚合问题且保持统计口径

系统 MUST 按应用内 signature 聚合问题；每报告每 signature 只贡献一次发生，instanceCount 不作为权重。同报告重复 signature 内容相同则合并、内容不同则拒绝。无路径报告 MUST 保存但不计入泄漏问题/趋势。每问题设备数为匿名设备去重数，发生占比分母为全部匹配问题发生次数，设备占比分母为全部匹配问题的设备去重数，不能使用当前分页作为分母。返回筛选范围内最新报告的路径/原因和去重版本集合。异常报告 MUST 不进入常规 memory_sample 统计。

#### Scenario: 多问题与重复路径
- **WHEN** 设备 D1 的报告 E1 含两条相同 A 和一条 B，D1 的 E2 含 A，设备 D2 的 E3 含 A
- **THEN** A 次数为 3、设备数为 2、发生占比为 3/4，B 次数为 1、设备数为 1、发生占比为 1/4；总设备数为 2，设备占比分别为 1 和 1/2

#### Scenario: 没有引用链
- **WHEN** 合法异常报告的 gcPaths 为空
- **THEN** 接受并返回 issueCount=0，不增加泄漏问题或发生次数

### Requirement: 授权查询问题与趋势

系统 MUST 提供 GET /api/v1/apps/{appId}/memory-leaks/issues 和 /trend 并校验 Session 应用查看权限。公共参数为 from/to、appVersion、deviceModel、processName、scene、manufacturer、sdkInt、dumpReason、anonymousDeviceId、signature、keyword；除 keyword 对类名/引用字段/原因作字面子串匹配外均为精确筛选，未知参数拒绝。时间按 occurredAt 的 UTC 左闭右开区间，默认最近24小时，最长31天。

issues MUST 支持 page/pageSize（默认1/20，最大100）、sort=occurrences|affectedDevices|lastOccurredAt、order=asc|desc，默认次数降序且以 signature 稳定排序；返回 total、总次数/设备数、dataSource、items，每项包含 signature、leakClass、leakReason、gcRoot、path、lastOccurredAt、occurrences、occurrenceRatio、affectedDevices、deviceRatio、versions。trend MUST 支持 interval=5m|hour|day，默认 hour、最多2000桶，返回 UTC 桶起点、occurrenceCount 和 affectedDeviceCount，空桶补零。空结果与查询失败 MUST 区分；不提供泄漏内存量、复现率、用户统计或累计趋势。


持久化查询 MUST 在数据库侧完成报告逻辑去重、引用链筛选、问题聚合、排序及分页，仅返回统计和当前页展示所需数据。系统 MUST NOT 为查询一页问题或趋势在应用内加载时间窗内全部报告正文。全范围总数、设备数、占比、去重版本集合及完整当前页引用链 MUST 保持既有语义；同一 signature 的最新报告时间相同时，MUST 以 eventId 升序选择代表引用链，保证重复查询确定性。

page MUST 为 1 到 2147483647 的整数，pageSize MUST 为 1 到 100 的整数；偏移计算 MUST 不发生整数溢出。超过末页 MUST 返回空 items 并保留匹配范围的总数。趋势超过 2000 桶 MUST 在访问事件存储前返回 400。关键词 MUST 作为字面子串处理，单引号、反斜杠、百分号及下划线不得改变筛选语义或 SQL 结构。

查询 MUST 复用服务端配置的执行时间、扫描量、内存及响应大小预算；不新增客户端必须提供的预算参数。超时 MUST 返回 `408 QUERY_TIMEOUT`，资源超限 MUST 返回 `422 QUERY_RESOURCE_LIMIT`，存储不可用 MUST 返回可重试的 `503 EVENT_STORE_UNAVAILABLE`。引用链或 versions 超过响应预算时 MUST 明确失败，不静默截断。网页与只读 Agent MUST 使用相同业务口径。

#### Scenario: 分页与空桶
- **WHEN** 问题数超过一页，某时间桶无问题上报
- **THEN** 各页使用相同全范围占比分母、稳定排序，趋势空桶为零，跨桶设备数不累加作为总设备数

#### Scenario: 越权与非法查询
- **WHEN** 用户无应用权限，或请求未知参数、超长时间范围、超量桶
- **THEN** 分别返回授权错误或 400，不泄露其他应用报告

#### Scenario: 小页查询较多报告
- **WHEN** 匹配报告数远大于 pageSize，调用方仅请求第一页
- **THEN** 应用只接收聚合结果和当前页所需的引用链，不加载所有报告正文，统计分母覆盖全部匹配问题

#### Scenario: 去重与筛选先于分页
- **WHEN** 数据含重复上报、同报告重复 signature、空路径、跨应用记录，并使用 signature 或 keyword 筛选
- **THEN** 系统按原有每报告每 signature 一次的口径统计，只在授权应用及匹配路径范围计算分母后分页

#### Scenario: 最新路径时间相同
- **WHEN** 同一 signature 的多个报告拥有相同的最大 occurredAt
- **THEN** 系统稳定选择 eventId 升序的报告作为路径和原因来源

#### Scenario: 请求超过末页或超大页码
- **WHEN** 用户请求超过末页但仍合法的 page，或请求大于 2147483647 的 page
- **THEN** 前者返回空 items 和正确总数，后者返回 400，均不发生溢出或 500

#### Scenario: 超量时间桶
- **WHEN** 时间窗和 interval 的组合会生成 2001 个或更多 UTC 桶
- **THEN** 系统在读取报告存储前返回 400，不进行无效的大范围扫描

#### Scenario: 查询或结果超过预算
- **WHEN** 报告扫描、聚合或包含完整路径及版本集合的结果超过配置预算
- **THEN** 系统返回明确的 408 或 422，不伪造空列表、部分占比或截断路径
