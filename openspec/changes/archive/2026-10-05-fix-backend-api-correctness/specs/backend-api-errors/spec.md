# 后端请求错误响应规范

## Purpose

为网页、只读 Agent 和 Worker 提供一致且可安全处理的请求错误，使调用方能够识别参数绑定、正文解析及字段校验失败，保留 HTTP 状态与业务错误语义，并避免在诊断响应中泄露提交的密码、凭据和原始请求内容。

## ADDED Requirements

### Requirement: 框架请求错误使用统一响应

已通过相应安全过滤链并进入 MVC 处理的请求，因 JSON 正文缺失或不可解析、参数类型错误、缺少必填参数/请求头/part、字段校验或方法参数校验失败时，系统 MUST 返回 JSON 错误对象，包含 `code`、`message`、`retryable`、`requestId`、`errors`、`timestamp`。requestId 沿用平台当前可空约定；错误响应 MUST NOT 是空正文或默认 HTML 页面。

正文解析和类型解码错误 MUST 使用 `400 INVALID_REQUEST_BODY`；路径、查询或请求头类型错误及必填项缺失 MUST 使用 `400 INVALID_PARAMETER`；已解码后的 Bean/方法参数校验失败 MUST 使用 `400 VALIDATION_FAILED`。不支持的 HTTP 方法 MUST 返回 `405 METHOD_NOT_ALLOWED` 并保留 Allow 头；不支持的媒体类型 MUST 返回 `415 UNSUPPORTED_MEDIA_TYPE`。这些错误的 retryable MUST 为 false。

#### Scenario: 非法 UUID 和非数字分页
- **WHEN** 已认证请求携带非法 appId UUID，或将 limit/page/size 设为非数字文本
- **THEN** 返回 400 和 INVALID_PARAMETER，errors 在可定位时提供字段名

#### Scenario: 缺少正文或 JSON 格式错误
- **WHEN** 要求 JSON 正文的请求没有正文、JSON 语法错误或字段类型不可解码
- **THEN** 返回 400 和 INVALID_REQUEST_BODY，不暴露原始正文

#### Scenario: 必填字段校验失败
- **WHEN** 创建 Worker 凭据的已认证请求提交空对象，或其他请求违反字段长度/必填约束
- **THEN** 返回 400 和 VALIDATION_FAILED，使用可读字段错误，不执行业务写入

#### Scenario: 必填参数或上传 part 缺失
- **WHEN** 请求缺少框架声明的必填查询参数、请求头或 multipart part
- **THEN** 返回 400 和 INVALID_PARAMETER，已定义专项业务错误的入口保持该专项错误

#### Scenario: 方法和媒体类型错误
- **WHEN** 已通过鉴权的请求对现有路由使用不支持的 HTTP 方法或媒体类型
- **THEN** 分别返回带 Allow 头的 405 或 415，均使用统一 JSON 错误结构

### Requirement: 错误响应保持安全和既有业务语义

字段错误 MUST 使用安全字段名及受控说明，不回显 rejectedValue、密码、Authorization、租约秘密、原始正文、异常堆栈或数据库内容；errors 最多返回 50 项。现有业务错误码、状态、Retry-After 和批次事件级部分接受语义 MUST 保持。鉴权及 CSRF 失败 MUST 继续由原安全边界处理；新请求校验处理 MUST NOT 将服务内部异常统统转换为 400，也不能将校验失败转换为成功响应。

#### Scenario: 错误输入含敏感信息
- **WHEN** 含密码、Token 或租约值的请求反序列化或校验失败
- **THEN** 响应和新增日志只含受控错误说明，敏感值与原始异常文本不被回显

#### Scenario: 业务与鉴权失败回归
- **WHEN** 请求触发 APP_NOT_FOUND、应用角色拒绝、CSRF 拒绝、查询超时、存储不可用或批次内单事件失败
- **THEN** 保持现有状态、业务错误码、重试头及部分接受结果，不被通用错误映射覆盖
