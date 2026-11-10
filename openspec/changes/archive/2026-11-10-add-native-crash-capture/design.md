# Native Crash 采集上传设计

## 当前背景

动机与范围见 [提案](proposal.md)。本变更跨 Android、后端、网页和 MCP，涉及信号上下文、可靠保存和严格载荷联合类型，需要设计文档。

已核对的实现边界：

- `performance-crash` 目前没有原生依赖；`CrashReporter` 在上传前调用 `FileCrashQueue.recoverCaptures()`，已有核心先保存、诊断后冻结及内容冲突保护。
- 后端 `CrashEventValidator` 只接受 `jvm`，`CrashState` 的未还原材料只允许 `raw_chain`；当前 ClickHouse 事实已保存 `crash_kind`，主栈和辅助栈使用通用字符串载荷。后台候选 SQL 已过滤 `crash_kind='jvm'`，但接收通知及内存实现仍需逐层限定。
- 网页和 MCP 的严格解码只识别 JVM 原始链/还原文本；分析任务服务明确只允许 JVM fatal，不能因详情支持 Native 而开放任务。
- 只读参考：外部 xCrash 检出的提交为 `86dea4fa0cdbae68b63e9b34436fa20b51244d11`。`xcc_signal.c` 注册信号，`xc_crash.c` 启动 dumper，`xcd_process.c` 读取线程并输出报告。`stack`、`memory near` 为直接调用，完整 maps 可开关关闭；其他线程另有 `stack` 输出。
- 用户样例来自 2019 年 Android 10，原文 738,378 字节，仅作格式参考，不证明当前版本兼容，也不把重新构建的 SO 当成其匹配符号。

## 设计目标与非目标

- 在不依赖 Java 回调成功的情况下，尽力保存最小 Native 核心；共享诊断均在本次崩溃生命周期内采集并带质量信息。
- 复用现有模块、鉴权、队列、事实/状态和查询边界。诊断完整接入，不建立第二套 Crash 系统。
- 本期只发布原始证据。SO 的 ELF Build ID、模块归属、相对 PC 必须可追溯，不增加符号管理接口或虚假的还原来源。
- 不承诺 SIGKILL、LMK 或任意堆/虚拟机损坏都能留下报告；不把 ANR、非致命信号或 SDK 自身 dumper 故障计成应用的额外 Crash。

## 关键决策

### 1. 在 Crash 模块内引入受控的 Native 采集实现

将 xCrash 必要的信号捕获、独立 dumper、ELF/栈展开代码作为受控第三方源码引入 `android/performance-crash/src/main/cpp/`，增加最小 Kotlin/JNI 桥接。保留许可证和来源提交，新增/修改的自有方法、变量补充标准注释和安全日志。构建不能依赖外部检出路径。

不直接依赖未经修改的 xCrash AAR：当前参考版本无法通过公开开关禁用全部禁止的转储；同时启用其 Java/ANR handler 会与现有功能重叠。不重新从零实现 ELF unwinder，也不让 Crash 依赖 `performance-native-tools`、Rhea、KOOM 或 ShadowHook。

配置采用 `CrashConfig.nativeEnabled=true` 的设计默认值：`enabled=false` 关闭整个 Crash 能力，`nativeEnabled=false` 只关闭新的信号采集，JVM 和已有队列恢复继续运行。受支持设备随现有 Crash 默认入口启用；加载、打包或初始化失败时记录安全原因并保留 JVM 功能，不让应用启动失败。此默认值为提案选择，不是现有已实现行为。

Crash 自有 AAR 构建 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，保持现有 API 21 下限；不把其他模块当前的 arm64 限制扩散到 Crash 精简消费者。当前示例应用的既有 Native 工具 ABI 不在本变更中扩大。

dumper 使用安装时解压的原生库目录中的受控可执行产物，不把可执行文件复制到应用可写目录后运行。AAR 同时交付 JNI 和 dumper；示例与独立消费者明确配置所需的原生库解压/打包方式，不通过 AAR 隐式覆盖宿主网络策略。使用现代 NDK、PIE 和目标页大小要求验证产物；仅编译通过不能证明 Android 10+ 可执行路径、ptrace 或新系统页大小可用。

初始化保持进程级单例和所有权：初始化前准备上下文、文件、缓冲区和工作者；重复初始化不重复注册；关闭只恢复自己仍持有的 handler，不覆盖后来注册的第三方 handler，释放自己的 FD/线程。参考实现缺少的关闭路径需要补齐，不宣称未经验证的多 SDK 信号链兼容。

### 2. 最小核心先保存，诊断和上传脱离信号上下文

```text
Init --> Cached identity + prepared file/buffers
                           |
Signal --> Persist core --> Dumper --> Optional Java diagnostics
                |                         |
                +-------------------------+
                           |
                     Frozen capture
                           |
Next launch --> Import to existing queue --> Existing uploader
```

初始化时为下一次 Native fatal 准备固定 eventId 和不含 App Key 的原进程上下文，包含构建、版本、会话、UUID processId、PID 和已知设备信息。崩溃入口保存信号、时间、TID 与原始 `ucontext` 寄存器；核心必须在启动昂贵诊断之前形成可恢复提交。

Native 落盘使用带版本、长度和完成校验的有限记录，核心和后续分区分别提交。启动恢复只接受完整提交的记录；尾部写到一半不能损坏已有核心。该文件是已有队列之前的采集暂存，不拥有第二套上传或重试策略。每份最多 512 KiB，最多 32 份且合计最多 16 MiB；正常初始化时管理额度，不能在 signal handler 内遍历清空其他队列项。空间耗尽如实失败，不能假报已保存。

信号上下文不调用现有 JVM 采集器、Gson、OkHttp、共享队列监视器或普通日志框架。逐处审查参考代码中的锁、fork/clone 后执行、回调等待及 fallback，使用预准备的有限内存和信号安全操作；保留原处理器/系统终止流程，不尝试继续运行出错指令。不要把备用信号栈描述成所有线程天然拥有的保证，按实际注册线程范围验证。

dumper 读取崩溃线程保存的寄存器，其他线程通过系统 TID 枚举、暂停、读取并回溯；线程暂停不承诺全局原子快照。回溯必要读取不等于输出内存转储。子进程与回调等待必须有截止时间，初始总等待预算为 8 秒，其中 dumper 最多 5 秒、Java 补充最多 2 秒且受剩余预算限制；必须取消/回收本次子进程、恢复线程并拒绝迟到写入。预算限制等待，不承诺异常内核 I/O 的绝对终止时延。

Java 工作在线程恢复后、普通回调上下文中尽力补充。Native 主报告不能依赖 Java 回调成功才有效。补充结束、超时或中断后冻结一次；本期不在 Native 崩溃期间联网。下次启动先恢复旧采集，再安排任何周期上传，始终使用原身份和原现场，不读取新进程来补旧诊断。即使 `nativeEnabled=false`，已存在的 Native 暂存仍要恢复。

### 3. 接收使用当前 v2 信封与互斥载荷

复用 `POST /ingest/v1/batches`、`X-App-Key`、`requestId`、部分接受和现有响应校验，不新增上传端点。公共字段不变；`crash.kind` 决定载荷：

- JVM：`kind=jvm`、`fatal=true`、现有 `throwableChain` 和可选 `diagnostics`。
- Native：`kind=native`、`fatal=true`、`nativeCrash` 和可选 `diagnostics`，不伪造 `Throwable`，不同时提交异常链。

`nativeCrash` 的字段约定：

| 字段 | 内容 |
|---|---|
| `abi` | 本次实际运行架构，统一 Android ABI 名称 |
| `signal` | 信号编号/名称、code 编号/名称；适用时的 `faultAddress`；受限脱敏 `abortMessage` |
| `registers` | 带采集状态的架构寄存器集合，保留 PC/SP/LR 等数值 |
| `modules` | 本事件主辅 Native 帧涉及的有限模块字典：事件内 ID、SO 名称/脱敏路径、ABI、可取得的 ELF Build ID |
| `backtrace` | 状态、时间、截断与帧；每帧包含序号、可空模块引用、有效时的相对 PC、现场已有函数名与偏移 |
| `javaStack` | 能够可靠关联到崩溃线程时的 Java 帧及采集状态；无法关联明确不可用 |

地址、寄存器和偏移以受架构位宽校验的规范十六进制字符串传输，避免 JavaScript 的 64 位整数精度丢失；故障地址 `0x0` 是有效零值，不能当成未知。模块信息不可取得时保留缺失状态，禁止按同名 SO 猜 Build ID。模块引用必须存在于同一事件字典；字典只收集帧涉及模块，不上传完整映射。

允许核心合法、回溯失败且帧为空的事件：保留信号/寄存器和失败原因，不能因 dumper 失败丢掉最小记录，也不能合成虚假帧。现场已有函数名不表示经过符号还原。

### 4. 完整诊断与两种线程身份

| 分区 | Native 事件上传内容 |
|---|---|
| 公共信封 | eventId、原发生时间、sessionId、UUID processId、匿名设备 ID、包名、版本、versionCode、应用 buildId、环境、渠道、系统、设备型号、可知网络类型 |
| `crashedThread` | 系统 TID、名称、主线程标记；可靠取得时补 Java ID、状态、优先级、daemon |
| `nativeThreads`（新增） | 崩溃线程之外的 Native 线程，TID、名称、可知主线程标记及各自原始 backtrace、状态和截断；按 TID 排除崩溃线程 |
| `otherThreads` | 现有 Java 线程快照及 Java 帧、Java ID、名称、状态、优先级、daemon、主线程标记；Native 事件无法可靠关联时保留关系未知，不按名称合并或误删 |
| `logcat` | 当前进程可读取的 main/system/events，独立状态和已脱敏行 |
| `memory` | Java heap used/max/PSS、Native heap allocated/PSS、总 PSS、RSS/VSS/Swap，统一字节；受限脱敏的系统 meminfo、原进程 status/limits |
| `openFiles` | 可知 FD 总数、有限 FD 编号与安全目标类别 |
| `process` | 原 PID、名称、可知前后台、可靠启动时间及 SDK 初始化时间 |
| `device` | API level、支持 ABI、厂商、品牌、系统构建指纹 |

所有分区保留 `status/reason/truncated/capturedAt`；Native 线程各自还有回溯状态。对 Native 事件，`otherThreads` 表示 Java 线程快照，条目的 `relationToCrash=other|unknown` 区分可靠排除与无法关联；主 Java 栈只有可靠关联才单独填入 `javaStack`。有可靠同一线程身份时可附 systemTid；Java ID、TID、PID 和 UUID processId 始终分开。纯 Native 线程没有 Java ID 是正常缺失，不能编造。

原生诊断和 Java 补充不重复覆盖同一分区：日志、FD、proc 用原进程 dumper 数据；Runtime/Debug 堆值及 Java 线程用 Java 回调补充。每个分区保留实际采集时间和来源阶段；回调不可用时这些字段明确 unavailable，不用零或初始化时缓存的堆值冒充崩溃现场。

### 5. 禁止转储、隐私和预算

从 dumper 正常、其他线程、fallback 和自身错误输出中移除三类转储的生成入口；不先生成完整 tombstone 再删除章节，不以压缩包、日志、proc 文本或通用 attributes 携带它们。服务端 Native 载荷采用字段白名单，拒绝对应转储字段或完整 tombstone 文本，不静默过滤后 accepted。允许的寄存器数值、backtrace 和 meminfo/status/limits 与三类内存转储分开定义。

文本在首次落盘前完成脱敏，Native dumper 也执行与 Java 相同的必要规则；无法安全处理的可选文本省略并标记原因。服务端再做结构校验与脱敏并复核大小。不得以私有目录作为存明文现场的豁免。测试使用固定凭据、邮箱、手机号、URL 和私有路径验证原生暂存、最终队列、请求及日志都不泄漏；地址和构建身份不套自由文本替换规则。

默认预算：

| 对象 | 上限 |
|---|---|
| 单事件 | 服务端 256 KiB；客户端 240 KiB，并满足实际批次上限 |
| 共享诊断 | 128 KiB，包含新增 Native 线程 |
| 主 Native 栈/模块 | 主帧最多 128、模块最多 128；优先主栈引用，不能留下悬空模块 ID |
| 其他线程 | Native 与 Java 合计最多 64，分别保留观测/遗漏数；单线程最多 32 帧；两类总计 48 KiB |
| 日志 | main/system/events 为 200/50/50 行，合计 48 KiB，每行编码后最多 2 KiB |
| 内存/FD/基础元数据 | 分别 16/12/4 KiB；FD 最多 128 条 |

全部按转义后 UTF-8 JSON 字节计费。主核心优先，其次可取得的主线程，其他诊断按预算裁剪并标记。JVM 原有 64 条线程等预算保持；Native 引入不能让两类线程各自再使用完整 48 KiB。服务端不静默裁剪已提交内容。

### 6. 存储、指纹与后台隔离

复用事实和状态：Native 初始 `status=pending`、`reason=native_symbolication_deferred`、`stack_format=raw_native`、`grouping_kind=temporary`，来源 ID/revision/hash/processedAt 均为空。本期该原因表示尚未提供 Native 还原能力，不代表已进入队列或等待 JVM mapping。

详情主 `stack.content` 是不含 diagnostics 的 `nativeCrash` 对象；Native 辅助帧在同版本辅助载荷中保存，非栈诊断仍归不可变事实。Java 辅助快照只保存原始帧。现有 JVM 仍使用 `raw_chain/retrace_text`，不能退回旧表或新增双份栈字段。

指纹使用独立 `native-tmp-v1`：应用、包名、应用 buildId、运行 ABI、信号/code，以及按顺序最多前 8 个主帧的模块 ELF Build ID（未知时用模块名）和相对 PC，采用确定的长度前缀编码。不使用绝对加载地址、fault address、寄存器、日志、其他线程、函数显示名或 Java 辅助栈；避免 ASLR、敏感消息及诊断变化拆组。无有效帧时使用独立的“现场不足”临时类别，不伪造精确归因，不与完整回溯的 Issue 合并。不承诺跨构建归组。

沿用 `appId+eventId` 幂等及完整采集内容摘要；发生时间/构建定位和内容冲突策略必须覆盖 Native 的主栈、模块与全部诊断。事实、主栈、辅助栈和摘要可靠保存后才确认 accepted；部分写入恢复不能重置已确认内容。

Native 接收不查询 R8 mapping、不唤醒 JVM 工作；ClickHouse 与内存候选均排除 Native，后台对账、辅助栈和发布路径增加类型防线。同构建上传/替换/删除 JVM mapping 不改 Native 状态、指纹或 Java 辅助栈。

所有 Crash 查询在数据库侧纳入两类 fatal 事件，使用同一个 app_start 分母，按原发生时间计数。同一 session 多个事件只算一个崩溃会话；原始内容和大诊断不进入列表投影。保留当前时间窗、分页、资源预算和 TTL；暂不新增 kind 筛选器或按种类分母。使用小型混合固定集验证口径，SQL 计划/读取投影验证 Native 负载不扩大列表查询边界。

### 7. 网页、Agent HTTP、MCP 和分析边界

原 Crash 导航改为能涵盖两类事件的名称；详情显示 crashKind，按 `raw_native` 渲染信号、寄存器、模块字典和主 backtrace。诊断面板提供 Native/Java 线程分组，Native 按 TID、Java 按 Java ID 选择；关系未知明确显示，不按同名线程合并。共享日志、内存、FD 和环境组件继续使用。

Native 文案显示“原始 Native 堆栈；本期未提供符号还原”，不出现上传 mapping、后台即将还原或已还原来源。当前分析任务继续只允许 JVM fatal；网页隐藏 Native 创建入口，服务端保留 `UNSUPPORTED_ANALYSIS_EVENT`，不把本期扩大成 Native Agent 修复平台。

Agent HTTP 复用同一详情，不在查询中解码设备现场或调用还原。MCP 扩展当前三态校验中的 Native 联合分支及 Native 诊断，仍通过既有 `stack/diagnostics` section、UTF-8 JSON 片段和 contentDigest 续读，工具名、鉴权、返回总预算不变；不添加文件下载或写工具。

## 风险与取舍

- 信号上下文、堆损坏、ptrace 限制、Java 回调阻塞：核心先保存，分区独立降级，子进程有界等待；通过故障注入证明受控边界，不承诺所有损坏场景。
- 参考代码较旧、可执行 dumper 的安装路径与系统限制：使用现代工具链重新构建，独立消费者验证 APK 内容和安装后真实执行；不可把复制到可写目录当回退。
- 去掉原始内存转储会减少手工内存分析材料：保留信号、寄存器、回溯和内存用量；不偷偷增加附件弥补用户明确排除的内容。
- 第三方源码维护与命名冲突：保留许可证/来源，使用本 SDK 自有库名和 JNI 包，记录必要裁剪，不导入 Java/ANR 双重 handler。
- 未提供符号还原时的归组局限：按构建隔离并显示临时类别，不把函数显示名当精确行号；保留未来关联所需材料。

## 验证与交付顺序

1. 后端/网页/MCP 先支持原始 Native 联合类型并完成混合固定集与隔离 HTTP 验证；不更新正式环境或清空已有数据。
2. Android 完成原生暂存、回溯、禁止转储、共享诊断、预算与恢复，再连接既有 uploader。
3. 在示例和独立 Crash 消费者覆盖主线程 JNI SIGSEGV、命名后台 Native SIGSEGV、SIGABRT、无 Java 关联线程、dumper 失败、采集中断、断网、确认丢失、同名线程、超限和敏感文本。
4. 验证四 ABI AAR/APK 内容；API 21 做最低运行冒烟，API 29/35 做真实信号到 HTTP/ClickHouse/网页/MCP 的有限闭环，并在 API 36 做打包、加载与崩溃冒烟。宿主不能运行的 ABI 和真机边界明确未验证，不用模拟器外推 OEM 兼容。
5. 部署时先升级读取 Native 的服务端/网页/MCP，再升级采集 SDK。回退采集可关闭 nativeEnabled；已保存 Native 不删除，读取端保留新契约，旧服务器不能接收的永久错误不被客户端无限重试。云端发布和 Maven Central 发布另行授权。

文档归属见任务清单。只有已执行证据才能更新当前状态页；可复用验收集中在既有 validation 分类并建立唯一入口，不记录日常执行流水。
