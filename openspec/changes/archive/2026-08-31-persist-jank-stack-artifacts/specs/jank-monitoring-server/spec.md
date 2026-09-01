## MODIFIED Requirements

### Requirement: 接收三类卡顿监控事件
系统 MUST 通过 `POST /ingest/v1/stack-artifacts:parse` 接收 `schemaVersion=2 / artifactType=RHEA_JANK` 的卡顿个例压缩包，并在现有批量上报入口继续支持 `eventType=frame_scene_summary` 和 `eventType=foreground_suspension_summary`。批量入口 MUST 不再接受 `eventType=jank` 的结构化 JSON 载荷；不匹配、缺失或同时携带多个专用载荷的批量事件 MUST 继续作为不可重试的事件级错误拒绝。

#### Scenario: 分别接受卡顿个例和指标事件
- **WHEN** 客户端通过压缩包入口上传合法卡顿个例，并通过批量入口上传合法的场景帧指标汇总和前台挂起汇总
- **THEN** 系统分别完成卡顿个例落库和指标事件部分接受处理，三类数据均进入现有查询与统计链路

#### Scenario: 拒绝批量 JSON 卡顿个例
- **WHEN** 批量上报事件声明 `eventType=jank`
- **THEN** 系统拒绝该事件并返回稳定、不可重试的卡顿传输方式错误，提示改用卡顿压缩包入口

#### Scenario: 拒绝指标载荷类型不匹配
- **WHEN** 帧指标或前台挂起事件缺少对应载荷，或携带其他类型的专用载荷
- **THEN** 系统拒绝该事件并返回稳定、不可重试的载荷类型错误

### Requirement: 校验卡顿个例的精确时间和采样证据
卡顿个例压缩包 MUST 包含正数的主线程消息总耗时、卡顿阈值、场景和最小采样间隔，并 MUST 能解析出 `processId` 对应主线程的至少一个有效堆栈 segment。采样时间 MUST 表示相对该次消息开始的单调时钟偏移并落在消息区间内；服务端 MUST NOT 将跨设备单调时钟值解释为墙上时间。客户端 MUST 按当前 processor 契约提供 `attemptedSampleCount`，但服务端 MUST 忽略该值；实际采样数量 MUST 由服务端解析主线程证据得到。

#### Scenario: 接受带主线程证据的卡顿个例
- **WHEN** 卡顿压缩包包含精确消息边界、合法主线程采样证据和完整文件校验信息
- **THEN** 系统保留精确总耗时和解析证据，派生采样数量，并将该事件纳入卡顿统计和 Issue 归组

#### Scenario: 拒绝消息区间外或无效的采样证据
- **WHEN** 解析结果的主线程采样偏移不在消息区间内、堆栈为空或无法识别目标主线程
- **THEN** 系统拒绝该事件并返回不可重试的卡顿证据错误

#### Scenario: 服务端忽略客户端尝试采样数
- **WHEN** processor 成功解析包含 `attemptedSampleCount` 的卡顿 manifest
- **THEN** 服务端不读取或保存该值，并完全根据主线程 segments 派生正式采样质量数据

### Requirement: 区分精确卡顿耗时与估算堆栈耗时
系统 MUST 将主线程消息总耗时标记为精确测量值，将基于解析 segments 和采样间隔推导的调用树总耗时与未归属耗时标记为估算值。系统 MUST 保存服务端派生的 `parsedSampleCount`、`expectedSampleCount` 和 `missingSampleCount` 以及算法版本、采样间隔和覆盖范围，且 MUST NOT 把这些估算字段命名或解释为精确方法耗时、CPU 自耗时或客户端确认的丢弃次数。

#### Scenario: 查询卡顿事件耗时证据
- **WHEN** 已授权用户查询一个由卡顿压缩包生成的事件
- **THEN** 响应分别返回精确消息总耗时、明确标记为估算的调用树耗时，以及服务端派生的采样数量和覆盖质量

#### Scenario: 采样存在空洞
- **WHEN** 相邻样本间隔超过算法允许的连续覆盖上限，或理论采样数量大于解析数量
- **THEN** 系统保留未覆盖区间和 `missingSampleCount`，不使用卡顿总时长、报告总记录数或客户端字段填满该空洞

### Requirement: 卡顿事件幂等和部分接受
系统 MUST 以 `projectId + eventId` 识别卡顿个例压缩包和其他卡顿指标事件的重试，并保证同一逻辑事件不会重复计入明细、Issue、FPS 或挂起率统计。单个卡顿压缩包 MUST 返回 `accepted` 或 `duplicate` 的最小成功结果；指标批次中的单条永久错误 MUST NOT 阻止其他合法事件写入；临时存储故障 MUST 使用现有可重试响应语义。

#### Scenario: 重复上报卡顿压缩包
- **WHEN** 客户端使用同一 `eventId` 重试已经接受的卡顿压缩包
- **THEN** 系统返回 `duplicate` 成功状态，且卡顿次数、Issue 次数和受影响设备数不增加

#### Scenario: 指标批次部分合法
- **WHEN** 同一批次包含合法帧指标事件和字段非法的前台挂起事件
- **THEN** 系统写入合法事件、拒绝非法事件，并返回准确的接受数、拒绝数和事件级错误
