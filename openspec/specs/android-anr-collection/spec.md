# android-anr-collection Specification

## Purpose

将指定版本 Matrix 的 Signal ANR 监控和数据采集行为接入 Android SDK，在保留其信号处理、判定、trace 拦截与解析口径的同时，使用 ByteHook 代替 xHook，并提供独立的客户端本地报告能力，供后续服务端对接使用。

## Requirements

### Requirement: 显式启用并尊重平台条件
SDK MUST 默认关闭 ANR 采集；首期在主进程、API 23+、arm64-v8a 提供采集。平台不支持时 MUST 返回原因，不得伪称已启动；关闭或不支持采集时已保存报告仍可读取，不要求加载 ANR JNI。

#### Scenario: 默认关闭与不支持平台
- **WHEN** 使用默认配置，或在非主进程、API 21/22、非 arm64 平台请求启动
- **THEN** 不启动 ANR 信号采集；状态分别说明关闭或不支持，历史报告仍可读

### Requirement: 按 Matrix 处理 SIGQUIT
SDK MUST 迁移指定 Matrix 版本的信号安装、来源判断、工作线程、Signal Catcher 查找和 SIGQUIT 转交顺序。外部 SIGQUIT 路径 MUST 先调用 Java 采集并按原超时时间等待，再按原逻辑准备 trace hook 和转交；自行触发的手动 trace MUST 走原手动路径。

#### Scenario: 外部 SIGQUIT
- **WHEN** SignalAnrTracer 收到外部 SIGQUIT
- **THEN** 按 Matrix 的先 Java 采集、再 trace hook、再转交顺序处理，而不是提前转交或引入额外判定

#### Scenario: 手动 trace
- **WHEN** 应用调用手动打印 trace
- **THEN** 按 Matrix 的自发 SIGQUIT 和打印 trace 路径处理，不冒充外部 ANR 回调

### Requirement: 按 Matrix 处理 debugger 信号
SDK MUST 迁移 Matrix 对 `__SIGRTMIN + 3` 的来源判断、主线程 Java 栈采集、原处理器恢复、信号重新排队、两秒等待和重新安装的顺序。该路径取得的主线程 Java 栈 MUST 不伪称独立 Native unwind 结果。

#### Scenario: 外部 debugger 信号
- **WHEN** 收到 Matrix 判定为外部来源的 debugger 信号
- **THEN** 按原次序采集和转交，报告保留真实信号来源及 Java 栈语义

### Requirement: 保留 Matrix 的 ANR 判定和通知
SDK MUST 以 Matrix 原逻辑反射主线程消息：前台超时超过 2 秒或后台超过 10 秒时立即以 `fromProcessErrorState=false` 通知；否则每 500ms 查询错误状态，最多 40 次，仅满足原进程/UID 判断及 NOT_RESPONDING 时以 `true` 通知。未达到任何条件时 MUST 不伪造 ANR 回调。

#### Scenario: 消息超时
- **WHEN** 消息逾期满足 Matrix 前台或后台阈值
- **THEN** 立即发出原有非系统错误状态来源的 ANR 回调，不额外等待系统状态确认

#### Scenario: 系统错误状态
- **WHEN** 消息条件不满足而后续错误状态匹配 Matrix 原有判断
- **THEN** 以 `fromProcessErrorState=true` 通知，并按原逻辑处理其他进程和 UID

#### Scenario: 未确认
- **WHEN** 消息判断未命中且有限次数内无匹配错误状态
- **THEN** 不构造 Matrix 本来不会发出的 ANR 回调

### Requirement: 保留 Matrix 的两类堆栈及去重
SDK MUST 保留 Signal ANR 与 debugger 分支分别采集主线程 Java 栈的口径，保留消息文本、消息时间差、cgroup 和 `fromProcessErrorState`。debugger 分支 MUST 按 Matrix 的 `lastReportedTimeStamp` 和 20 秒窗口执行原有去重。

#### Scenario: 相邻信号
- **WHEN** debugger 分支在 Matrix 定义的最近一次报告后 20 秒内触发
- **THEN** 按原实现抑制该分支回调，不合成新事件或改变原报告

#### Scenario: 栈来源
- **WHEN** debugger 分支读取主线程 Java 栈
- **THEN** 保留该 Java 栈和原分支类型，不声称得到 Native 符号化栈

### Requirement: 使用 ByteHook 等价替换 Matrix 的 PLT hook
SDK MUST 通过 ByteHook 复现 Matrix 按 API 版本选择的 `connect/open/write` 调用方 SO 和代理次序；MUST 保留目标路径命中后的线程标记、该线程首次命中 write 时的字符串复制、trace 回调和原 write 调用。不得自行添加 recvmsg、输出 FD 关联或多次 write 拼接。

#### Scenario: 不同 API 调用方
- **WHEN** 分别运行 API 23、24/25、26、27/28、29 和 30+ 分支
- **THEN** connect/open/write 的调用方和目标与指定 Matrix 版本一致，使用 ByteHook 替换 xHook

#### Scenario: 首次被 hook 的 write
- **WHEN** 目标路径命中后同一线程调用被拦截的 write
- **THEN** 与 Matrix 一样在原 write 前截取第一次命中的字符串并触发对应 trace 回调，再调用原 write

#### Scenario: 原实现边界
- **WHEN** trace 分为多次写入、数据不以 NUL 结尾或第一次命中的是其他写入
- **THEN** 不将结果标为经本 SDK 补全的完整 trace，保留原实现边界及可验证事实

### Requirement: 保留 Matrix 的死锁与空闲循环解析
SDK MUST 以指定 Matrix 版本的 `SimpleDeadLockDetector` 解析已截取 trace，保留线程匹配、等待关系、图遍历、结束行与 nativePollOnce 分支的原结果，不用新的锁图规则扩大回调范围。

#### Scenario: 对照 Matrix 样本
- **WHEN** 使用同一批锁等待、锁环、无等待主线程、nativePollOnce 和 EOF 样本分别运行原实现和迁移实现
- **THEN** 迁移结果与原实现一致；原实现未识别的结果不能标为已识别

### Requirement: 客户端保存 Matrix 回调结果
SDK MUST 将已发生的 Matrix ANR 和 trace 回调结果保存至应用私有本地目录，保留原有来源、堆栈、消息、时间差、cgroup 和确认方式；手动 trace 与 ANR 结果 MUST 区分。不因没有 Matrix 回调而生成虚假的 ANR 报告。采集、保存和读取 MUST 不要求网络服务。宿主常规接入无需主动触发手动 trace；短间隔连续捕获覆盖活动文件时，归档内容与每次回调的一一对应超出本期保证范围。

#### Scenario: Matrix 发出 ANR 回调
- **WHEN** Matrix 原逻辑发出 ANR 回调、本地写入成功，且归档前活动 trace 未被后一次捕获覆盖
- **THEN** 可读取该次原始结果和当前事件身份，并发出完成回调

#### Scenario: 只有手动 trace
- **WHEN** Matrix 完成手动 trace、没有 ANR 回调，且归档前活动 trace 未被后一次捕获覆盖
- **THEN** 可将其作为手动 trace 读取，不统计为系统确认 ANR

### Requirement: 本地报告可恢复和清理
SDK MUST 提供报告列表、按 ID 读取/删除、重启读取、容量及过期清理。报告索引 MUST 使用原子提交和受限路径，保存失败或采集尚未完成时不得伪称成功；不能用新进程数据填充旧报告。容量清理不得改变 Matrix 当前采集和 trace 截取逻辑。

#### Scenario: 进程重启
- **WHEN** 报告已成功保存后进程退出并重新启动
- **THEN** 可读取原报告与原事件身份，不重新采集新进程现场

#### Scenario: 容量或存储异常
- **WHEN** 保存超出配置容量、文件损坏或磁盘写入失败
- **THEN** 按容量策略处理或返回可识别失败，不冒称报告完整保存，不修改其他功能目录

### Requirement: 完成回调和 SDK 生命周期
SDK MUST 在本地结果保存完成后提供 SDK 自有回调，并隔离回调异常；重复初始化、关闭和重新启动行为 MUST 以迁移后的 Matrix 实际生命周期为依据，不能虚构成功状态。已保存报告不因关闭而删除。

#### Scenario: 回调失败或关闭
- **WHEN** 完成回调抛异常，或宿主关闭 ANR 采集
- **THEN** 已保存的报告仍可读取，实际监控状态如实反映，不影响其他功能

### Requirement: 本期 ANR 不上传
SDK MUST 不将 ANR 自动转换为 Crash/Jank 或其他事件上传；本地列表、读取、清理及完成回调不依赖服务器。示例 MUST 能触发手动 trace 和受控系统 ANR，并检查实际本地结果。

#### Scenario: 离线采集与全量刷新
- **WHEN** 无网络、无有效 App Key 或调用全量 flush
- **THEN** ANR 使用本地结果，不产生 ANR 请求或既有上传队列项

#### Scenario: 手动和系统 ANR 验收
- **WHEN** 通过示例分别触发手动 trace 和系统 ANR
- **THEN** 分别验证对应 Matrix 回调与本地结果，手动信号不替代真实系统 ANR 证据
