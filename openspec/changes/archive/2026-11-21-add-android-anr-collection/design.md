# Android ANR 客户端采集设计

## 背景

用户明确要求严格依据本地 Matrix 实现迁移，不自行改写 ANR 监控和数据采集逻辑。参照版本为 Matrix `3b8293bd65d47eeea7caf1f32a3a5d4d5eab60e7`，以 `SignalAnrTracer.java`、`SignalHandler.*`、`AnrDumper.*` 和 `MatrixTracer.cc` 中 ANR 相关代码为基准。现有项目的 JNI、日志、SDK 生命周期和数据格式需要适配，但不改变 Matrix 的采集判定和处理顺序。需要改进的原有风险只记录，不作为本次自行修改的目标。

Matrix 的 xHook 必须换成用户确认的 ByteHook；本期另有用户确认的本地持久化、重启读取、容量清理和完成回调。上述平台接入与结果保存是迁移所必需的适配，不增加其他信号/trace 检测策略。

## 目标与非目标

- 保留 Matrix 的 SIGQUIT、debugger 信号、来源判断、线程创建、转交、等待、消息判定、系统错误状态检查、trace 选择、死锁和 nativePollOnce 分析行为。
- 使用 ByteHook 1.1.1 复现 xHook 指定调用方 SO 的 PLT 拦截点，保持 Matrix 的目标版本分支和代理执行次序；不添加 recvmsg/SCM_RIGHTS 跟踪、全局 inline hook 或备用采集策略。
- 将 Matrix 回调及 trace 结果接到本项目本地持久化，并在应用重启后读取；不调用服务端、事件上传队列或改变其他端。

## 决策

### 一、模块、构建和依赖

新增 `performance-anr`，依赖 Core 与 ByteHook，不要求 Crash、Metrics、Transport、Native 工具、Rhea、KOOM 或 Matrix 运行时。通过适配器替换 Matrix 单例、Plugin、日志及前后台辅助依赖，同时保留相关方法原判定口径；公共类型不暴露 ByteHook 或 Matrix 类。全量门面和独立接入分别装配，默认不启动 ANR；应用可在关闭采集后读取已保存报告。

首期承接既有方案的主进程、API 23+、arm64-v8a 运行边界；`minSdk 21` 不变，非支持环境可构建并明确降级，不加载 ANR JNI。按用户最新决定恢复 compileSdk 36 和原有 AGP 8.13.2 / Gradle 8.13 / Kotlin 1.9.25，使用 ByteHook 1.1.1 并统一到项目已有 ShadowHook 2.0.0；须重新验证最终依赖图、JNI、SO 打包、真实初始化与独立消费者。

### 二、保留 Matrix 信号处理

按照 `SignalHandler.cc` 和 `AnrDumper.cc` 迁移 SIGQUIT 与 `__SIGRTMIN + 3` 的安装、旧处理器保存、备用栈、来源判断、信号线程、手动打印、Signal Catcher 查找和转交方式。SIGQUIT 外部来源按 Matrix 在工作线程调用 Java `onANRDumped`，等待其结束或超时、安装 trace hook，然后转交；手动信号按 Matrix 的独立路径处理。debugger 路径保留原回调、恢复原处理器、排队转发、等待两秒及重新安装的次序。

不自行替换为预建通知管道，不提前转交，不重新解释信号来源；Matrix 在信号处理器中使用互斥锁、创建线程、读取私有信号字段，以及 debugger 恢复窗口均属于本次保留的原行为和风险。初始化与关闭接口需要适配现有 SDK，但不能通过伪造启动/关闭结果掩盖 Matrix 实际生命周期限制。

### 三、按原版本分支和首次写入截取

原 `hookAnrTraceWrite` 在需要时安装：API 27+ 针对 `libcutils.so` 的 `connect`，较低版本针对 `libart.so` 的 `open`；write 的调用方在 API 24/25 和 30+ 为 `libc.so`，API 29 为 `libbase.so`，其余为 `libart.so`。被选中的 connect/open 命中目标路径时记录线程号和标记；被 hook 的 write 首次在该线程命中时，在调用原 write 之前复制 `buf` 对应的字符串到 Matrix trace 文件，触发相应回调并关闭本次标记。按 ByteHook 自动模式要求适配原函数调用和 hook 生命周期。

不增加输出 FD 相等判断、recvmsg hook、分段拼接、以实际成功字节重建、延后文件输出或其他新的采集逻辑。Matrix 此处不使用 `count` 限制字符串读取、不检查实际写入结果，可能产生越界读取、错选或不完整 trace；本次忠实迁移并保留可复现的验证边界，不承诺这些情况已解决。仅记录安全阶段日志，不额外输出报告原文。

### 四、保留 Matrix Java 判定和回调

复用 Matrix 的主线程 Java 栈、前后台查询、`/proc/self/cgroup`、反射读取 `MessageQueue.mMessages` 和时间计算。前台过期超过 2 秒或后台过期超过 10 秒时立即以 `fromProcessErrorState=false` 回调；否则每 500ms 检查本进程错误状态，最多 40 次，发现 `NOT_RESPONDING` 才以 `true` 回调，未确认时不伪造 Matrix 的 ANR 回调。保留 Matrix 针对其他 UID 错误状态的原判断及 20 秒窗口。debugger 分支按原逻辑收集主线程 Java 栈，按 `lastReportedTimeStamp` 执行 20 秒去重，不标为新生成的 Native 栈。

直接迁移 `SimpleDeadLockDetector` 的线程匹配、等待关系、DFS、EOF 和 `nativePollOnce` 判断，按其真实解析结果产生对应回调；不替换为新的锁分析算法。提供源码等价测试和固定 trace 样例，覆盖有等待/无等待、锁环、EOF、nativePollOnce 和格式差异，但测试期望跟随原实现，不能将原实现无法判断的情况伪称已识别。

### 五、本地报告适配

Matrix 结果回调和 trace 文件经本项目适配器分别写入私有 `noBackupFilesDir`，保存当次应用/进程身份和可读取的结果；保持 Matrix 原始判断值及本次已捕获的内容，不补造失败前不存在的 ANR 回调或采集证据。手动 trace 与 ANR 结果分开标识。原始 trace 由 Matrix 的写入路径生成，本地归档不得在其 hook 代理中添加分段、延时或清理逻辑改变采集结果。

完成回调在结果实际保存后触发；提供按 ID 读取/删除、重启读取和有界容量/过期清理。采用私有路径和原子报告索引，避免路径穿越与已完成报告被半写入覆盖；存储失败、空间不足、采集进程在结果写入前退出明确返回失败或无报告，不虚构采集成功。存储配额和日志限制不作为 Matrix 捕获结果完整或进程终止前必然落盘的证明。

## 风险与取舍

- Matrix 原有 SIGQUIT handler 内锁/线程创建、等待 20 秒后才转交以及 debugger 临时恢复窗口被要求保留；可能影响系统处理或引发竞争，需记录并以隔离设备验证。
- Matrix 原有首次写入/无长度字符串读取可能产生错误或不完整 trace；不得伪造为已完整采集。此次不引入用户未授权的修复。
- Matrix 的锁解析和 20 秒去重存在局限；输出以实际回调和源代码行为为准，不将其升级解释为系统确认、全量线程或真实 Native 回溯。
- 原始 trace 可能含敏感信息，保留在应用私有目录，常规日志仅记录安全阶段；不将真实设备数据加入仓库。
- ByteHook 对应版本分支和动态安装必须通过设备实际验证；API/ROM 兼容、物理设备和系统 ANR 未验证时不可声称通过。
- ByteHook 1.1.1 不包含上游 1.1.2 修复的 unhook 偶发崩溃及其他修复；本迁移按 Matrix 原行为不主动 unhook，但其他组件若卸载 hook 仍须考虑该已知版本风险。

- 宿主正常运行不主动打印 trace，预期短间隔重复 ANR 极少。本期接受同一活动文件在异步归档前被覆盖而导致报告内容错配的限制，保留复现和未完成验收记录。

## 实施与回退

先按本版本修订任务与规格，再以原源码为基准迁移信号及 Java 采集，完成 ByteHook 的等价替换，最后增加本地报告、SDK 装配和独立消费者。每个需要设备的场景留存实际结果；无设备时对应任务保持未完成。关闭 ANR 不删除已完成报告。普通开发不自动同步知识库，归档流程按项目配置显式执行 knowledge-sync。
