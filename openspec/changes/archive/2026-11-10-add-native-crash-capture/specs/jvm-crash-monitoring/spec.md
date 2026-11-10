# 共享 Crash 批次与诊断调整

## MODIFIED Requirements

### Requirement: 接收 JVM 致命崩溃事件
系统 MUST 在 JSON v2 批量上报协议中支持 `eventType=crash` 的 Java/Kotlin JVM 崩溃事件。JVM 事件 MUST 使用 `schemaVersion=2`，声明 `crash.kind=jvm` 和 `crash.fatal=true`，并包含 `eventId`、`occurredAt`、`sessionId`、`packageName`、应用版本、`versionCode`、`buildId`、环境和设备信息。公共事件信封 MUST NOT 再使用 `appId` 表示 Android 包名。共享批次 MUST 同时接受符合 Native 规范的 `kind=native` fatal 事件，按 kind 分别校验载荷，不得强迫 Native 使用 JVM 异常链。

#### Scenario: 接受合法 JVM Crash
- **WHEN** 批次包含完整公共事件信封、与 App Key 绑定值一致的 `packageName`、JVM 崩溃类型、异常链和堆栈帧
- **THEN** 系统接受该事件并返回批次级的接受数量

#### Scenario: 拒绝暂不支持的 Crash 类型
- **WHEN** 事件的 `crash.kind` 不属于 jvm/native，或 `crash.fatal` 为 false
- **THEN** 系统按永久错误拒绝该事件，不要求客户端重试，并返回稳定错误类别

#### Scenario: 拒绝 JSON v1 或旧包名字段
- **WHEN** 批次事件仍使用 schemaVersion=1，或使用 appId 而不是 packageName 表示包名
- **THEN** 系统返回不可重试的版本或字段契约错误，不进入事件级部分接受和存储流程

#### Scenario: 合法 Native 不走 JVM 校验
- **WHEN** 批次包含没有 throwableChain、但 Native 载荷合法的 fatal 事件
- **THEN** 接受该事件，不返回不支持类型或缺少 JVM 异常链错误，原有 JVM 校验继续成立

### Requirement: JVM Crash 携带有界诊断上下文
系统 MUST 在现有 JSON v2 的 `crash.diagnostics` 接收诊断上下文，覆盖崩溃线程、其他 JVM 线程和堆栈、当前进程可读的 logcat、内存快照、文件描述符、进程与设备环境。共享诊断 MUST 可附属于 JVM 或 Native fatal Crash，不成为新的事件类型或统计事件；Native 专属线程与身份规则由 Native 规范限定。诊断缺失 MUST 不影响满足对应核心契约的 Crash 接收；`app_start` MUST 不携带诊断。

每个诊断分区 MUST 区分成功、部分成功、不可用和跳过，使用安全原因类别表示超时、权限限制、资源不足及读取失败，并标记截断；成功得到的空结果 MUST 与采集失败区分。快照 MUST 保留原采集时间，跨启动补传 MUST NOT 重新采集现场或修改原事件身份。JVM 已有线程身份、大小及隐私规则 MUST 保持；Native 扩展不得扩大共享诊断总预算。

#### Scenario: 接收完整诊断
- **WHEN** 合法 Crash 携带符合大小、字段和身份约束的全部诊断分区
- **THEN** 主异常和诊断作为同一事件接收，accepted 数和 Crash 统计只增加一个逻辑事件

#### Scenario: 只有核心异常
- **WHEN** 合法 Crash 没有 diagnostics，或因资源不足仅保留核心异常
- **THEN** 继续接收主异常，详情明确表示诊断未采集，不伪造空快照

#### Scenario: 部分日志缓冲区不可读
- **WHEN** 某个 logcat 缓冲区因权限或系统限制无法读取，其他分区成功
- **THEN** 保留已采集内容和各分区真实状态，不将不可读内容表示为成功的空日志

#### Scenario: 新进程补传旧事件
- **WHEN** 新进程恢复上一次 Crash 并上传或重试
- **THEN** 保留原 eventId、发生时间、sessionId、UUID processId、数值 PID、线程身份、采集时间和原诊断内容

#### Scenario: Native 复用共享诊断
- **WHEN** Native fatal 事件带日志、内存、FD、进程、设备和 Java 线程快照
- **THEN** 按共享分区状态、隐私和总大小规则接收，不要求事件伪装成 JVM
