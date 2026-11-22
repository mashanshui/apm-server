# Android ANR 验证记录

用户最新决定恢复 compileSdk 36 并改用 ByteHook 1.1.1。下方“此前版本验证记录”中的 API 37、ByteHook 1.1.2 和 ShadowHook 2.0.1 证据仅代表此前方案，不能作为当前版本的通过证明。

全部增量规格场景的逐项证据和缺口见[场景对应表](scenario-matrix.md)。

## compileSdk 36 与旧版 ByteHook 的本轮验证

- 主工程和独立消费者已恢复 compileSdk 36、AGP 8.13.2、Gradle 8.13、Kotlin 1.9.25；`targetSdk`、`minSdk` 保持原值。`performance-anr` 和示例 Release 构建、ANR/SDK JVM 测试、模块依赖检查通过。
- ByteHook 1.1.1 发布元数据声明 ShadowHook 1.1.1；本模块显式声明项目原有的 ShadowHook 2.0.0，ANR 与全量应用的 `releaseRuntimeClasspath` 均解析为 2.0.0。Release APK 的 arm64 ByteHook 和 ShadowHook 各一份，SHA-256 分别为 `1fa39f206cf1cb5863dc3770ec3a21339921364e647a3ab4dbb094eab83676d8` 和 `d91c60ad7d65e27b2430b0171a1bbf195a07c8cb4d111f645374a88e42570448`，与对应官方 AAR 一致。
- 独立 ANR AAR 和消费者 APK 未包含 xHook；全量示例 APK 中仍有原有 `libxhook_lib.so`，`dependencyInsight` 显示它来自项目已有的 `koom-java-leak:2.2.4` 传递依赖，而非本次 Matrix ANR 迁移。
- API 29、arm64 隔离模拟器执行 ANR 仪器测试：支持平台与手动 trace 测试通过，API 21 专用测试按条件跳过。实际日志显示 ByteHook 1.1.1 自动模式和 ShadowHook 2.0.0 SHARED 模式初始化成功；手动 trace 已保存且原始字节校验通过。
- API 35、arm64 只读模拟器执行 ANR 仪器测试及示例 `AnrHookCompatibilityTest`、`AnrDemoFlowTest`：手动 trace 原字节保存、关闭后读取、Rhea 与 ByteHook 1.1.1/ShadowHook 2.0.0 共存，以及从主页查看和删除报告均通过。
- 对 `SignalHandler.cc` 和 `AnrDumper.cc` 从命名空间开始的源码主体去除注释和空白后逐字对照，均与指定 Matrix 检出一致；API 35 仪器测试检查 SIGQUIT 与 debugger 信号的 `SigCgt` 安装位，并在关闭后检查其恢复为启动前状态。该检查不能证明第三方在采集中接管处理器时的所有权安全。
- 同一 API 35 模拟器以示例主线程阻塞加后续输入触发系统输入 ANR；系统记录超时并完成 tombstoned 输出，本轮 ByteHook 1.1.1 采集保存了 `SIGNAL_ANR` 与 `ANR_TRACE`。此项未覆盖物理设备，亦不证明截取内容完整。
- API 35 隔离模拟器通过同 UID 的独立进程先后两次发送 debugger 信号，间隔超过 Matrix 的两秒处理窗口；两次均观察到 Matrix Java 回调阶段及 libc 请求 dump，目标进程仍存活，未因普通状态伪造 debugger ANR 报告。模拟器的 `crash_dump64` 报告无法读取 guest 状态和请求响应失败，因此不能称 Native 栈转储成功；两秒内暂存和恢复的处理器指针未逐时采样。
- API 21、arm64 隔离模拟器运行不支持平台测试通过；独立 ANR 混淆 Release 消费者启动后返回不支持状态，并可打开本地报告列表。
- 使用唯一测试版本向隔离 Maven 仓库发布，再以独立工程的 Kotlin 1.9.25 和 compileSdk 36 构建 ANR Debug/混淆 Release；两种 APK 均在 API 29 上生成手动 trace，并在强制停止进程后重新启动、读取原报告和原运行身份。AAR、POM、Gradle module metadata、许可证及 JNI/consumer rules 已检查；消费者无需 App Key、源码替换、宿主 NDK/CMake 或宿主额外 keep 规则。
- 主工程 `test checkPerformanceModuleDependencies --offline` 通过，Debug/Release JVM 测试结果合计 458 项、跳过 2 项、失败 0 项；独立 Crash、Metrics 和全量消费者的 Debug/混淆 Release 均可构建。
- API 35 只读模拟器断开默认网络后，独立 Crash、Metrics、全量 Release 消费者各自的初始化、关闭和重启烟测通过。APK 清单中仅 Crash/仅 Metrics 均无 ANR/ByteHook/ShadowHook；全量包含各一份对应 arm64 SO。此烟测未触发 Native Crash，也未完成完整 Jank 导出。
- 上游 [ByteHook 1.1.2 发布说明](https://github.com/bytedance/bhook/releases/tag/v1.1.2)记载了对旧版本 unhook 偶发崩溃等问题的修复；按用户决定保留 1.1.1。Matrix 迁移自身按原逻辑不主动 unhook，这不等于其他使用者的风险已消除。

### 暂停后恢复的本地报告验证

- 在当前实现上重新执行 ANR 和 SDK Debug JVM 测试以及模块依赖检查；ANR 存储测试覆盖索引写入空间不足错误注入、正文路径不可写、已提交报告保留、容量/过期、字节上限和相邻功能目录保护。此处验证的是配额和注入的 I/O 错误，没有将设备磁盘实际写满。
- API 35、arm64 只读隔离模拟器重新执行 `MatrixSignalHandlerTest`：Matrix 手动 trace 的原始字节与已保存报告一致；损坏测试索引时不发出成功回调，回调异常被隔离；相同配置共享句柄、冲突配置、关闭后处理器状态和报告读取均通过。重启采集返回 `matrix_restart_not_supported`，没有伪称重新安装成功。
- 用暂停前最后一次发布的唯一版本重新构建独立 ANR Debug 和混淆 Release 消费者；两种 APK 在 API 35 模拟器上均完成手动 trace、强制停止、重新启动、读取原运行身份和删除。测试时模拟器网络关闭，消费者无需 App Key。旧版本的 API 29 记录仍只表示当时版本的结果。
- 检查 SDK Java 和 Native 日志调用，Matrix 日志适配器只输出阶段文本；此次手动 trace 的 SDK 相关日志中未出现检查过的线程 trace 特征。该检查仅覆盖上述源码和本次测试日志，不代表系统或其他第三方组件的日志策略。
- Matrix 回调后由后台线程读取活动 trace。API 29、arm64 只读 AVD 的显式诊断 `AnrConsecutiveTraceTest` 已复现错配：第一份报告的完成回调被阻塞时，第二、第三次手动 trace 分别捕获到不同线程标记；归档队列恢复后，第二份报告读到了第三次 trace，并已发出保存完成回调。该诊断通过 `-Pperformance.anrIsolatedTestRunner=true -Pandroid.testInstrumentationRunnerArguments.class=com.shanshui.performance.AnrConsecutiveTraceTest -Pandroid.testInstrumentationRunnerArguments.verifyTraceRace=true` 显式运行，当前按正确对应关系断言会失败。此问题在 SDK 异步归档层，不能归因于 Matrix 原版；任务 5.1 保持未完成。
- 用户已选择保持 Matrix 原有时序，暂不采用在回调中同步读取文件的快照方案；因此连续 trace 对应关系及完成回调的准确性限制保留，任务 5.1 和 5.6 均不标为完成。
- 进程在报告和索引提交前退出时，未完成的结果可能丢失，不发出保存成功回调；已提交报告可在下次进程读取。未改变 Matrix 首次 write 的原始行为来掩盖该边界。
- 在主机以 JDK 21 执行 `python3 openspec/changes/archive/2026-11-21-add-android-anr-collection/verify-matrix-detector.py <Matrix的SignalAnrTracer.java路径>`，从指定 Matrix 检出 `3b8293bd65d47eeea7caf1f32a3a5d4d5eab60e7` 和迁移文件直接提取解析器并运行相同输入。七组锁环、无等待、nativePollOnce、EOF、格式变化和非法线程 ID 样本的线程文本、等待关系、锁类型及对应回调分支一致；这不是物理设备死锁验收。
- 在主机执行 `python3 openspec/changes/archive/2026-11-21-add-android-anr-collection/verify-matrix-hook.py <Matrix的MatrixTracer.cc路径>`，分别提取原版和迁移版 `connect/open/write` 代理，使用同一原函数桩对照五组安全终止样本。结果一致：目标线程首次 write 在原函数调用前复制字符串并触发回调；`count` 小于字符串长度时仍复制完整字符串，原 write 返回失败时此前的复制与回调不会撤销。其他线程、第二次写入、空缓冲和空目标路径遵循原版状态。该主机对照用桩替代真实 ByteHook 链，不能替代设备 hook 验证。
- 在主机执行 `python3 openspec/changes/archive/2026-11-21-add-android-anr-collection/verify-matrix-judgment.py <Matrix的SignalAnrTracer.java路径>`，直接提取两版消息判断、进程错误检查、轮询及 debugger 分支。18 组可控样本覆盖前台 2 秒和后台 10 秒边界、立即通知、不同 UID/PID、反射失败、空状态、40 次及每次 500ms 轮询、20 秒去重和未确认不回调，结果一致。线程和休眠在主机对照中使用同步桩记录，未将其视作真实线程调度及 20 秒挂起的设备验证。
- 同一脚本另对照两版 `onANRDumped`、cgroup、主线程栈及前后台辅助方法主体，忽略注释、安全日志和方法可见性差异后保持一致。API 29、arm64 上的 `AnrJavaSamplingTest` 使用真实阻塞主线程，验证默认等待配置为 20 秒、采样线程名称、主线程 Java 栈、消息时间差、cgroup 和回调来源；测试把可配置超时缩短到 150ms，证明回调仍阻塞时 Java 入口可按该配置超时返回。该测试直接调用 Java 入口，真实系统 ANR 信号另见 API 35 记录；未声称实际挂起了 20 秒。
- API 29、arm64 只读 AVD 上的 `AnrSignalTimeoutTest` 在测试进程中阻塞 Matrix Java 回调，并由同 UID 的独立子进程发送外部 SIGQUIT；默认 20 秒内没有 trace 保存，超时后 Signal Catcher 得到转交且 SDK 保存 `ANR_TRACE`，期间 Java 回调仍处于阻塞。该测试使用受控信号和回调桩，不将其称作真实系统 ANR。
- API 29 只读 AVD 使用 NDK 编译本变更的 `signal-owner-probe.c` 并注入测试应用缓存后运行 `AnrSignalOwnershipTest`：Matrix 启动后，探针成功安装第三方 SIGQUIT handler；Matrix 关闭后该 handler 被原版的恢复逻辑覆盖，处理器回到 Matrix 启动前的值。此结果说明不能承诺保留采集期间后来接管的第三方处理器，本次没有修改 Matrix 的恢复顺序。
- API 29 只读 AVD 的 `AnrStorageFailureDeviceTest` 临时移除测试应用报告目录的写权限，Matrix 第二次活动 trace 仍生成，但报告正文写入返回 `IO_ERROR` 且没有成功回调；旧报告仍可读，恢复目录权限后再次采集成功。此项验证真实权限类 I/O 失败，不声称模拟了磁盘实际耗尽。
- API 29 只读 AVD 的独立 ANR Debug 消费者先提交一份报告并阻塞完成回调，再触发第二次手动 trace；确认第二个归档任务已排队后强制停止测试进程。重启后只有首份已提交报告可读，保留原 session，排队但未提交的第二份没有伪造成成功报告。该测试覆盖终止时队列尚未执行的窗口，不覆盖每一个原子写入指令位置。
- Matrix 的字符串复制未按 `count` 限长，非 NUL 输入可能越界，分段、其他首个写入及原 write 失败可能令保存结果与系统最终内容不同；本次不以不安全输入触发越界，也不改变这些原有行为。
- 使用 NDK 28 的 API 23 arm64 交叉编译器，将同一代理对照程序推送到 API 35 隔离模拟器运行，五组样本与原版一致；该程序仍使用原函数桩。真实 ByteHook 链另外通过 API 35 仪器测试的两次手动采集、全量 SDK 关闭后外部所有者再次采集和最终关闭验证。
- 原 xHook 在每次准备时分组注册并异步 refresh；ByteHook 使用按调用方后缀筛选的 partial hook 任务及 `BYTEHOOK_CALL_PREV` 链接原函数，任务保留至进程退出。与原 `unHookAnrTraceWrite` 一样，每次捕获只清除活动标志；关闭采集器恢复信号处理器，不声称卸载了全部 ByteHook 任务。采集中途关闭和连续信号压力仍属于未完成的故障联合验收。
- 使用 `-Pperformance.anrIsolatedTestRunner=true` 在 API 35 只读模拟器运行 `AnrSdkIntegrationTest`：在独立目录注入活动路径不可用后返回 `trace_directory_failed`，随后独立 ANR 与全量 SDK 使用相同身份共同运行；Rhea 可用，日志显示 Native Crash 初始化及关闭完成，全量关闭后外部 ANR 句柄仍可采集。另由 `MatrixSignalHandlerTest` 验证重复初始化、冲突和 Matrix 停止后不支持重启；核心运行时测试验证组件逆序关闭。该项没有触发真实 Native Crash。
- 组合测试使用设备回环 `MockWebServer` 捕获并解压既有 `app_start` 请求，以它证明捕获链路有效；两次手动 trace 的报告 ID 和类型均未进入所捕获请求，采集和全量 flush 前后的 Crash/Jank 队列文件清单一致。独立消费者的跨进程读取在关闭模拟器网络时完成，不需要 App Key。该检查不代表其他 Crash、Jank 或指标事件不会上传。
- 单独构建 `performance-sdk` 仪器测试 APK 时，现有 Rhea 与 KOOM 依赖携带的 `libc++_shared.so` 内容不同，发生重复打包；因此组合测试使用示例应用已有的打包配置和测试专用 runner，没有在 SDK 模块新增覆盖或随机选库。此问题属于既有组合打包边界，不能从本次冒烟推断两份 C++ 运行库完全兼容。
- 使用独立临时 AVD 的 Android 16 / API 36、arm64 默认镜像运行 `MatrixSignalHandlerTest`，两处 ByteHook 安装日志、手动 trace 原字节、回调失败隔离与关闭检查通过；同一设备上的独立 ANR 混淆 Release 消费者完成采集、强制停止、重启读取原身份及删除。此结果不覆盖其他 Android 版本或物理设备。
- Android 6 / API 23、arm64 临时 AVD 首次测试暴露本次接入层的进程识别问题：`getMyMemoryState` 不保证填写 `processName`，主进程被误判为子进程。已将 API 28 以下的 ANR 平台识别改为读取 `/proc/self/cmdline`，不更改 Matrix 信号或判定逻辑。重新执行设备测试，主进程允许采集，独立子进程返回 `NOT_MAIN_PROCESS` 且未加载 ANR JNI。
- 随后核对 ANR 复用的公共运行身份时，在同一 API 23 模拟器复现公共组件也使用了上述不可靠的进程名字段，导致主进程 `sessionId` 与 `processId` 不相同。公共身份的低版本判断同样改为读取本进程命令行；修改前的真实进程测试失败，修改后主进程两个字段相等、独立子进程两个字段不同，且子进程未加载 ANR JNI。此修正不改变 Matrix 采集逻辑。
- 同一 API 23 全新镜像起初没有 `/data/anr`，ART 无法打开 Matrix 原有的 `/data/anr/traces.txt`，手动 trace 未保存；在临时 AVD 内按测试需要创建该目录和文件后，ByteHook 注册及手动 trace、原始字节保存和关闭测试通过。该预置条件是测试环境操作，不是产品增加了文件创建或备用采集策略。
- 在完成上述平台修正后，将 SDK 发布为新的隔离测试版本 `1.0.0-anr-20261121-bytehook111-v3`，独立 ANR 消费者的 Debug 和混淆 Release 均在 API 23 预置 trace 文件的临时模拟器完成采集、强制停止、重启读取原身份及删除；不依赖 App Key 或网络服务。
- 公共身份修正后，以新的隔离测试版本 `1.0.0-anr-20261121-bytehook111-v4` 重新构建独立 ANR 混淆 Release；在同一已预置系统 trace 文件的 API 23 模拟器再次确认主进程身份约定、手动 trace 采集、强制停止后读取旧报告及删除均通过。
- 同一修正后的代码在 API 29、arm64 只读模拟器运行完整 ANR 仪器测试：5 项中 4 项通过，API 21 专用降级项按条件跳过；主/子进程身份、Java 采样及手动 trace 均覆盖。核心、ANR、SDK 的 Debug/Release JVM 测试、ANR 与示例 Release 构建和模块依赖检查再次通过。
- Android 7 / API 24、arm64 临时 AVD 验证主/子进程判断及 Matrix 手动 trace 通过，覆盖 Matrix 原分支的 `libart.so` open 与 `libc.so` write。新镜像缺少 `/data/anr/traces.txt` 时，ART 首次打开返回权限不足而无法保存；以 root 在该隔离 AVD 中预置可写 trace 文件后测试通过，未修改产品代码或关闭 SELinux。
- Android 8 / API 26、arm64 临时 AVD 在预置可写系统 trace 文件后通过主/子进程及手动 trace 设备测试，覆盖 Matrix 原分支的 `libart.so` open 与 write。此前未单独测试该镜像缺少文件时的采集结果。
- Android 8.1 / API 27、arm64 临时 AVD 无需预置旧版 trace 文件，通过主/子进程及手动 trace 设备测试，ByteHook 安装日志正常，覆盖 Matrix 原分支的 `libcutils.so` connect 与 `libart.so` write。
- Android 10 / API 29、arm64 只读 AVD 使用当前代码通过主/子进程和手动 trace 测试，覆盖 `libcutils.so` connect 与 `libbase.so` write。API 23、24、26、27、29、35 和 36 的 ARM64 结果合计覆盖了 Matrix 选择的各组调用方分支；旧版文件预置条件和缺失时的失败分别见上文。
- 使用 v3 发布坐标重新构建仅 Crash、仅 Metrics 和全量混淆 Release 消费者；前两者在 API 29 只读 AVD 完成初始化、关闭和重启烟测。解析图、APK 的 DEX/SO 与合并清单未发现这两者引入 ANR、ByteHook 或 ShadowHook；全量 APK 的 arm64 ANR、ByteHook、ShadowHook 各一份，后两者的 SHA-256 与本轮已核对的 1.1.1、2.0.0 AAR 一致。
- 同一 API 29 的独立全量混淆消费者启用 ANR、Rhea 和 Native Crash：手动 trace 已保存可读，Jank 真实生成非空文件并入队，正常关闭通过；另启进程在这些能力运行时向自身发送受控 SIGSEGV，进程退出后下一进程恢复了信号编号为 11 的 Native Crash 报告。该信号由测试主动发送，不代表已覆盖真实硬件访问故障、物理设备或完整 Native 符号化；所有测试使用不可连接的本机地址和关闭的模拟器网络。
- 尝试在本机启动 API 23 的 `armeabi-v7a` 临时 AVD，但当前 macOS ARM64 上的 Emulator 37.2.13 明确拒绝 32 位 ARM 系统镜像；该次尝试未验证非 arm64 运行，后续真机验证见下。
- 连接两台物理设备后，Pixel 4 XL（Android 13 / API 33）和华为 EML-AL00（Android 10 / API 29）均在 arm64 测试进程通过 `MatrixSignalHandlerTest`：手动 trace 原始字节保存、故障回调与关闭检查通过。两台设备的主/子进程身份与 JNI 不加载检查最终通过；Pixel 的相同子进程测试前两次等待启动超时，第三次通过，超时原因未确定，不能据此声称该现象已修复。
- 两台真机分别运行 `AnrStorageFailureDeviceTest`：移除测试应用报告目录写权限时没有成功回调，已保存报告仍可读；恢复权限后再次保存成功。此项没有写满设备磁盘。
- 两台真机分别运行 `AnrSignalTimeoutTest`：受控外部 SIGQUIT 进入测试进程后，Java 采样回调保持阻塞，约 20 秒后 Signal Catcher 继续处理并保存 `ANR_TRACE`。这是测试发送的外部信号，不作为系统自行识别 ANR 的证据。
- Pixel 4 XL 真机在测试应用中阻塞主线程 15 秒并触发后续输入；系统 `InputDispatcher`、`WindowManager` 和 `ActivityManager` 记录输入分发 ANR，Signal Catcher 日志显示已向 tombstoned 写入线程栈。SDK 新保存 `SIGNAL_ANR` 和 `ANR_TRACE`，分别检查到测试页面 Java 栈及 trace 中的测试页面线程证据；`fromProcessErrorState=false`。只检查测试报告的字段和标记，未向仓库保存设备原始线程栈。
- 华为 EML-AL00 使用同一 15 秒阻塞与后续输入时，厂商 `ZRHungService` 触发测试应用“无响应”提示；随后重启测试应用并触发持续主线程锁环，厂商再次显示该提示。两次观察期间均未找到目标进程的 SIGQUIT/Signal Catcher 日志，也没有新增 ANR 报告；锁环测试观察约 40 秒后只停止测试应用。该现象不能仅凭提示认定标准系统 ANR 已按 Matrix 路径触发，未验证系统是否实际发送 SIGQUIT，原因仍待核实。
- 为区分华为设备的信号处理与系统触发边界，重新触发同一测试锁环并等待无响应提示；确认此前没有新增报告后，由同 UID 子进程向测试进程发送外部 SIGQUIT。Signal Catcher 随后写入 tombstoned，SDK 保存 `SIGNAL_ANR`、`ANR_TRACE` 和 `DEADLOCK`；trace 与死锁报告包含测试工作线程，`SIGNAL_ANR` 的 `fromProcessErrorState=false`。该对照证明受控 SIGQUIT 到达后本次采集可工作，不证明此前厂商提示时系统是否发送了信号，也不能将手动注入当作系统 ANR 验收。
- Pixel 4 XL 真机从测试应用触发主线程与工作线程锁环并再次输入，系统产生输入分发 ANR；Signal Catcher 日志显示继续向 tombstoned 写入。SDK 保存 `SIGNAL_ANR`、`ANR_TRACE` 和 `DEADLOCK`；死锁报告的主线程文本含测试方法、锁环文本含测试工作线程，并记录两条等待关系。测试后仅强制停止本次安装的测试应用，未清理报告数据。
- 华为 EML-AL00 真机运行 `AnrSignalOwnershipTest`，测试探针在 Matrix 后接管 SIGQUIT；Matrix 关闭后第三方处理器被覆盖，并恢复为启动前的值，与模拟器观察和原 Matrix 恢复次序一致。该测试验证已知限制，不代表可保留后来安装的处理器。
- 两台真机分别运行 `AnrPhysicalSignalTest`：在隔离进程阻塞主线程并构造 Matrix 可识别的过期消息，由同 UID 的子进程发送 debugger 信号；`DEBUGGER_SIGNAL` 报告保存了测试主线程 Java 栈、`fromProcessErrorState=false`，没有伪填 Native trace。日志显示 libc 请求 dump，`crash_dump64` 进入处理；Pixel 的 dump 请求响应报错，华为日志也未证明完整 Native 栈输出。Pixel 上以应用 UID 直接运行 `debuggerd -b` 提示需要 root，因此以上是受控信号验证，不冒称原生 debuggerd 调用或 Native 转储完成。
- 两台真机由同 UID 子进程向隔离进程发送 SIGCONT，三秒观察窗口内未收到 ANR 报告，采集器仍可用；此对照不代表已测试全部非 ANR 信号。
- Pixel 4 XL 真机显式运行 `AnrConsecutiveTraceTest`，在受控阻塞第一份报告完成回调的条件下复现第二份手动报告读取第三次 trace，正确对应关系断言失败。与 API 29 模拟器结果一致；真实业务发生频率尚未测量，按用户决定保持当前时序、不修改 SDK 归档逻辑。
- Pixel 4 XL 真机的独立 arm64 混淆消费者在首份报告保存后阻塞完成回调，再确认第二次 trace 已排队并强制停止测试进程；重启后只读取到先前提交的报告及原 session，未提交任务没有伪装为成功。此项只覆盖排队后、处理前被终止的窗口。
- 华为 EML-AL00 先以 arm64 安装独立混淆消费者并保存手动 trace，保留测试应用数据后以 `--abi armeabi-v7a` 更新同一 APK。实际 32 位进程返回 `UNSUPPORTED_ABI`，确认没有映射 `libapm_anr.so` 或 `libbytehook.so`，并读取、校验及删除上一个 64 位进程保存的报告。Pixel 4 XL 上独立 32 位消费者也返回 `UNSUPPORTED_ABI` 并可列出报告；后者未验证历史正文。结合此前 API 21 降级及各 arm64 版本分支结果，首期架构降级和版本分支矩阵已有设备证据。

## 归档决策与遗留边界

- 宿主正常运行不主动调用 `printTrace()`，用户判断重复 ANR 在业务中极少短间隔触发，因此接受连续 trace 错配为本期已知限制。5.1、5.6 保留未完成状态和已复现结果。
- 7.4、7.5 按原验收要求保留未完成状态。华为无响应提示场景的系统信号送达、两台真机系统 Native 转储完成、采集中关闭、正文写完与索引提交之间被终止，以及磁盘实际耗尽仍缺少对应证据。源码可见未登记的正文不会被现有索引清理覆盖。
- 全量包中 ByteHook 和 ShadowHook 版本已核对；现有 Rhea 与 KOOM 所带不同内容的 C++ 运行库仍按消费者配置选取，联合冒烟只证明已执行的路径。

## 此前版本验证记录

### ByteHook 与 ShadowHook 依赖核验

- 官方 Maven 产物：`com.bytedance:bytehook:1.1.2` 依赖 `com.bytedance.android:shadowhook:2.0.1`；二者 AAR 均要求 `compileSdk 37`。两者各自的 `classes.jar` 均只包含 Java 类，字节码检查未发现 Kotlin 类引用；通过限定依赖路径排除其元数据带入的 Kotlin 标准库，不能将本次检查推及未来版本。
- `:performance-anr:dependencyInsight --dependency shadowhook --configuration releaseRuntimeClasspath` 和 `:app:dependencyInsight --dependency shadowhook --configuration releaseRuntimeClasspath` 均解析为 ShadowHook 2.0.1；Kotlin 标准库解析为 1.9.25。Rhea 的 ShadowHook 路径显式统一到相同版本。
- `:app:assembleRelease` 构建通过。Release APK 中各有一份 arm64 的 `libbytehook.so`、`libshadowhook.so`、`libapm_anr.so` 和 `librheatrace.so`；前两者的 SHA-256 分别为 `52037a6086f5b6c4ab588393b917d92a9b49beedec9813fd08f92666d9bf0433`、`fc84287eac46e3bade2f7e07e3c9efdca005822e21191d6db5b8cb222d53e8a0`，分别与官方 1.1.2/2.0.1 AAR 内同 ABI 的文件一致。
- Pixel_6 模拟器、Android API 35、arm64-v8a 上执行 `AnrHookCompatibilityTest` 通过：应用先初始化 Rhea，然后 ByteHook 以自动模式初始化成功，读取到 ByteHook 1.1.2、ShadowHook 2.0.1 和 SHARED 模式，Rhea 仍报告可用。测试使用只读临时模拟器和不可连接的本地测试地址。
- 上述依赖核验只证明指定构建及初始化冒烟；其他 Android 版本、物理设备和完整 Jank 导出仍需单独验证。

### API 37 构建和 Kotlin 1.9 消费边界

- 主工程使用 AGP 9.1.1、Gradle 9.5.1 与 compileSdk 37；示例及消费者的 targetSdk 仍为 36，原有 minSdk 未改变。现阶段通过 AGP 9 的过渡选项保留旧 DSL 和 Kotlin Android 插件，AGP 提示这些选项将在 10.0 移除。
- SDK Kotlin 以语言/API 1.9 编译；`AnrConfig.class` 元数据为 `1.9.0`，ANR POM 的 Kotlin 标准库为 1.9.25。ByteHook 及 ShadowHook 的 Kotlin 标准库排除按依赖路径记录在发布元数据中。
- 独立临时 Maven 仓库以唯一测试版本发布 Core 和 ANR；另建使用 Kotlin Gradle Plugin 1.9.25、AGP 8.13.2、compileSdk 37 的独立应用，从发布坐标构建成功，在 API 35 arm64 模拟器运行后读取到 `ANR_DEPENDENCIES_OK`，其中验证了配置类型、ANR JNI 加载及 ByteHook 初始化。AGP 8.13.2 对 compileSdk 37 发出超出官方支持范围的警告，因此本项只证明这组实际构建和运行结果，不宣称该消费者工具组合得到官方支持。
- 主工程 `assembleRelease publishToMavenLocal` 使用隔离仓库通过；`test checkPerformanceModuleDependencies` 通过，结果文件合计 220 项、1 项跳过、0 项失败。独立 Crash、Metrics 和全量消费者的 Debug APK 从隔离仓库解析并构建成功。

### ART trace 文件描述符核对

- [ART SignalCatcher 源码](https://android.googlesource.com/platform/art/+/5c803116e26ef40ad2a9c14d3411e21d149e0c9b/runtime/signal_catcher.cc)的 tombstoned 调用分别返回通信 socket 与 trace 输出描述符；[tombstoned 客户端源码](https://android.googlesource.com/platform/system/core/+/refs/tags/aml_ips_331111030/debuggerd/tombstoned/tombstoned_client.cpp)通过接收文件描述符的消息获取输出描述符。仅将 connect 的 socket 描述符与后续 write 描述符直接比较，不能保证捕获该 trace。
- Android 15 / API 35 arm64 模拟器系统库静态检查：`libbase.so` 和 `libcutils.so` 含 `recvmsg` PLT 重定位，`libtombstoned_client.so` 引用 `ReceiveFileDescriptorVector`。这只表明相关候选 hook 点存在，尚未通过运行时调用链证明某一个 hook 点覆盖该设备的全部 trace。
- 此前提案曾将 socket FD 与 write FD 关联，后经对照发现 Matrix 原实现并未这样做。用户要求严格迁移 Matrix 后，已撤销该额外判断，不增加 recvmsg hook；Matrix 按目标 connect/open 命中的线程截取首次被拦截的 write，仍存在错选、不完整及非 NUL 字符串读取等原实现边界。

### Matrix 迁移与模拟器验证

- 本地 Matrix `3b8293bd65d47eeea7caf1f32a3a5d4d5eab60e7` 的信号处理和死锁解析已迁入；ByteHook 使用按 SO 后缀过滤的 partial hook，以覆盖原 xHook 正则匹配的多个调用方。ByteHook 的任务同步注册并在后续库加载时继续生效，与 xHook 的异步 refresh 机制存在 API 差异。
- Pixel_6、API 35、arm64 的只读隔离模拟器上，主线程启动 Matrix 采集器后，手动 SIGQUIT 产生了 Matrix trace；SDK 保存完成回调、报告读取、关闭后的再次读取和回调异常隔离通过。Matrix 初始化在仪器测试工作线程时，同一手动信号由系统 Signal Catcher 处理但未生成 Matrix 文件；本次保留原初始化线程语义，不能据此保证任意线程启动均可采集。
- 新建的隔离 API 29、arm64 模拟器执行 `:performance-anr:connectedDebugAndroidTest`，两项通过，覆盖 Matrix 对 API 29 选择 `libbase.so` write 的手动 trace 路径及原字节保存；不代表其他 API 分支已经通过。
- 新建的隔离 API 21、arm64 模拟器实际运行不支持平台测试，返回 `UNSUPPORTED_API`，仍能保存、读取、删除合成历史报告，且未加载 ANR JNI；未将 API 21 作为可采集平台。
- 按 Matrix 原实现保留 JNI 信号来源、消息判断、线程匹配与首次 write 截取；固定锁环、未等待主线程和 EOF 样本的 JVM 测试通过。当前尚未完成与原 Matrix 的全样本逐项设备对照。
- Matrix 活动 trace 在回调后由 SDK 保存线程读取；连续 trace 覆盖活动文件时，尚未证明每个异步读取都对应原回调，不将该情况作为已验证的完整采集。保存内容同时保留 Matrix 文件的原始字节编码和文本视图，不修改 Matrix 采集逻辑。
- 示例应用 `AnrDemoFlowTest` 在相同模拟器上从主页进入 ANR 页面，触发手动 trace，读取 `MANUAL_TRACE` 详情并删除报告；构建使用不可连接的本地测试地址。本项未触发页面中的真实系统 ANR 或死锁按钮。
- 在同一只读 API 35 模拟器单独点击受控主线程阻塞入口并再次发送输入，系统 `WindowManager` 和 `ActivityManager` 均记录输入分发超时 ANR；系统 Signal Catcher 完成 tombstoned 输出，SDK 日志分别记录保存 `SIGNAL_ANR` 与 `ANR_TRACE`。只检查报告元数据：前者含 Java 栈且 `fromProcessErrorState=false`，后者含 trace 原始字节。这属于模拟器的真实系统 ANR，不是手动 SIGQUIT；尚未核验物理设备，也不能证明 trace 完整。
- 模块 Debug/Release 构建、ANR 和 SDK JVM 测试、API 35 ANR 仪器测试及模块依赖边界检查通过。debugger 来源、跨版本矩阵和物理设备仍待执行，不以手动信号替代系统 ANR 验收。
