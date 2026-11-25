# Android ANR 采集增量规格

## MODIFIED Requirements

### Requirement: 客户端保存 Matrix 回调结果

SDK MUST 将 Matrix 已发生的 ANR 和 trace 回调结果保存至应用私有目录，保留原来源、堆栈、消息、时间差、cgroup 和确认方式。外部信号的 ANR 判定结果与 trace MUST 使用同次采集身份，归档内容 MUST 对应实际截取，不得引用可能被下一次捕获覆盖的共享活动内容。采集、保存和读取 MUST 可离线执行，手动 trace MUST 与 ANR 结果区分。

#### Scenario: Matrix 发出 ANR 回调

- **WHEN** Matrix 原逻辑发出 ANR 回调且本地写入成功
- **THEN** 宿主可读取本次判定结果与原事件身份，并收到保存完成回调。可用 trace 只有在归属明确时才属于该事件

#### Scenario: 只有手动 trace

- **WHEN** Matrix 完成手动 trace 且没有发出 ANR 回调
- **THEN** 宿主可读取手动 trace，且该结果不生成 ANR 事件

#### Scenario: 连续捕获与异步归档

- **WHEN** 两次外部信号捕获相邻，判定与 trace 回调先后交错，或前一次归档被滞留
- **THEN** 每个已关联 trace 对应本次捕获，归属不明的内容标为不可用，后一次捕获不覆盖前一次待归档内容

## ADDED Requirements

### Requirement: 仅上传已经判定的 Signal ANR

SDK MUST 在显式启用上传且 Matrix 外部 SIGQUIT 路径发出 `SIGNAL_ANR` 后才生成 `anr` 事件。消息超时与系统错误状态两条原判定路径 MUST 均可上传，且保留各自来源。只有信号、单独 trace、debugger、手动 trace 和解析回调 MUST NOT 生成上传事件。截取范围仍为首个命中写入的系统 trace 片段。

#### Scenario: 消息超时或系统状态触发回调

- **WHEN** 外部 SIGQUIT 经过现有判定，以任一合法来源产生 `SIGNAL_ANR`
- **THEN** SDK 为该报告生成一个稳定事件 ID，记录实际判定来源，并附上可以关联到同次采集的 trace 片段

#### Scenario: 没有 Matrix ANR 回调

- **WHEN** 外部信号后未满足原有判定条件，或仅有 trace、debugger、锁解析或手动报告
- **THEN** 已保存的本地报告可按现有方式读取，服务端没有独立 ANR 事件

#### Scenario: 本地采集和真实系统输入 ANR

- **WHEN** 示例分别触发手动 trace 和受控真实系统输入 ANR，并检查系统记录和本地结果
- **THEN** 宿主可分别识别手动报告和系统输入 ANR 对应回调，仅符合上传条件的 ANR 进入待上传状态

### Requirement: 采集 ANR_INFO 与通用参考现场

SDK MUST 仅使用本 UID、本 PID、状态为 `NOT_RESPONDING` 且能归属本次事件的 `ProcessErrorStateInfo.longMsg` 生成 `ANR_INFO`。原错误状态判定命中时 MUST 从匹配记录取值，消息超时分支 MUST 在原进程后台有界补查。SDK MUST 保留主线程 Java 栈，后台限时采集和记录内存、Logcat、FD、进程、设备分区的状态、截断和采集时间。首版 MUST NOT 再采集全量 Java 线程快照。

#### Scenario: 系统错误状态直接命中

- **WHEN** 原判定使用本进程匹配的 `NOT_RESPONDING` 记录，且其 `longMsg` 有内容
- **THEN** `ANR_INFO` 保存该记录经脱敏和限长的文本，并保留系统错误状态判定来源

#### Scenario: 先消息超时后出现系统说明

- **WHEN** Matrix 先按消息超时发出回调，随后在同次采集的补查窗口内找到本进程匹配的错误记录
- **THEN** 原报告及时落盘，在上传冻结前补充 `ANR_INFO`，判定来源仍为消息超时

#### Scenario: 未获取诊断数据

- **WHEN** 系统说明为空、仅返回其他进程记录、无法区分相邻捕获，或辅助分区无权限、超时或失败
- **THEN** SDK 保留核心 ANR，用相应的缺失或部分状态记录对应分区，其他已有现场正常上传

### Requirement: 冻结原现场并可靠重试

SDK MUST 先持久化核心事件，在同次采集的有界窗口内冻结原身份与诊断后才可上传。进程终止后 MUST 使用已有持久材料恢复，未完成分区记录采集中断。应用、原事件 ID、时间、身份和载荷 MUST 在重试中保持稳定。冻结前 MUST 按未压缩 JSON 字节裁剪和脱敏，并保留截断状态。

#### Scenario: 进程在诊断采集中退出

- **WHEN** 核心事件已提交，但原进程在补充 trace 或诊断完成前退出
- **THEN** 下次启动恢复原事件和已提交的诊断，缺失现场标为中断，不用新进程数据替代原现场

#### Scenario: 断网、服务端暂时失败或确认格式错误

- **WHEN** 已冻结事件发送失败、收到重试响应或收到无法匹配当前请求的业务确认
- **THEN** SDK 保留事件，按公共退避规则重试原载荷，直至得到有效接受、重复或永久拒绝状态

#### Scenario: 关闭上传或保有历史本地报告

- **WHEN** 宿主只启用本地采集、关闭上传、或首次启用上传时已经有纯本地历史报告
- **THEN** 本地报告读写可用，关闭期间不发送请求，未登记为待上传的历史本地报告不回灌

## REMOVED Requirements

### Requirement: 本期 ANR 不上传

**Reason**: 独立 ANR 上传、接收和 Web 查询已纳入本次能力。本地接入模式及手动和真实系统 ANR 验收由新增要求覆盖。

**Migration**: 本地接入沿用现有方式。需要上报的宿主显式启用 ANR 上传并提供传输配置，启用后产生的合格事件进入待上传流程。
