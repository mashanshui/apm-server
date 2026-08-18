## Purpose

为 Android 应用提供 Java/Kotlin 致命崩溃的可靠收集、统一统计和可下钻堆栈展示能力，使团队能够按版本和运行环境识别崩溃回归及其影响范围。

## ADDED Requirements

### Requirement: 接收 JVM 致命崩溃事件

系统 MUST 在现有批量上报协议中支持 `eventType=crash` 的 Java/Kotlin JVM 崩溃事件。首期事件 MUST 声明 `crash.kind=jvm` 和 `crash.fatal=true`，并包含 `eventId`、`occurredAt`、`sessionId`、应用版本、`versionCode`、`buildId`、环境和设备信息。

#### Scenario: 接受合法 JVM Crash

- **WHEN** 批次包含完整公共事件信封、JVM 崩溃类型、异常链和堆栈帧
- **THEN** 系统接受该事件并返回批次级的接受数量

#### Scenario: 拒绝暂不支持的 Crash 类型

- **WHEN** 事件的 `crash.kind` 为 `native`，或 `crash.fatal` 为 `false`
- **THEN** 系统按永久错误拒绝该事件，不要求客户端重试，并返回稳定错误类别

### Requirement: Crash 事件可靠收集

Android Crash SDK MUST 在进程终止前将事件写入本地持久队列；网络不可用或服务端返回可重试错误时，SDK MUST 保留事件并使用原始 `eventId` 重新上报。

#### Scenario: 崩溃发生时网络不可用

- **WHEN** Crash Handler 已生成事件但无法访问上报服务
- **THEN** 事件保留在本地队列，并在后续启动或网络恢复后重新上报

#### Scenario: 服务端暂时不可用后重试

- **WHEN** 批量上报因服务端临时故障失败
- **THEN** 客户端按照重试语义保留事件，重试请求继续使用原始 `eventId`

### Requirement: 校验、脱敏和大小保护

系统 MUST 校验事件时间、会话标识、版本和构建标识、异常链结构、堆栈帧数量及事件大小。异常消息、堆栈内容和设备标识 MUST 遵守项目隐私规则，完整事件 MUST NOT 写入应用日志或服务端日志。

#### Scenario: 缺少统计必需字段

- **WHEN** Crash 事件缺少 `sessionId`、`eventId` 或 `occurredAt`
- **THEN** 系统拒绝该事件并返回不可重试的字段错误

#### Scenario: 堆栈或消息超过限制

- **WHEN** 异常消息、堆栈帧或解压后的单事件超过配置上限
- **THEN** 系统拒绝该事件并返回大小错误，不将超限内容写入分析存储

#### Scenario: 异常消息包含敏感内容

- **WHEN** 异常消息或标识字段包含可识别用户信息、路径参数或其他敏感值
- **THEN** 系统在存储和展示前进行脱敏或截断，并禁止在日志中输出原始值

### Requirement: 服务端指纹归组和重复处理

系统 MUST 基于项目、应用、异常类型和规范化堆栈生成服务端 Crash 指纹，并保存指纹版本。相同问题的行号或动态消息变化 MUST NOT 导致不必要的拆组；相同 `eventId` 的重试 MUST NOT 重复计入 Crash 统计。

#### Scenario: 相同行为的堆栈归为同一问题

- **WHEN** 两个事件的异常类型和主要应用堆栈相同，但行号或消息中的动态值不同
- **THEN** 系统将两个事件归入同一 Crash 指纹，同时保留各自的原始事件详情

#### Scenario: 重复上报不放大统计

- **WHEN** 同一 `eventId` 因网络重试被上报多次
- **THEN** 事件详情最多对应一个逻辑事件，Crash 事件数、崩溃会话数和受影响设备数不因该重试增加

### Requirement: 按启动会话统计崩溃影响

系统 MUST 使用 `app_start` 事件的去重 `sessionId` 作为会话分母，并按事件发生时间统计 Crash。系统 MUST 提供崩溃事件数、崩溃会话数、受影响设备数、每千会话崩溃率和无崩溃会话率。

#### Scenario: 计算指定时间范围的崩溃率

- **WHEN** 查询范围内存在启动会话和 Crash 事件
- **THEN** 每千会话崩溃率等于崩溃会话数除以启动会话数再乘以 1000，无崩溃会话率等于 1 减去崩溃会话数除以启动会话数

#### Scenario: 缺少有效统计分母

- **WHEN** 查询范围内没有可用的 `app_start` 会话
- **THEN** 系统将崩溃率和无崩溃会话率标记为不可计算，而不是返回 0

#### Scenario: 按版本比较崩溃表现

- **WHEN** 查询指定基准版本和目标版本
- **THEN** 系统分别返回两个版本的启动会话数、崩溃会话数、受影响设备数和崩溃率，且使用相同筛选条件

### Requirement: Crash 查询和堆栈下钻

系统 MUST 提供 Crash 总览、趋势、问题排行、问题事件列表和单事件详情查询。查询 MUST 限制项目范围、时间范围、返回数量和执行时间，并支持按版本、渠道、环境、Android 版本、设备型号和 Crash 指纹筛选。

#### Scenario: 查看 Crash 问题排行

- **WHEN** 已授权用户查询项目在指定时间范围内的问题排行
- **THEN** 系统返回指纹、异常类型、事件数、崩溃会话数、受影响设备数、首次出现时间和最近出现时间

#### Scenario: 从问题下钻到堆栈

- **WHEN** 用户从问题排行选择一个 Crash 问题或事件
- **THEN** 系统展示该事件的版本、构建、设备、系统、会话、异常链、原始堆栈和符号化状态

#### Scenario: 访问其他项目的 Crash 详情

- **WHEN** 当前用户不属于目标项目，或没有该项目的查看权限
- **THEN** 系统拒绝总览、问题列表和事件详情请求，不泄露目标项目是否存在

### Requirement: Grafana Crash Dashboard

系统 MUST 提供首期 Grafana Crash Dashboard，至少包含崩溃总览、时间趋势、问题排行、版本对比和堆栈下钻入口，并复用项目、版本、渠道、环境、Android 版本、设备型号和时间范围筛选。

#### Scenario: 查看有数据的 Crash Dashboard

- **WHEN** 用户选择项目和时间范围，且范围内存在 Crash 与 `app_start` 数据
- **THEN** Dashboard 展示事件数、崩溃率、受影响设备数、问题排行和可用的下钻链接

#### Scenario: 查看无数据或分母不足的 Dashboard

- **WHEN** 选择的范围没有 Crash 数据或没有有效 `app_start` 分母
- **THEN** Dashboard 明确显示无数据或分母不足，不将其误显示为零崩溃率

### Requirement: 原始堆栈和符号化状态可追溯

系统 MUST 保留原始堆栈、`buildId` 和符号化状态。缺少 R8 mapping 时，系统仍 MUST 允许查看原始堆栈；后续补充 mapping MUST NOT 修改原始事件或破坏已有统计归组。

#### Scenario: 尚未上传 mapping

- **WHEN** Crash 事件对应的 `buildId` 没有可用 mapping
- **THEN** 事件详情展示原始堆栈，并将符号化状态标记为未符号化或等待制品

#### Scenario: 后续补充 mapping

- **WHEN** 项目为既有构建上传了匹配的 mapping
- **THEN** 系统可以补充符号化堆栈和符号化指纹，同时保留原始堆栈、事件 ID 和历史统计
