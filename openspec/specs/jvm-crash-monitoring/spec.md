# JVM Crash 监控规范

## Purpose

为 Android 应用提供 Java/Kotlin 致命崩溃的可靠收集、统一统计和可下钻堆栈展示能力，使团队能够按版本和运行环境识别崩溃回归及其影响范围。

## Requirements

### Requirement: 接收 JVM 致命崩溃事件

系统 MUST 在 JSON v2 批量上报协议中支持 `eventType=crash` 的 Java/Kotlin JVM 崩溃事件。事件 MUST 使用 `schemaVersion=2`，声明 `crash.kind=jvm` 和 `crash.fatal=true`，并包含 `eventId`、`occurredAt`、`sessionId`、`packageName`、应用版本、`versionCode`、`buildId`、环境和设备信息。公共事件信封 MUST NOT 再使用 `appId` 表示 Android 包名。

#### Scenario: 接受合法 JVM Crash

- **WHEN** 批次包含完整公共事件信封、与 App Key 绑定值一致的 `packageName`、JVM 崩溃类型、异常链和堆栈帧
- **THEN** 系统接受该事件并返回批次级的接受数量

#### Scenario: 拒绝暂不支持的 Crash 类型

- **WHEN** 事件的 `crash.kind` 为 `native`，或 `crash.fatal` 为 `false`
- **THEN** 系统按永久错误拒绝该事件，不要求客户端重试，并返回稳定错误类别

#### Scenario: 拒绝 JSON v1 或旧包名字段

- **WHEN** 批次事件仍使用 `schemaVersion=1`，或使用 `appId` 而不是 `packageName` 表示包名
- **THEN** 系统返回不可重试的版本或字段契约错误，不进入事件级部分接受和存储流程

### Requirement: Crash 事件可靠收集

Android Crash SDK MUST 在进程终止前将事件写入本地持久队列；网络不可用或服务端返回可重试错误时，SDK MUST 保留事件并使用原始 `eventId` 重新上报。

#### Scenario: 崩溃发生时网络不可用

- **WHEN** Crash Handler 已生成事件但无法访问上报服务
- **THEN** 事件保留在本地队列，并在后续启动或网络恢复后重新上报

#### Scenario: 服务端暂时不可用后重试

- **WHEN** 批量上报因服务端临时故障失败
- **THEN** 客户端按照重试语义保留事件，重试请求继续使用原始 `eventId`

### Requirement: 校验、脱敏和大小保护

系统 MUST 校验事件时间、会话标识、`packageName`、版本和构建标识、异常链结构、堆栈帧数量及事件大小。异常消息、堆栈内容和设备标识 MUST 遵守应用隐私规则，完整事件 MUST NOT 写入应用日志或服务端日志。身份级包名校验 MUST 在事件级部分接受前完成。

#### Scenario: 缺少统计必需字段

- **WHEN** Crash 事件缺少 `schemaVersion=2`、`sessionId`、`eventId`、`occurredAt` 或非空 `packageName`
- **THEN** 系统拒绝请求并返回不可重试的字段或包名契约错误

#### Scenario: 堆栈或消息超过限制

- **WHEN** 异常消息、堆栈帧或解压后的单事件超过配置上限
- **THEN** 系统拒绝该事件并返回大小错误，不将超限内容写入分析存储

#### Scenario: 异常消息包含敏感内容

- **WHEN** 异常消息或标识字段包含可识别用户信息、路径参数或其他敏感值
- **THEN** 系统在存储和展示前进行脱敏或截断，并禁止在日志中输出原始值

### Requirement: 服务端指纹归组和重复处理
系统 MUST 基于系统生成的 appId、绑定 packageName、异常类型和规范化堆栈生成版本化 Crash 指纹。未成功还原事件 MUST 使用包含 buildId 的独立临时命名空间；成功还原事件 MUST 使用基于还原信息的正式指纹，不把 buildId、应用版本或符号表版本纳入正式归组身份。相同问题的行号或动态消息变化 MUST NOT 导致不必要的拆组；相同 appId + eventId 的重试和补处理 MUST NOT 重复计入统计。首次成功后正式指纹 MUST 固定。

#### Scenario: 相同行为的堆栈归为同一问题
- **WHEN** 同一应用两个已还原事件的异常类型和规范化主要应用调用信息相同，仅行号或动态消息不同
- **THEN** 归入同一正式问题，保留各事件的当前堆栈与来源

#### Scenario: 不同构建临时隔离
- **WHEN** 两个 buildId 的未还原事件碰巧有相同混淆类名和原始指纹
- **THEN** 仍属于不同临时问题，页面可区分构建及临时状态

#### Scenario: 跨混淆版本正式合并
- **WHEN** 不同构建的原始混淆名称不同，但用各自 mapping 还原后得到相同规范化问题特征
- **THEN** 归入相同正式问题，并保留应用版本与构建作为筛选维度

#### Scenario: 补还原调整问题归属
- **WHEN** 临时事件成功还原
- **THEN** 从原临时问题转入正式问题，临时问题可以消失、拆分或合并，事件身份不变

#### Scenario: 重复上报不放大统计
- **WHEN** 同一 appId + eventId 因重试、后台重启或结果重放被多次处理
- **THEN** 只计一个逻辑事件，崩溃次数、会话数和影响设备数不增加，已成功堆栈与指纹不回退

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
系统 MUST 继续提供总览、趋势、问题排行、问题事件列表及单事件详情，保持应用授权、时间范围、返回数量、执行预算与既有版本、渠道、环境、系统、设备和指纹筛选。问题及事件摘要 MUST 返回临时/正式归组类别，临时问题 MUST 标识其 buildId。详情 MUST 只按当前契约返回单一 stack、格式、持久化处理状态、安全原因及实际处理来源，不因没有 mapping 拒绝查看。系统 MUST NOT 返回 rawCrash、symbolicatedStackText 等旧详情字段别名，或在新结构缺少事件时读取旧表兜底。

查询 MUST 使用绑定应用、时间和筛选条件的版本化游标；无效游标返回 INVALID_CURSOR。固定数据集分页 MUST 不重不漏；新增、迟到事件及后台归组变化下 MUST 明示非快照语义，预算失败不得作为部分成功结果。事件详情 MUST 始终通过 eventId 定位；旧临时指纹不保证一对一跳转到正式问题。

#### Scenario: 查看 Crash 问题排行
- **WHEN** 授权用户查询问题排行
- **THEN** 返回当前指纹、归组类别、异常类型、事件数、崩溃会话数、影响设备数及首次/最近发生时间，临时问题附构建标识

#### Scenario: 从问题下钻到堆栈
- **WHEN** 用户选择问题或事件
- **THEN** 展示对应版本、构建、设备、系统、会话、单份当前堆栈和持久化还原状态

#### Scenario: 访问其他项目的 Crash 详情
- **WHEN** 用户没有目标应用查看权限
- **THEN** 返回与资源不存在一致的响应，不泄露其他应用信息

#### Scenario: 游标无效
- **WHEN** 提交旧版、损坏或其他查询的游标
- **THEN** 返回 INVALID_CURSOR，要求重新查询，不回退第一页

#### Scenario: 查询资源耗尽
- **WHEN** 查询超过执行时间、扫描或内存上限
- **THEN** 返回明确错误，不返回部分聚合成功

#### Scenario: 临时问题已迁移
- **WHEN** 原临时问题的全部事件已转入其他问题，用户打开旧指纹链接
- **THEN** 返回空事件列表并可重新查询问题，不伪造唯一跳转，已知 eventId 的详情仍可正常打开

#### Scenario: 当前详情没有旧字段别名
- **WHEN** 授权用户获取当前模型事件详情
- **THEN** 只返回当前 stack 契约；仅存在旧存储中的事件不被按需转换或作为兼容详情返回

### Requirement: Crash 有界查询保持精确口径
系统 MUST 在数据库侧限定应用、发生时间和维度，选取每个 eventId 的最新逻辑处理状态后完成精确去重、归组及分页；概览、趋势、列表 MUST NOT 读取完整堆栈。Crash 与 app_start 分母 MUST 来自同一当前模型数据集，不能混入旧事实或旧小时聚合。使用指纹筛选时 MUST 保留符合其他维度的 app_start 分母。补处理 MUST 仅改变原发生日期下的问题归属，不增加总事件数、影响设备或崩溃会话。详情 MUST 只读持久化结果，不执行实时符号化。

#### Scenario: 小时间窗查询
- **WHEN** 应用有大量历史数据但只查询最近一小时
- **THEN** 只在需要的时间及维度范围内计算，不在 JVM 加载应用全部历史事件

#### Scenario: 精确统计对照
- **WHEN** 数据包含重复上报、跨桶会话、多个版本、临时问题及还原后的新状态版本
- **THEN** 所有查询符合精确契约，不能把桶内去重数相加代替整体去重，不能把旧状态当成额外事件

#### Scenario: 仅查询列表
- **WHEN** 查询问题或事件摘要
- **THEN** 仅读取轻量字段并返回有限页，不读取完整堆栈，不截断事实计算排行

#### Scenario: 跨日补处理
- **WHEN** 昨天发生的 Crash 今天完成还原
- **THEN** 昨天的问题分布更新，两天的总量和会话统计不因还原新增事件

### Requirement: Grafana Crash Dashboard

系统 MUST 提供首期 Grafana Crash Dashboard，至少包含崩溃总览、时间趋势、问题排行、版本对比和堆栈下钻入口，并复用 `appId`、包名、版本、渠道、环境、Android 版本、设备型号和时间范围筛选。

#### Scenario: 查看有数据的 Crash Dashboard

- **WHEN** 用户选择应用和时间范围，且范围内存在 Crash 与 `app_start` 数据
- **THEN** Dashboard 展示事件数、崩溃率、受影响设备数、问题排行和可用的下钻链接

#### Scenario: 查看无数据或分母不足的 Dashboard

- **WHEN** 选择的范围没有 Crash 数据或没有有效 `app_start` 分母
- **THEN** Dashboard 明确显示无数据或分母不足，不将其误显示为零崩溃率

### Requirement: 原始堆栈和符号化状态可追溯
系统 MUST 按后台流程持久化一份当前堆栈及 buildId。pending/failed 保留脱敏原始异常链；symbolicated 仅保留完整还原输出及实际符号表身份、版本或摘要、处理时间和算法版本。详情 MUST NOT 执行 Retrace，也不能把当前注册 mapping 的版本冒充历史事件的处理来源。成功后的堆栈和正式指纹 MUST 固定；缺少 mapping 或失败仍可查看原始材料及原因。内联和多候选 MUST 完整保留，未变化输出不得直接认定失败，成功处理不得被解释为每帧均正确还原。显式创建的分析任务只冻结当时已发布材料，不要求 APK 到源码登记。

#### Scenario: 尚未上传 mapping
- **WHEN** 查看没有 mapping 的事件
- **THEN** 返回已存原始异常链、pending 状态和等待原因，不触发请求内还原

#### Scenario: 后续补充 mapping
- **WHEN** mapping 已上传但后台尚未完成
- **THEN** 详情仍返回 pending 或 failed 的当前材料；后台成功发布后再返回固定还原输出和正式指纹

#### Scenario: 每次查询都还原
- **WHEN** 连续查询同一已成功事件
- **THEN** 返回相同堆栈和来源，不调用 R8，不要求符号文件仍存在

#### Scenario: 替换 mapping
- **WHEN** 管理员替换或删除后重新上传 mapping
- **THEN** 已成功事件不变，尚未成功及新事件由后台使用后续实际取得的 mapping 处理

#### Scenario: 还原服务繁忙或失败
- **WHEN** 后台许可不足、文件不可读、输出超限或处理失败
- **THEN** 保留原始材料和安全原因，详情不因此变成事件不存在，临时归组继续可见

#### Scenario: 分析证据与日常查询分开
- **WHEN** 单事件任务冻结当前材料后事件完成后台还原或 mapping 发生变化
- **THEN** 原任务证据不变，后续新任务可冻结新的当前结果，不改写旧证据

#### Scenario: 缺少 APK 源码登记
- **WHEN** 事件有可用当前堆栈但没有源码登记或 mapping
- **THEN** 允许创建当前代码分析任务并记录局限，不声称工作区与历史 APK 相同

### Requirement: JVM Crash 批次使用应用级凭据并校验包名

系统 MUST 使用请求头 `X-App-Key` 查询应用级上报凭据并确定唯一 `appId` 与绑定 `packageName`。批次中的每个公共事件信封 MUST 使用 `schemaVersion=2` 并包含非空 `packageName`，且所有包名 MUST 与绑定包名完全一致；客户端 MUST NOT 在正文中声明系统生成的 `appId`。系统 MUST NOT 接受 JSON v1、旧 `X-Project-Key`、旧 Key 前缀、全局项目身份或默认包名处理批次。

#### Scenario: 合法 App Key 和匹配包名上报 Crash

- **WHEN** 客户端携带合法 App Key，且批次内所有事件 `packageName` 与该 Key 绑定包名一致
- **THEN** 系统使用 App Key 映射得到的 `appId` 继续执行原有批次校验、部分接受和幂等存储

#### Scenario: App Key 无效

- **WHEN** 客户端未携带 `X-App-Key`、携带不存在的 Key、使用旧前缀或只携带 `X-Project-Key`
- **THEN** 系统在读取批次正文前返回不可重试的 `401 INVALID_APP_KEY`

#### Scenario: 批次包含其他包名

- **WHEN** 合法 App Key 的绑定包名与批次中任意事件 `packageName` 不一致
- **THEN** 系统返回不可重试的 `403 PACKAGE_NAME_MISMATCH` 并拒绝整个批次，不执行原有部分接受逻辑且不写入任何事件

#### Scenario: 应用鉴权数据库不可用

- **WHEN** 服务端无法从应用凭据存储解析 App Key
- **THEN** 系统返回可重试的 `503 APP_AUTH_UNAVAILABLE`，客户端保留原批次和 `eventId` 后重试
