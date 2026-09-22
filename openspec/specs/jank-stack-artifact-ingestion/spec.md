# jank-stack-artifact-ingestion Specification

## Purpose

为 Android 客户端提供单个 v2 卡顿压缩包的同步上传入口，由服务端完成安全解析、采样数量派生、幂等落库，并仅返回客户端重试所需的最小成功或失败结果。

## Requirements

### Requirement: 只接收 v3 卡顿压缩包

系统 MUST 在 `POST /ingest/v1/stack-artifacts:parse` 直接接收原始 `.rheajank.zip` 字节，请求媒体类型 MUST 为 `application/vnd.shanshui.rheajank+zip`。系统 MUST 只接受 `sourceManifest.schemaVersion=3`、`sourceManifest.artifactType=RHEA_JANK` 且使用 `packageName` 表示 Android 包名的产物，并 MUST 拒绝 v1 `.rheatrace.zip`、v2 卡顿产物、通用堆栈产物、仍使用 manifest `appId` 表示包名的旧产物及旧解析响应兼容分支。

#### Scenario: 接受合法卡顿压缩包

- **WHEN** 客户端携带合法 `X-App-Key` 并上传通过 ZIP、manifest、文件大小、SHA-256 和 Sampling 格式校验的 v3 卡顿压缩包
- **THEN** 系统解析该产物并继续执行卡顿事件归一化和落库，不向客户端返回完整解析报告

#### Scenario: 拒绝旧版通用堆栈产物

- **WHEN** 客户端上传 `schemaVersion=1 / artifactType=RHEA_STACK`、`schemaVersion=2 / artifactType=RHEA_JANK` 的产物、使用旧媒体类型，或 manifest 仍使用 `appId` 表示包名
- **THEN** 系统返回稳定且不可重试的媒体类型或产物契约错误，不进入卡顿落库流程

### Requirement: 服务端忽略客户端尝试采样次数

v3 卡顿 manifest MUST 按升级后的 processor 契约包含 `attemptedSampleCount`。processor 可以读取并校验该字段，但服务端 MUST 忽略该值，且 MUST NOT 使用客户端传入的采样总数生成采样质量、统计或指纹。manifest 未知字段继续由 processor 按严格契约拒绝。

#### Scenario: 接受包含尝试采样数的 manifest

- **WHEN** 合法 v3 manifest 包含事件身份、`packageName`、查询维度、消息区间、采样间隔、进程标识、`attemptedSampleCount` 和文件校验信息
- **THEN** processor 通过 manifest 校验，服务端忽略尝试采样数并完全根据解析报告计算采样数量

#### Scenario: processor 拒绝不符合当前 manifest 契约的产物

- **WHEN** v3 manifest 缺少 `attemptedSampleCount`、缺少 `packageName`、包含旧 `appId` 或包含 processor 不认识的字段
- **THEN** processor 返回不可重试的产物契约错误，服务端不进入映射和存储流程

### Requirement: 服务端从主线程解析证据计算采样数量

系统 MUST 使用 `sourceManifest.processId` 选择报告中对应的主线程，并将消息窗口内带有非空堆栈的有效 `segments` 数量计算为 `parsedSampleCount`。统计 MUST 包含解析器保留的所有有效事件类型，不得只统计 `kCustom`，也不得使用报告顶层 `recordCount`、所有线程记录总数或客户端字段代替主线程采样数量。

系统 MUST 按 `ceil((messageEndNs-messageStartNs)/minSampleIntervalNs)` 计算 `expectedSampleCount`，并按 `max(0, expectedSampleCount-parsedSampleCount)` 计算 `missingSampleCount`。这些字段 MUST 标明为服务端派生值；`missingSampleCount` 只表示理论采样槽位与已解析主线程证据的差值，不得解释为客户端已确认的丢弃次数。

#### Scenario: 从指定样例计算主线程采样数

- **WHEN** 服务端解析由 Android/processor 后续升级产出的 v3 对等样例，其中主线程报告包含 454 个有效 segment，消息区间为 2,296,632,812 纳秒且最小采样间隔为 5,000,000 纳秒
- **THEN** 系统得到 `parsedSampleCount=454`、`expectedSampleCount=460` 和 `missingSampleCount=6`，且不会把 manifest 中的 `attemptedSampleCount` 作为采样数量

#### Scenario: 报告包含其他线程记录

- **WHEN** 解析报告除主线程外还包含工作线程的 segments
- **THEN** 系统保留允许的其他线程诊断证据，但采样质量计数只使用 `processId` 对应线程的有效 segments

#### Scenario: 找不到主线程

- **WHEN** 报告中没有与 `sourceManifest.processId` 匹配的线程，或匹配线程没有任何带非空堆栈的有效 segment
- **THEN** 系统返回不可重试的 422 卡顿证据错误，不生成采样数量、指纹或部分存储记录

### Requirement: 归一化并持久化卡顿事件

系统 MUST 使用 App Key 映射得到的 `appId` 和已校验 `sourceManifest.packageName` 构造事件身份、发生时间、应用、会话、匿名设备和查询维度，并使用解析报告的主线程 segments、调用树、耗时和 warnings 构造卡顿详情。系统 MUST 在存储前执行现有文本规范化、设备标识保护、证据规模限制和服务端指纹生成，并 MUST 将事件原始事实、卡顿事实和卡顿详情作为同一逻辑事件写入按 `appId` 隔离的存储模型。

系统 MUST NOT 在首版持久化原始 ZIP、引入对象存储或异步解析任务；解析报告只有经过归一化且属于卡顿查询所需的事实和证据进入数据库。

#### Scenario: 首次上传完成落库

- **WHEN** 一个合法卡顿压缩包首次以某个 `appId + eventId` 上传且存储可用
- **THEN** 系统保存可供该应用卡顿总览、趋势、Issue、事件列表和事件详情查询的数据，并只计入一次卡顿事件

#### Scenario: 详情超过服务端限制

- **WHEN** 解析后的主线程采样数、堆栈深度、唯一堆栈数、总帧数或归一化详情体积超过配置上限
- **THEN** 系统返回不可重试的大小或证据限制错误，不静默截断用于指纹和统计的证据，也不留下部分新事件

### Requirement: 使用产物 buildId 安全选择 mapping

系统 MUST 在产物完整性和 packageName 匹配校验完成后，使用 App Key 认证得到的 appId 和已校验的 sourceManifest.buildId 从统一符号表注册表选择当前应用的 ProGuard/R8 mapping。客户端 MUST NOT 通过请求头或 manifest 提供 mapping ID、系统 appId 或文件路径；mapping 缺失时系统 MUST 允许以未解混淆状态继续解析，并在内部记录符号化状态。系统 MUST 保持上传时解析还原并保存证据、详情直接读取的处理方式，不在卡顿详情查询时应用 mapping，不回填历史卡顿。符号表替换 MUST 不覆盖已完成事件的堆栈、调用树或指纹，重复事件仍遵循已有幂等语义。

#### Scenario: 项目 mapping 存在

- **WHEN** 当前应用和 buildId 在注册表存在可用文件
- **THEN** 系统固定该版本用于此次解析并保存处理后的方法证据，不允许访问其他应用文件

#### Scenario: 项目 mapping 不存在

- **WHEN** 当前应用没有与 `buildId` 匹配的 mapping 文件
- **THEN** 系统继续解析和落库未解混淆证据，不因 mapping 缺失要求客户端重传或提供路径

#### Scenario: 替换后查看已有卡顿

- **WHEN** 管理员替换 mapping 后查看已完成写入的卡顿
- **THEN** 返回已保存的证据，不重新还原；新上传事件使用当前版本

#### Scenario: 重复上传遇到新版本

- **WHEN** 已完整保存的同一事件在 mapping 替换后再次上传
- **THEN** 返回 duplicate 且不覆盖其已保存证据及指纹

#### Scenario: 注册表暂时不可用

- **WHEN** 上传时无法查询符号表注册表或已注册文件损坏不可读
- **THEN** 返回可重试的存储不可用错误，不把基础设施故障伪装成 mapping 缺失

### Requirement: 返回最小幂等结果

系统 MUST 以 `appId + eventId` 作为幂等键。首次完整写入 MUST 返回 HTTP 200 和 `{"success":true,"status":"accepted"}`；已经完整存在或经本次请求修复完整的重复事件 MUST 返回 HTTP 200 和 `{"success":true,"status":"duplicate"}`。成功响应 MUST NOT 包含 `appId`、包名、mapping 状态、解析报告、堆栈或设备信息。

#### Scenario: 同一事件重复上传

- **WHEN** 客户端使用相同 App Key 和相同 manifest `eventId` 重试已接受的压缩包
- **THEN** 系统返回 `success=true / status=duplicate`，且卡顿次数、Issue 次数、受影响设备数和详情记录均不增加

#### Scenario: 上次写入留下部分数据

- **WHEN** 同一 `appId + eventId` 的原始事实已经存在但卡顿事实或详情缺失
- **THEN** 系统补齐缺失数据后返回 `status=duplicate`，不生成新的事件身份

### Requirement: 保持上传安全与稳定错误语义

系统 MUST 在读取正文前使用应用凭据存储校验 `X-App-Key`，并 MUST 在鉴权成功后校验媒体类型、使用 `Content-Length` 预检及受限流实施 64 MiB 默认压缩请求体上限。系统 MUST 在 processor 产出已校验 manifest 后、读取业务 mapping、生成指纹或执行分析存储前验证 manifest `packageName` 与凭据绑定包名。系统 MUST 对同步解析实施配置化并发上限，并 MUST 将鉴权存储故障、App Key 无效、包名不匹配、媒体类型、大小、产物校验、证据校验、解析繁忙和分析存储故障映射为稳定错误；响应和日志 MUST NOT 回显 App Key、ZIP 内容、完整堆栈或服务端文件路径。

#### Scenario: 请求体超过限制

- **WHEN** 声明长度或流式读取累计大小超过配置上限
- **THEN** 系统返回不可重试的 413 错误并中止解析，不产生数据库写入

#### Scenario: App Key 无效

- **WHEN** `X-App-Key` 缺失、格式错误、使用旧前缀或不能映射到应用凭据
- **THEN** 系统在读取 ZIP 正文前返回不可重试的 `401 INVALID_APP_KEY`

#### Scenario: 应用鉴权数据库不可用

- **WHEN** 应用凭据查询因 PostgreSQL 暂时不可用而失败
- **THEN** 系统在读取 ZIP 正文前返回可重试的 `503 APP_AUTH_UNAVAILABLE`

#### Scenario: 包名不匹配

- **WHEN** App Key 鉴权成功，但解析得到的 manifest `packageName` 与绑定包名不同
- **THEN** 系统返回不可重试的 `403 PACKAGE_NAME_MISMATCH`，不读取业务 mapping、不生成指纹且不执行分析存储写入

#### Scenario: 解析并发已满

- **WHEN** 同步解析许可已经全部占用
- **THEN** 系统返回带重试提示的 503 `STACK_PARSER_BUSY`，且不启动新的解析

#### Scenario: 存储暂时不可用

- **WHEN** 产物已成功解析且包名匹配，但分析数据库写入或部分写入修复遇到临时故障
- **THEN** 系统返回可重试的 503 存储错误，客户端可以使用同一 `eventId` 重试

### Requirement: 卡顿产物使用应用级凭据并校验包名

系统 MUST 使用 `X-App-Key` 查询应用级上报凭据并确定唯一 `appId` 与绑定 `packageName`。v3 卡顿 manifest MUST 包含非空 `packageName`，且该值 MUST 与绑定包名完全一致；客户端 MUST NOT 在 manifest 中声明系统生成的 `appId`。系统 MUST NOT 接受旧 `X-Project-Key`、旧 Key 前缀、全局项目身份或默认包名处理卡顿产物。

#### Scenario: 合法 App Key 和匹配包名上传卡顿产物

- **WHEN** 客户端携带合法 App Key，且通过解析得到的 manifest `packageName` 与该 Key 绑定包名一致
- **THEN** 系统使用 Key 映射得到的 `appId` 继续执行 mapping 选择、归一化、指纹和幂等落库

#### Scenario: 卡顿 manifest 缺少 packageName

- **WHEN** processor 或服务端发现 v3 manifest 没有非空 `packageName`
- **THEN** 系统返回不可重试的 manifest 契约错误，不使用绑定包名静默补值

#### Scenario: 卡顿 manifest 属于其他包名

- **WHEN** 合法 App Key 的绑定包名与 manifest `packageName` 不一致
- **THEN** 系统返回不可重试的 `403 PACKAGE_NAME_MISMATCH`，不生成指纹、不读取业务 mapping、不写入原始事实、卡顿事实或详情
