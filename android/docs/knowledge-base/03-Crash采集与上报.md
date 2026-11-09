# Crash 采集与上报


## 2026-09-22 模块化说明

当前 Crash 实现归属 `performance-crash`，共享上下文和网络会话分别来自 `performance-core` 与 `performance-transport`。
[返回导航](README.md)

## 功能与入口

Crash 链路记录应用启动和 JVM 未处理异常，先持久化事件，再尝试上传，以便进程重启后恢复。由 `PerformanceSdk.initialize → componentFactory.startCrash → CrashReporter.start` 启动。

示例应用主页面统一通过“崩溃测试页面”进入 `TestCrashActivity`。点击“1. 主动抛出异常”调用 `CrashTestCases.explicitException`，直接抛出未捕获 `IllegalStateException`，覆盖原首页“崩溃上传测试”的采集、落盘与上传链路；首页不再直接触发崩溃。该页面在 Debug、Release 构建中均存在，仅用于受控设备测试，不代表 native signal、系统 ANR 或非致命异常已经接入。

当前载荷为 `crash.kind=jvm`、`fatal=true`。它不覆盖 native signal、系统 ANR 或业务主动上报的非致命异常。

## 调用链

~~~mermaid
flowchart TD
    Start["Reporter 启动"] --> Recover["恢复中断核心"] --> AppStart["构造 app_start"]
    Handler["默认 UncaughtExceptionHandler"] --> Factory["CrashEventFactory.crash"]
    AppStart --> Queue["FileCrashQueue.enqueue"]
    Factory --> Core["核心原子写入 capturing"] --> Capture["有界采集诊断"] --> Queue
    Queue --> Upload["CrashUploader.flush"]
    Upload --> Net["CrashNetworkClient.sendBatch"]
    Net --> Ack["确认 / 重试 / dead-letter"]
    Handler --> Previous["处理后委托原异常 handler"]
~~~

`CrashReporter.start` 先恢复中断的 capturing，再入队 `app_start`、安装异常 handler，并安排启动上传、周期上传和网络恢复回调。网络恢复监听只在 API 24+ 注册；低版本仍可依赖启动和周期任务。

异常处理通过原子标志避免重复进入采集。先冻结异常线程与系统 TID、数值 PID、原事件身份和异常链并原子保存核心；预建单工作线程采集辅助现场，处理器最多等待 1500ms，logcat 三个缓冲区共享最多 500ms。结果冻结到 events 后，同步尝试最多一批、`withTimeout(3500ms)` 约束的上传，随后委托原 handler；没有原 handler 或委托失败则退出进程。若已有 flush 正在执行，本次同步 flush 跳过，落盘事件等待后续处理。等待上限不是对 ART 栈采集、文件系统或进程终止的硬中断保证；迟到快照不能修改已冻结事件。

启用 Crash 时默认采集其他 JVM 线程、当前进程 logcat、内存、FD、进程和设备上下文，没有单独的诊断开关。线程按对象排除崩溃线程、优先保留主线程，Java ID 为字符串且不等同系统 TID；这些是崩溃处理期间的近似快照。每个分区独立表达成功、部分、不可用和跳过；日志权限、线程数、ROM 与内存压力会影响结果。进程真实启动的可靠 Unix 时间当前无法从公开单调时间准确推导，`startedAt` 保持缺失，不与 SDK 初始化时间混淆。

## 事件与堆栈

`CrashEventFactory` 为事件生成 eventId，复用 SDK 进程级 `sessionId`、`processId` 和匿名设备 ID。
`processId` 是规范小写 UUID v4，进程内的启动事件、异常事件和重试保持不变。`ThrowableMapper` 沿
cause 链读取，使用对象身份防止循环，最多 16 层异常、总计 200 帧；无堆栈时构造合成帧。

消息经过 `CrashSanitizer` 处理和截断，applicationFrame 根据应用包名前缀判断。这里的脱敏是规则处理，不能解释为任意业务字符串都已不可识别。

## 持久化与上传

队列位于应用 `noBackupFilesDir/performance-crash-reporter`：

~~~text
anonymous-device-id       共享匿名设备 ID
capturing/               未完成诊断采集的核心异常，上传器不可见
events/                  每个 eventId 一份 JSON，含重试状态
dead-letter/             永久拒绝、损坏或过期记录
~~~

`AtomicFileWriter` 写临时文件、刷新并同步，再重命名。`FileCrashQueue` 用同步方法保护同实例访问；这不是已验证的跨进程锁。

启动恢复保留原 eventId、会话、进程身份、发生时间、崩溃线程和核心裁剪标记；未完成分区标记 interrupted，不重新采集新进程现场。冻结前按 UTF-8 JSON 核对单事件 240 KiB 目标、128 KiB 诊断总额、独立分区限制和配置的实际批次大小；裁剪主异常时记录 `coreTruncation`。明细预算与协议由 [Crash API](../../../docs/api/crash-api.md#批量上报)维护。

`CrashUploader.selectBatch` 同时限制事件数和完整 UTF-8 JSON 字节数。单事件超限直接隔离。每次 flush 最多处理 5 批，开始时把超过 7 天的事件转入 dead-letter；dead-letter 默认最多保留 100 个文件，当前正常队列没有字节配额。

响应按 errors 的 index/eventId 定位：未列入错误的事件确认删除，可重试事件保留 ID 并更新下次时间，永久错误转入 dead-letter。无效错误定位触发整批重试。

HTTP 错误优先参考错误体 `retryable`，其次采用网络层分类；退避从 30 秒指数增长至最多 1 小时，也支持响应中的重试秒数。具体差异见 [队列对比](08-网络通信与持久化队列.md)。

## 当前边界与调试

当前客户端协议已按所核对服务端契约对齐：默认 schema v2，事件发送 `packageName` 和 UUID v4
`processId`，不再发送旧的 `appId`；服务端要求的 JVM Crash 字段和批次响应校验已同步到代码与测试，
见 [协议说明](09-服务端对接与数据协议.md)。

调试时先通过启动事件验证本地落盘和请求，再从主页面进入“崩溃测试页面”。受控现场可先准备合成线程、日志和文件，再触发主线程或后台线程异常；不要把被 `runCatching` 吞掉的异常作为未处理崩溃测试。检查 schema v2、`packageName`、UUID v4 `processId`、事件确认及诊断分区，实际设备与服务端范围见[独立验收](../../../docs/validation/crash-diagnostics/README.md)。

## 源码与验证依据

- [CrashReporter](../../performance-crash/src/main/java/com/shanshui/performance/crash/CrashReporter.kt)：start、captureAndFlush、flushSynchronously、close。
- [CrashEventFactory / ThrowableMapper](../../performance-crash/src/main/java/com/shanshui/performance/crash/CrashEventFactory.kt)、[FileCrashQueue](../../performance-crash/src/main/java/com/shanshui/performance/crash/FileCrashQueue.kt)、[CrashUploader](../../performance-crash/src/main/java/com/shanshui/performance/crash/CrashUploader.kt)。
- [CrashEventFactoryTest](../../performance-crash/src/test/java/com/shanshui/performance/crash/CrashEventFactoryTest.kt)：脱敏、链长/帧数限制与启动元数据。
- [FileCrashQueueTest](../../performance-crash/src/test/java/com/shanshui/performance/crash/FileCrashQueueTest.kt)：重试状态和 dead-letter。
- [CrashUploaderTest](../../performance-crash/src/test/java/com/shanshui/performance/crash/CrashUploaderTest.kt)：部分成功、原 eventId 重试和异常定位。
- [RuntimeIdentityTest](../../performance-core/src/test/java/com/shanshui/performance/identity/RuntimeIdentityTest.kt)：主/子进程身份规则和 UUID v4 校验。

2026-09-13 修复前在 Pixel 4 XL / Android 13（API 33）上安装 Debug APK，点击主页面“崩溃上传测试”按钮，确认 `AndroidRuntime` 收到 `IllegalStateException: Crash upload test triggered from MainActivity`，`CrashReporter` 发起请求并收到 HTTP 400，随后将包含该 Crash 事件的记录移入 `dead-letter`。该结果仅证明旧版按钮、未捕获异常、事件生成、持久化和上传尝试链路已执行；协议修复后的真实设备和服务端接收结果尚未重新验证。

## 独立页面的崩溃样本（2026-10-01）

[崩溃测试页](../../app/src/main/java/com/shanshui/performance/TestCrashActivity.kt) 调用 [CrashTestCases](../../app/src/main/java/com/shanshui/performance/CrashTestCases.kt) 制造五类真实未捕获 JVM 异常。前三类和状态异常由主线程触发，外部响应异常由命名后台线程触发；不自行捕获或替换 SDK 默认异常处理器，不裁剪异常堆栈，不增加事件字段。后两项人工模拟输入不写入错误消息或日志，输入的真实取值需要人工另行记录；完整堆栈仍能解释失败分支，但不能据此确定实际输入与上游业务原因。操作见 [示例应用](10-示例应用与调试路径.md)。
