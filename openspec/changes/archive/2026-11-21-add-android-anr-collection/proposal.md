# Android ANR 客户端采集提案

## Why

Android 工程已有 Crash、Jank 和消息超时抓栈，但尚无系统信号驱动的 ANR 采集与本地报告。迁移 Matrix SignalAnrTracer 的采集思路，先建立可独立验证、进程终止后可恢复的客户端闭环，为后续服务端接收和展示提供真实数据基础。

## What Changes

- 新增独立 `performance-anr` 模块，迁移 SIGQUIT、debugger 信号通知、主线程 Java 栈、ART 线程 trace、消息及前后台/cgroup 信息和锁等待分析；移除 Matrix 运行时与 Issue 上报依赖。
- 使用 ByteHook 1.1.1 的 PLT hook 替换 xHook/grouped API，按调用方 SO 限制 trace 截取范围；统一到项目原有 ShadowHook 2.0.0，并核对二者依赖、初始化及打包兼容性。
- 按用户最新决定将 Android 工程和独立消费者恢复到 compileSdk 36，恢复原有构建工具基线并验证 Kotlin 1.9 消费兼容；保持原有 targetSdk 和 minSdk。
- 按用户明确要求保留 Matrix 信号处理、线程、判定、trace 截取和死锁解析原逻辑；仅将 xHook 接口适配为 ByteHook，并用项目接口替换 Matrix 依赖。不额外引入 recvmsg 跟踪或修改采集语义。
- 在 Matrix 采集结果之外增加已确认的本地报告持久化、重启读取、容量清理和完成回调，提供列表及按 ID 读取/删除；不连接现有事件上传队列。
- 接入全量 SDK 与按需组件装配，新增示例 ANR 测试入口及自动化/设备验收。ANR 默认关闭，由宿主显式启用，首期运行采集限定主进程、API 23+、arm64-v8a；不支持时提供明确状态，本地报告读取独立于监控是否开启。
- 本期不新增后端、数据库、网页、MCP、上传端点或上传重试；不把现有消息超时监控等同于系统 ANR，也不引入新的 Native unwind 引擎。Matrix 原实现的已知边界原样保留并如实说明，不冒称已修复。

## Capabilities

### New Capabilities

- `android-anr-collection`：ByteHook 驱动的系统信号监听、ANR 候选与确认、线程证据采集、有界本地报告、恢复及读取回调。

### Modified Capabilities

- `modular-sdk-composition`：允许独立或全量显式启用 ANR，复用运行身份与生命周期，单独使用 ANR 无需网络凭据和 TransportSession，其他按需能力不隐式启动 ANR。
- `modular-sdk-distribution`：将新增 ANR 模块纳入统一版本产物交付，提供 JNI、依赖与 consumer rules，保持仅 Crash/仅 Metrics 的依赖隔离。

## Impact

- 代码范围：`android/settings.gradle.kts`、共享 Gradle 依赖声明、新增 `android/performance-anr/`、`performance-sdk` 的配置/生命周期/测试、示例 `app` 及 `verification/modular-consumer` 的 ANR 消费验证。
- 依赖：新增 ByteHook 1.1.1，统一使用项目原有 ShadowHook 2.0.0；Android 构建基线为 compileSdk 36，并与 Rhea 的版本、初始化和 SO 打包共同验证；不把 `performance-native-tools` 作为 ANR 必需依赖。
- 对外影响：新增客户端配置、状态、报告读取和回调接口；本地格式独立版本化，不作为已发布 HTTP 契约；已有服务端和其他功能事件语义不变。
- 来源：Matrix 本地检出的 `SignalAnrTracer.java`、`SignalHandler.*`、`AnrDumper.*` 及 `MatrixTracer.cc` 中 ANR 相关实现；保留迁移源码的来源与许可证声明，不引入整个 Matrix。
- 验证：主机逻辑测试、原生产物与依赖检查、Release 混淆消费者、系统 ANR/手动信号/故障注入设备验证分别留证。当前只是规划，尚未证明任何新采集能力可运行。
