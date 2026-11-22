# Android ANR 客户端采集任务

以三个增量规格的场景为验收单位。完成状态以任务勾选及验证证据为准。

归档保留项：宿主正常运行不主动调用 `printTrace()`，预期短间隔重复触发 ANR 的概率极低。按用户决定接受连续 trace 错配为本期已知限制，5.1、5.6 保留复现结果和未完成状态。7.4、7.5 保留设备与故障验收缺口，详见[验证记录](verification.md)。

方法和变量补齐标准注释，成功/失败日志仅含安全阶段、计数、字节和耗时。知识库及配套文档在归档前通过 knowledge-sync 同步，不在普通实现中自动更新。

## 1. 模块与原生依赖

- [x] 1.1 新增 `performance-anr` Library、JNI/CMake、consumer rules、来源及许可证，加入 settings 和统一版本发布；用 `bash android/gradlew -p android :performance-anr:assembleRelease` 核验 AAR 的 arm64 JNI、许可证及规则。
- [x] 1.2 接入 ByteHook 1.1.1、ShadowHook 2.0.0 并统一与 Rhea 的最终解析结果；通过 dependencyInsight、Release APK SO 清单及初始化冒烟证明无不兼容重复版本，在变更验证材料记录具体版本和结果。
- [x] 1.3 提供 SDK 自有 ANR 配置、状态、报告与句柄契约以及平台判断；同组加入 JVM 配置/平台测试和 JNI 按需加载仪器测试，验证默认关闭、主进程/API 23+/arm64 条件和不支持时仍可读取报告。
- [x] 1.4 将 Android 和独立消费者 compileSdk 恢复至 36，恢复原 AGP/Gradle/Kotlin 构建基线，保持 targetSdk/minSdk；核验 ByteHook/ShadowHook 依赖，使用 Kotlin 1.9 独立消费者编译和设备初始化证明兼容，完整构建及模块测试通过。

## 2. 信号接收、转交与所有权

- [x] 2.1 迁移 Matrix `SignalHandler` 的信号安装、旧处理器、备用栈和 handler 逻辑，保留互斥锁与线程处理方式；通过源码对照与设备测试核验迁移顺序及真实启动/关闭状态。
- [x] 2.2 迁移外部/自发 SIGQUIT 来源判断、Signal Catcher 查找、Java 采集等待、trace hook 和转交顺序；在设备对照手动/外部信号及系统输出，记录未能观察的行为。
- [x] 2.3 迁移 debugger 来源、Java 回调、旧处理器恢复、重新排队、两秒等待和重新安装的原顺序；用隔离设备对照来源和信号处理，不以自拟管道或重试逻辑替代。
- [x] 2.4 将原启动与关闭接入 SDK，按 Matrix 实际行为核验重复启停、初始化失败和与 Native Crash 共存；如存在原实现限制则明确返回或记录，不伪造可用状态。

## 3. 按调用方截取 ART trace

- [x] 3.1 用 ByteHook 自动模式替换原 xHook/grouped 操作，保持 Matrix 的 API 与调用方 SO 选择和安装时机；测试各版本分支及安装状态，确认产物不携带 xHook 或 Matrix 运行时。
- [x] 3.2 原样迁移目标 connect/open 命中后的线程标记、手动/ANR 文件路径和首次同线程 write 命中判断；单元与设备测试对照原实现，不增加 recvmsg 或输出 FD 筛选。
- [x] 3.3 保留 Matrix 在原 write 调用前按字符串复制首段、触发对应回调再调用原函数的次序；使用安全终止的测试样本比较结果，并如实记录非 NUL、分段、其他写入和原函数失败的原有风险。
- [x] 3.4 按 ByteHook API 管理原函数链及必要 hook 资源，保持 Matrix 标记与原可观察行为；验证重复信号/关闭时行为，并记录 ByteHook 与原 xHook 不可避免的 API 适配差异。

## 4. 候选、确认和线程证据

- [x] 4.1 原样迁移 `onANRDumped` 的 Java 线程、20 秒 latch、静态字段与回调时间顺序，适配 SDK 所需接口；对照测试执行先后、超时和实际来源，不引入新的会话合并策略。
- [x] 4.2 迁移主线程 Java 栈、Matrix 前后台判断、消息和 cgroup；测试字段与源码一致，失败按原分支处理，debugger Java 栈不伪标为新采集的 Native 栈。
- [x] 4.3 原样迁移前台 2 秒/后台 10 秒消息阈值、立即回调、500ms/40 次错误状态检查和 20 秒 debugger 去重；对照测试进程/UID、反射失败及未命中时没有回调。
- [x] 4.4 迁移 Matrix `SimpleDeadLockDetector` 及其 nativePollOnce 判断；同一批锁环、无等待主线程、EOF、格式变化样本对比原版输出，不改写原解析行为。

## 5. 本地事务、脱敏、读取和回调

- [ ] 5.1 在 Matrix 结果回调后生成 SDK 本地报告及原子索引，保存已实际取得的结果与原进程身份；通过写入故障和重启测试验证已提交报告可读取、未提交结果不伪称成功。
- [x] 5.2 在应用私有 noBackup 目录保存 Matrix 采集结果，常规日志只记录安全阶段；验证不把完整 trace 或用户数据输出到 SDK 日志，同时不改变 Matrix 实际捕获内容。
- [x] 5.3 实现重启读取、损坏记录与 I/O 失败区分和非法 ID/引用拒绝；测试验证原身份保留、不补采新现场、路径穿越/外部引用拒绝及有效报告不被误删。
- [x] 5.4 实现每页 1–20 条稳定摘要查询、详情按需读取和显式删除；加入分页边界、并发清理、缺失/损坏/不可读状态测试，验证列表不加载所有 trace。
- [x] 5.5 实现已完成报告的容量及过期清理，不改写 Matrix 活动采集文件；测试过期、空间不足、活动文件保护及不删除其他功能目录。
- [ ] 5.6 实现结果保存完成后的 SDK 回调并隔离回调异常；测试保存失败不伪报成功、已保存报告不因关闭丢失，记录进程退出前未完成时的边界。

## 6. SDK 装配与示例

- [x] 6.1 接入全量 `PerformanceConfig`/`PerformanceSdk` 和按需组件入口，复用公共身份且 ANR 无需网络参数；扩展现有 SDK 集成测试，覆盖默认关闭、初始化失败不拖垮其他能力、重复/冲突配置、借用资源和逆序关闭。
- [x] 6.2 新增独立 ANR 示例页面，提供系统输入 ANR/主线程死锁受控触发、manual trace、报告列表/详情/删除和回调观察；仪器测试或设备操作证明能从主页面到达并读到正确标记的本地报告。
- [x] 6.3 验证采集、恢复、回调与全量 flush 无 ANR 网络请求，独立消费者无 App Key 仍工作；使用请求捕获/模拟传输测试断言无既有 Crash/Jank 队列项或 ANR HTTP 请求。

## 7. 分发与跨能力集成验收

- [x] 7.1 新增仅 ANR 的独立 Maven Local 消费者并使用本轮唯一版本解析，运行 Debug/Release 混淆构建及初始化/读取冒烟；检查 AAR、POM、module metadata、JNI 和 consumer rules，无源码替换、NDK/CMake 或宿主宽泛 keep 补救。
- [x] 7.2 回归仅 Crash/仅 Metrics/全量消费者；检查解析图、APK 和合并清单，确认精简产物不新增 ANR/ByteHook/ShadowHook，组合产物共享库版本唯一且 ANR、Jank、Native Crash 均可运行并关闭。
- [x] 7.3 完成首期 arm64 设备版本矩阵：API 23、24/25、26、27/28、29、30+ 至当前 compileSdk 36 的代表设备覆盖各 hook 分支；API 21/22 及非 arm64 验证安全降级，每项记录实际版本、来源及结果，未运行项保持未完成。
- [ ] 7.4 在可用物理设备完成真实系统 ANR、主线程死锁、手动 SIGQUIT/manual、外部 debugger 信号及非 ANR 信号对照；检查系统 trace 继续、本地确认依据和线程证据，不以信号注入替代系统 ANR。
- [ ] 7.5 完成设备故障联合验收：Java 采样挂起、连续信号、磁盘失败、采集中杀进程与重启读取、第三方 handler 接管和重复启停；逐项对照 Matrix 原行为并记录局限，不能将未实现的增强当作通过。
- [x] 7.6 执行 ANR/SDK 相关 JVM 测试、Release 构建和仪器回归，建立全部增量规格场景到测试或人工证据的映射；运行 `openspec validate add-android-anr-collection --strict` 与 `git diff --check`，如有缺口保留未完成任务，报告主机/模拟器/真机边界。

## 工作流后续

- 实现和验收完成后进入 verify；未验证平台、信号来源和采集完整性必须明示，不以任务全勾替代场景覆盖。
- 用户发起归档时，完成归档原检查及选定规格同步后、移动目录前按根 config 调用并等待 knowledge-sync；同步 Android 概览/模块/生命周期/配置/示例/测试/待验证主题及受影响平台状态，逐类说明 API、客户端、后端、前端是否受影响，校验未完成则停止归档。
