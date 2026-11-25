# event-batch-transport Specification

## Purpose

为 Android SDK 与服务端之间的 JSON 事件批次提供一致的 gzip 传输契约，减少重复元数据和诊断内容的传输开销，并保证压缩不改变事件身份、应用鉴权、资源限制和持久化确认行为。

## Requirements

### Requirement: SDK 自建会话对 JSON 批次统一使用 gzip

Android SDK 自建网络会话 MUST 对批量事件 POST 请求的 UTF-8 JSON 正文执行且仅执行一层 gzip。该规则 MUST 覆盖Crash、启动、FPS、内存峰值与 ANR 上传；前台挂起汇总 MUST 通过合成批次验证编码与接收，不要求新增 Android 采集上传链路，不区分全量或按需接入。SDK MUST NOT 设置压缩大小阈值、按压缩率回退或因服务端拒绝而改发普通 JSON。宿主完整提供的外部网络会话 MUST 保持借用语义，其传输策略不由 SDK 自动改写。

#### Scenario: 小批次和压缩后变大的批次
- **WHEN** SDK 自建会话提交小于 1 KiB 的合法 JSON 批次，或者 gzip 后字节数大于原 JSON
- **THEN** SDK 仍发送完整 gzip 正文，不发送普通 JSON，也不跳过该批次

#### Scenario: 各事件类型使用一致编码
- **WHEN** 全量或按需接入通过 SDK 自建会话上传JVM/Native Crash、启动、FPS、内存峰值或 ANR 批次，或测试客户端提交合成前台挂起批次
- **THEN** 线上正文均为单层 gzip，解压后的字节与本次批次原 UTF-8 JSON 一致

#### Scenario: 外部网络资源由宿主管理
- **WHEN** 宿主完整提供外部网络客户端和 API 会话
- **THEN** SDK 不替换或修改其传输策略，关闭时也不释放宿主资源

#### Scenario: ANR 批次确认丢失

- **WHEN** 服务端已持久化 gzip 批次中的 ANR，客户端未收到有效业务确认
- **THEN** SDK 保留冻结事件，后续按相同应用、事件 ID、原身份和载荷重试；重复确认不使 ANR 重复计数

### Requirement: gzip 编码保持原逻辑协议和正确请求头

批量事件接口 MUST 保持 `POST /ingest/v1/batches`、`application/json` 媒体类型、Schema v2 和现有应用认证。压缩请求 MUST 携带唯一的 `Content-Encoding: gzip`，声明长度时 MUST 使用压缩后实际字节数，MUST NOT 保留原 JSON 长度。逻辑请求的 requestId、events 与各事件字段 MUST 不因压缩发生变化；已有合法 gzip 正文 MUST NOT 被二次压缩，不支持的预编码 MUST 在发送前失败。

#### Scenario: gzip 请求头与正文匹配
- **WHEN** SDK 发出一个批量事件请求
- **THEN** 请求保留 `X-App-Key`、`X-Schema-Version` 和 JSON 媒体类型，长度对应完整 gzip 字节，解压得到同一 requestId 和 events

#### Scenario: 已有编码不会被覆盖
- **WHEN** 自建会话的批次请求已经带有合法 gzip 正文和对应编码头，或带有其他预编码
- **THEN** 合法 gzip 原样发送且长度正确；其他预编码在本地失败，不发送编码与正文不一致的请求

### Requirement: 批次压缩保持原始预算和有界资源

SDK MUST 先按现有未压缩 UTF-8 JSON 大小预算选择批次，MUST NOT 因 gzip 后变小而扩大批次、减少诊断裁剪或放宽单事件限制。压缩 MUST 在后台上传链路执行，内存占用 MUST 受单批上限约束，不读取整个历史队列、不另存压缩队列。压缩后若触发服务端线上请求上限，MUST 沿用 413 永久失败处理，不放宽上限或回退普通 JSON。

#### Scenario: 原始 JSON 超过预算
- **WHEN** 原始 JSON 超过对应领域的单批预算，即使 gzip 后足够小
- **THEN** SDK 仍执行原来的分批或超大事件处理，不以压缩后大小替代原始预算

#### Scenario: 大小边界上的压缩膨胀
- **WHEN** 原始批次符合 SDK 预算，但 gzip 后超过服务端线上请求字节上限
- **THEN** 服务端返回 413，SDK 沿用现有永久错误策略，且不重发普通 JSON

### Requirement: 服务端严格识别批次内容编码

服务端 MUST 接受无编码头、空白编码头或单独 `identity` 的普通 JSON，以及单独 `gzip` 的压缩 JSON；编码 token MUST 忽略大小写和首尾空白。重复编码头、多个 token、叠加编码和包含 gzip 子串的未知编码 MUST 返回不可重试 `415 UNSUPPORTED_MEDIA_TYPE`。应用鉴权 MUST 在读取和解压正文前执行，鉴权失败保持既有错误语义。

#### Scenario: 大小写与空白
- **WHEN** 已认证请求使用 `Content-Encoding: GZip` 或首尾带空白的单独 gzip token
- **THEN** 服务端按单层 gzip 解码并进入现有批次校验

#### Scenario: 普通 JSON 仍可接收
- **WHEN** 已认证请求不提供编码头，提供空白值或单独 identity，且正文是合法 JSON
- **THEN** 服务端使用现有普通 JSON 接收与大小限制，不要求调用方必须 gzip

#### Scenario: 拒绝未知和叠加编码
- **WHEN** 请求编码是 `xgzip`、`br`、`gzip, br`、`gzip, gzip`，或者存在重复 Content-Encoding 头
- **THEN** 服务端返回 415 和 UNSUPPORTED_MEDIA_TYPE，不执行任何该批事件写入

#### Scenario: 鉴权失败先于解压
- **WHEN** 无效 App Key 请求携带体积较大或损坏的 gzip 正文
- **THEN** 服务端先返回既有鉴权错误，不读取和解压正文

### Requirement: 服务端完整校验 gzip 并分别限制线上与解压字节

服务端 MUST 对线上实际读取字节和完整解压后字节分别执行配置上限，默认分别为 1 MiB 与 4 MiB；无 Content-Length 时也 MUST 限制实际读取。gzip 必须完整读至结束并验证尾部完整性后才能分派业务。大小超限 MUST 返回不可重试 `413 PAYLOAD_TOO_LARGE`；损坏、截断、校验失败或解压后非法 JSON MUST 返回不可重试 `400 INVALID_BATCH`；失败批次 MUST 不产生事件写入。

#### Scenario: 声明长度和实际线上流超限
- **WHEN** Content-Length 超过线上上限，或未声明长度但实际线上正文超过该上限
- **THEN** 服务端中止读取并返回 413，不继续解压或写入事件

#### Scenario: 高压缩率请求超过解压上限
- **WHEN** gzip 线上大小合法，但完整解压后字节数超过配置上限，包括 JSON 后的空白
- **THEN** 服务端返回 413，不因已读到合法 JSON 对象而提前接受

#### Scenario: gzip 尾部损坏或截断
- **WHEN** gzip 解压能产生完整合法 JSON，但尾部 CRC、长度校验或结束标记损坏或缺失
- **THEN** 服务端返回 400 和 INVALID_BATCH，该批没有事件写入

#### Scenario: gzip 正文或内部 JSON 非法
- **WHEN** 声明 gzip 却发送普通 JSON，或 gzip 合法但解压后不是合法批次 JSON
- **THEN** 服务端返回 400 和 INVALID_BATCH，不回显原始正文

### Requirement: 压缩失败与重试保持领域确认语义

SDK MUST 在收到对应领域合法业务确认后才按既有规则删除队列项。压缩准备失败 MUST 在发起 HTTP 前报告并保留原事件等待既有重试，MUST NOT 当作服务端成功或改发普通 JSON。网络错误、响应丢失与可重试状态 MUST 保留原 eventId、身份和载荷；后续批次 requestId 沿用现有生成规则，不要求跨重试固定。服务端 MUST 保持应用与 eventId 幂等、部分接受和重复确认语义。

#### Scenario: 压缩准备失败
- **WHEN** 请求正文读取或 gzip 生成发生可报告的 I/O 失败
- **THEN** 本次不发出 HTTP 请求，不删除原事件，按既有上传错误路径等待重试

#### Scenario: 接受后响应丢失
- **WHEN** 服务端已接受事件而响应丢失，SDK 后续重新构造 gzip 批次上传原事件
- **THEN** 服务端返回重复确认，事件仅统计一次，SDK 仅在合法确认后删除原队列项

#### Scenario: 部分接受和不完整确认
- **WHEN** 服务端部分接受一个 gzip 批次，或返回与当前请求不匹配的确认
- **THEN** SDK 继续分别处理已确认、永久拒绝与可重试事件；不完整确认不能导致未确认数据删除

### Requirement: 批次压缩不影响其他传输且具有安全日志

SDK MUST 仅对当前基础地址解析出的批次 POST JSON 请求应用本策略，基础地址包含路径前缀时也 MUST 正确匹配。Jank ZIP、泄漏报告 multipart、其他路径和方法 MUST 不被包装 gzip。现有可选网络日志开启时，MUST 记录压缩前后字节数、压缩耗时与结果；失败 MUST 记录受控阶段原因。新增响应与日志 MUST NOT 包含 App Key、Authorization、正文或完整事件堆栈。

#### Scenario: 基础地址包含路径前缀
- **WHEN** 基础地址包含 `/apm/`，SDK 请求相对路径 `ingest/v1/batches`
- **THEN** `/apm/ingest/v1/batches` 的 POST JSON 请求也使用 gzip，不错误匹配其他目录下同名后缀

#### Scenario: 非目标请求原样传输
- **WHEN** SDK 发送 Jank ZIP、泄漏报告 multipart，或其他方法和路由的请求
- **THEN** 原媒体类型、正文和编码保持不变，不额外添加 gzip 编码头

#### Scenario: 成功与失败日志脱敏
- **WHEN** 开启现有网络日志后，批次压缩成功或发生失败
- **THEN** 成功日志有字节数与耗时，失败日志有受控阶段原因，均不泄露凭据或事件正文
