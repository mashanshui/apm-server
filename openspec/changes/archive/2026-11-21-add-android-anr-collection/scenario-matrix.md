# Android ANR 规格场景与验证对应表

本表只记录本变更当前的证据和缺口，设备、版本、测试条件及失败细节见[验证记录](verification.md)。`已覆盖`仅指表中列明的环境，不代表全部设备或 ROM 已验收。连续 trace 错配已在模拟器和 Pixel 真机复现，正常宿主不主动调用 `printTrace()`，用户接受短间隔重复捕获错配为本期已知限制；相关场景保留原验证缺口。

## ANR 采集规格

| 场景 | 证据 | 当前边界 |
|---|---|---|
| 默认关闭与不支持平台 | `AnrPlatformTest`、`AnrNativeLoadingTest`、API 21 arm64 降级；华为 API 29 真机上的 32 位进程降级和历史报告读取 | Pixel API 33 子进程测试前两次启动超时、第三次通过，原因未确定 |
| 外部 SIGQUIT | API 35 模拟器和 Pixel API 33 真机系统输入 ANR；API 29/33 真机受控外部信号超时转交 | 真机仅覆盖当前设备与系统版本 |
| 手动 trace | `MatrixSignalHandlerTest` 与独立消费者在 API 23/24/26/27/29/35/36 模拟器及 API 29/33 真机运行 | API 23/24/26 模拟器需预置系统 trace 文件 |
| 外部 debugger 信号 | API 35 模拟器及 API 29/33 真机由同 UID 子进程发送信号，真机保存 Java 栈并观察 libc 请求 dump | Pixel 请求响应报错；两台真机均未证明完整 Native 转储 |
| 消息超时 | `verify-matrix-judgment.py` 前后台边界对照；API 35 系统 ANR 得到非错误状态来源 | 不代表全部 ROM 的私有字段可反射 |
| 系统错误状态 | `verify-matrix-judgment.py` 对照 PID、UID 和系统错误状态 | 未在设备观察到 `fromProcessErrorState=true` 的真实系统 ANR |
| 未确认 | 主机对照脚本模拟空状态、异常和 40 次轮询；debugger 普通状态未产生报告 | 设备仅覆盖所记录的状态 |
| 相邻信号 | 主机对照脚本覆盖 20 秒窗口两侧；API 35 两次 debugger 信号留证 | 未对所有竞态和 ROM 验证 |
| 栈来源 | 主机对照及 API 29/33 真机 debugger 信号测试确认保存主线程 Java 栈 | 不将其称为新采集的 Native 栈 |
| 不同 API 调用方 | API 23、24、26、27、29、35、36 arm64 模拟器，以及 API 29 和 33 arm64 真机的手动 trace | API 23/24/26 模拟器预置系统文件；未覆盖全部 ROM |
| 首次被 hook 的 write | `verify-matrix-hook.py` 主机及 API 35 arm64 对照；各版本手动 trace | 主机和设备对照程序的原函数链使用桩 |
| 原实现边界 | 对照脚本验证 `count`、回调顺序和原 write 失败；源码记录非 NUL、错选和分段风险 | 不触发非 NUL 越界，也不声称补全 trace |
| 对照 Matrix 样本 | `verify-matrix-detector.py` 对照七组样本；Pixel API 33 真机锁环包含主线程和测试工作线程 | 真机锁环结果未与原 Matrix 应用在同一设备逐次对照 |
| Matrix 发出 ANR 回调 | API 35 系统输入 ANR 产生 `SIGNAL_ANR`，保存 Java 栈和判定来源 | 连续 trace 的异步归档存在已复现错配 |
| 只有手动 trace | 各版本仪器测试和独立消费者读取 `MANUAL_TRACE` | 同上，连续事件不能保证一一对应 |
| 进程重启 | 独立消费者强制停止后读取原 session；待归档任务中途终止测试 | 未覆盖原子写入的每个机器指令窗口 |
| 容量或存储异常 | `AnrReportStoreTest` 配额、过期、损坏和写入故障；API 29 模拟器及 API 29/33 真机文件权限失败 | 没有将设备磁盘实际写满 |
| 回调失败或关闭 | `MatrixSignalHandlerTest` 覆盖回调异常、保存失败、关闭后读取 | 连续 trace 已复现错误对应和成功回调，任务 5.6 未完成 |
| 离线采集与全量刷新 | `AnrSdkIntegrationTest` 捕获既有 app_start 请求并检查无 ANR；独立消费者无需 App Key | 仅覆盖测试时间窗口，不代表其他能力不上传 |
| 手动和系统 ANR 验收 | API 35 模拟器及 Pixel API 33 真机系统输入 ANR、手动 trace；Pixel 真机锁环产生 `DEADLOCK` | 华为 API 29 真机两次系统无响应提示未观察到 Matrix 报告，原因未确定 |

## SDK 组合规格

| 场景 | 证据 | 当前边界 |
|---|---|---|
| 无网络的独立 ANR 接入 | v3 独立 ANR Debug/Release 消费者离线采集、关闭及重启读取 | 连续 trace 错配仍存在 |
| 全量默认与显式启用 | `PerformanceConfigTest`、`PerformanceSdkIntegrationTest`、全量联合仪器测试 | 不推及未验证设备 |
| 既有精简接入 | v3 仅 Crash/仅 Metrics 解析图、DEX、SO、清单和设备烟测 | API 29 arm64 模拟器 |
| 共同身份与恢复 | `AnrSdkIntegrationTest` 对照共享身份；独立消费者跨进程读取旧 session | 连续 trace 内容对应问题仍在 |
| ANR 启动失败 | 全量单元测试注入失败；仪器测试使用不可创建的活动目录 | 未逐一注入所有 ROM 的 hook 安装失败 |
| 重复初始化与全量关闭 | `MatrixSignalHandlerTest`、SDK 集成测试、核心运行时逆序关闭测试 | Matrix 停止后本进程重启采集返回失败 |
| 借用外部组件 | `AnrSdkIntegrationTest` 先独立启动，再启动并关闭全量 SDK；华为真机验证第三方 handler 接管 | 原版关闭会覆盖采集期间后来安装的第三方 SIGQUIT handler |

## SDK 分发规格

| 场景 | 证据 | 当前边界 |
|---|---|---|
| 独立工程解析本轮发布 | v3 隔离 Maven 仓库及独立消费者 Release 构建 | 不依赖源码替换 |
| 使用 SDK 的公开配置类型 | 全量消费者仅声明全量 SDK 与 Native Tools 坐标并编译配置 | 以 v3 产物为准 |
| ANR 消费者编译基线 | compileSdk 36、AGP 8.13.2、Gradle 8.13、Kotlin 1.9.25 构建 | targetSdk/minSdk 未随 SDK 强制升级 |
| 独立 ANR 消费者 | v3 AAR、POM、module metadata 和 Debug/混淆 Release 运行 | 不依赖宿主 NDK/CMake 或宽泛 keep |
| 不支持平台加载 | API 21 arm64 降级并读写合成历史报告；华为 API 29 真机的 32 位进程未加载 ANR/ByteHook 并读取原报告 | 当前真机覆盖 armeabi-v7a 进程，不代表全部非 arm64 架构 |
| ANR 与 Jank 共存 | v3 独立全量消费者手动 trace、Jank 文件导出、Native SIGSEGV 恢复和关闭 | API 29 arm64 模拟器；Rhea/KOOM 的既有 libc++ 重复边界见验证记录 |
| 既有精简分发回归 | v3 仅 Crash/仅 Metrics 的解析图、DEX、SO、清单和混淆 Release 烟测 | 未引入 ANR 相关依赖和组件 |
| 验证矩阵缺项 | 本表及[验证记录](verification.md)保留缺项 | 华为系统无响应场景未观察到 Matrix 报告；真机 debugger 的完整 Native 转储未证实；连续 trace 错配相关任务未完成 |
