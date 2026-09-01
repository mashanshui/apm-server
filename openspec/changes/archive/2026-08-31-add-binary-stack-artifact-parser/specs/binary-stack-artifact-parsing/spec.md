## Purpose

为 Android/btrace 提供受项目隔离保护的二进制堆栈产物上传解析契约，使服务端能够安全接收 `.rheatrace.zip`、选择可选的 ProGuard/R8 mapping，并返回证据边界明确的完整堆栈报告。

## ADDED Requirements

### Requirement: 接收并解析二进制堆栈产物
系统 MUST 提供独立于 JSON 批量事件入口的原始二进制 API，以 `application/vnd.shanshui.rheatrace+zip` 请求体接收 `.rheatrace.zip` 字节，并在解析成功后返回包含 `RHEA_STACK_REPORT` 的 JSON 响应。系统 MUST 保持现有 `/ingest/v1/batches` 行为不变，且 MUST NOT 将通用堆栈报告静默转换为现有 `jank` 事件。

#### Scenario: 成功解析合法产物
- **WHEN** 客户端携带合法项目上报 Key，并上传通过 manifest、SHA-256 和 Sampling 格式校验的产物
- **THEN** 系统返回 HTTP 200、项目标识、mapping 处理状态和完整 `RHEA_STACK_REPORT`

#### Scenario: 缺少二进制请求体
- **WHEN** 请求体为空或媒体类型不是受支持的版本化堆栈产物类型
- **THEN** 系统返回稳定且不可重试的 400 请求错误，不调用堆栈解析器

### Requirement: 对上传产物实施项目鉴权和大小保护
系统 MUST 在解析前校验项目上报 Key，并 MUST 同时使用 HTTP `Content-Length` 预检和受限输入流保护上传入口；缺少或伪造 `Content-Length` 时仍 MUST 由受限输入流实施硬上限。默认压缩产物上限 MUST 为 64 MiB；超过限制的请求 MUST 拒绝，且响应和日志 MUST NOT 包含产物内容、项目 Key 或完整堆栈。

#### Scenario: 项目 Key 无效
- **WHEN** 客户端缺少或提供错误的项目上报 Key
- **THEN** 系统返回与现有上报入口一致的 401 项目鉴权错误

#### Scenario: 上传文件超过限制
- **WHEN** `Content-Length` 已超过服务端配置上限，或流式读取累计字节超过该上限
- **THEN** 系统返回不可重试的 413 大小错误；预检超限时不进入解析，流式超限时中止解析且不生成报告

### Requirement: 安全选择项目级 mapping
系统 MUST 允许客户端通过可选 `X-Mapping-Id` 请求头提供 mapping 标识，但 MUST NOT 接受客户端文件路径。服务端 MUST 只从配置的 mapping 根目录下、当前项目隔离目录中解析满足标识符约束的文件，并在解析后核对报告中的 `mappingId`。未提供 `X-Mapping-Id` 时系统 MUST 允许不解混淆解析，并明确返回未应用 mapping 的状态。

#### Scenario: 使用匹配的 mapping 解析
- **WHEN** `mappingId` 合法、对应文件属于当前项目且报告中的 mapping 标识一致
- **THEN** 系统使用该 ProGuard/R8 mapping 解析并返回 `applied` 状态

#### Scenario: mapping 不存在或不匹配
- **WHEN** `mappingId` 非法、项目目录中不存在对应文件，或解析报告声明了不同的 mapping 标识
- **THEN** 系统返回稳定且不可重试的 400 或 422 错误，不泄露服务端文件路径和其他项目的 mapping 是否存在

### Requirement: 返回稳定的解析错误和资源繁忙语义
系统 MUST 验证解析结果的 JSON、报告类型和报告 Schema，并 MUST 将损坏 ZIP、非法条目、manifest/校验和错误、格式版本不支持和报告结构错误映射为稳定的不可处理产物错误。系统 MUST 对同步解析并发实施配置化上限；容量已满时 MUST 返回可重试的资源繁忙错误。

#### Scenario: 产物不能解析
- **WHEN** 解析器拒绝 ZIP、manifest、哈希、Sampling 数据或报告结构
- **THEN** 系统返回 HTTP 422 和稳定的 `INVALID_STACK_ARTIFACT` 错误码，不把解析器异常文本作为客户端契约

#### Scenario: 解析并发已满
- **WHEN** 同时解析数达到服务端配置上限
- **THEN** 系统返回 HTTP 503、稳定的可重试错误码和重试等待提示，不启动新的解析任务
