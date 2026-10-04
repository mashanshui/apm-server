## MODIFIED Requirements

### Requirement: 原始堆栈和符号化状态可追溯

系统 MUST 保留原始堆栈和 `buildId`，Crash 入库 MUST NOT 执行符号化。每次授权详情请求 MUST 按 `appId + buildId` 获取当前注册 mapping 并实时还原，返回原始异常链、可选完整还原文本、本次符号化状态、原因及使用的符号表版本。日常查询 MUST NOT 持久化或跨请求缓存还原结果；仅显式创建的单事件分析任务 MUST 允许保存带证据 ID、摘要及实际符号表版本的不可变证据，此证据 MUST NOT 作为日常查询缓存。当前代码分析 MUST NOT 要求 APK 到源码提交的登记；mapping 缺失或还原失败 MUST 作为证据局限，不得因此拒绝使用原始堆栈分析。系统 MUST NOT 修改事件 ID、原始指纹、Issue 归组或统计。缺少 mapping、存储不可用、解析繁忙或解析失败时 MUST 仍可查看原始详情，并明确原因。未变化的输出不得直接认定失败，也不得声称所有帧均已还原；内联和多候选输出 MUST 完整保留。

#### Scenario: 尚未上传 mapping
- **WHEN** 当前应用和 buildId 没有可用 mapping
- **THEN** 详情返回原始堆栈、raw_only 状态和 mapping_missing 原因

#### Scenario: 后续补充 mapping
- **WHEN** 管理员补传对应 mapping 后再次查看已有 Crash
- **THEN** 本次请求实时返回还原文本，原始事件和所有数据库还原字段均不更新

#### Scenario: 每次查询都还原
- **WHEN** 有可用 mapping 的同一事件被连续查询两次
- **THEN** 每次都执行本次还原，不从已存或跨请求缓存的还原结果返回

#### Scenario: 替换 mapping
- **WHEN** 管理员替换成功后发起新的详情请求
- **THEN** 使用新版本重新还原并返回其版本，旧事件无需回填

#### Scenario: 还原服务繁忙或失败
- **WHEN** 还原许可不足、符号文件不可读或处理失败
- **THEN** 返回原始详情和本次失败原因，不将错误作为事件不存在，也不改变聚合结果

#### Scenario: 分析证据与日常查询分开
- **WHEN** 当前代码任务冻结单次还原证据后 mapping 被替换
- **THEN** 原任务继续使用原证据，日常详情使用本次还原结果，原始 buildId 仅用于既有符号链路

#### Scenario: 缺少 APK 源码登记
- **WHEN** 事件有原始堆栈但没有源码提交登记或 mapping
- **THEN** 可创建当前代码任务并保存实际符号局限，不声称当前工作区属于该 APK
