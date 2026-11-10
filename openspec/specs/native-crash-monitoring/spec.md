# native-crash-monitoring 规范

## Purpose

让 Android 应用的 Native 致命崩溃能够在符号文件上传与还原能力尚未提供时，可靠保存原进程身份、原始调用栈和完整的有界诊断，复用既有事件上传、统计和查询，并明确排除用户不需要的内存转储。

## Requirements

### Requirement: 监控 Native 致命崩溃并保存原始核心现场
SDK MUST 在受支持进程中监控 Native 致命信号，至少覆盖 SIGSEGV、SIGABRT、SIGBUS、SIGILL 和 SIGFPE，保存信号原因、原发生时间、原进程及崩溃线程身份和可取得的原始寄存器。系统 MUST 将主线程、Java 调用的 JNI 线程和纯 Native 工作线程的崩溃作为同一种事件能力处理。采集不得将应用恢复运行作为成功标准，也不得把采集器自身失败计成新的应用 Crash。

#### Scenario: 主线程 JNI 崩溃
- **WHEN** 主线程通过 JNI 发生 SIGSEGV
- **THEN** 记录原信号、故障地址、系统 TID、寄存器和可取得的调用栈，并能恢复为一个 Native fatal 事件

#### Scenario: 纯 Native 线程崩溃
- **WHEN** 没有 Java 线程身份的工作线程发生 SIGABRT
- **THEN** 保留 Native TID、信号与可安全取得的完整有界 abort message，不强制要求或伪造 Java ID 和 Throwable；消息不可安全保存时在首次落盘前省略

#### Scenario: 采集器失败
- **WHEN** 辅助采集进程或回调失败
- **THEN** 尽力保留已保存核心和安全原因，继续原崩溃处理，不额外增加一条应用崩溃

### Requirement: 上传明确的 Native 载荷并保留后续还原材料
Native 事件 MUST 使用现有公共事件信封、`eventType=crash`、`schemaVersion=2`、`crash.kind=native`、`fatal=true` 和独立 `nativeCrash` 载荷；MUST NOT 同时提交 JVM `throwableChain`。载荷 MUST 包含实际运行 ABI、信号/code、适用时的故障地址、寄存器采集结果、主 backtrace 及其模块字典，可附有状态的关联 Java 栈。每帧 MUST 保留顺序、有效时的模块引用和相对 PC、现场已有函数名及偏移；模块 MUST 保留可取得的 SO 名称/脱敏路径、ABI 和 ELF Build ID。

地址、寄存器和偏移 MUST 使用受架构位宽校验的十六进制字符串，不得经由浮点数丢失精度。模块引用 MUST 对应同一事件字典。应用 buildId 与模块 ELF Build ID MUST 分开表达；未知模块身份 MUST 明确缺失，不得按同名库猜测。

#### Scenario: 没有调试符号
- **WHEN** 模块没有可解析的函数名或行号
- **THEN** 仍上传原始地址、模块身份和真实缺失状态，不因缺少符号拒绝核心，不声称已还原

#### Scenario: 回溯失败
- **WHEN** 核心信号合法但主线程无法回溯
- **THEN** 接受带空帧和失败状态的最小事件，不生成虚假帧

#### Scenario: 64 位地址与零地址
- **WHEN** 事件含超过 JavaScript 安全整数范围的寄存器值和 `0x0` 故障地址
- **THEN** 存储、HTTP、网页和 MCP 保留精确字符串及合法零值，不将其舍入或解释为未知

#### Scenario: 混合或非法载荷
- **WHEN** Native 事件同时带异常链、包含悬空模块引用或非法地址
- **THEN** 返回可定位的永久字段错误，不持久化为合法 Crash

### Requirement: Native Crash 包含完整的有界诊断类别
Native Crash MUST 尽力采集并上传与 JVM Crash 对等的日志、Java 堆/Native 堆和进程内存用量、受限 proc 文本、FD、进程、设备和 Java 线程快照，并额外提供其他 Native 线程及 backtrace。各分区 MUST 独立表达 `ok/partial/unavailable/skipped`、安全原因、原采集时间及截断；失败或权限不足不能被表示为成功空结果。

内存 MUST 包含可取得的 Java heap used/max/PSS、Native heap allocated/PSS、总 PSS、RSS、VSS、Swap，以字节为单位；文本仅限有界脱敏的 meminfo/status/limits。日志 MUST 分开表达当前进程可读的 main/system/events。FD MUST 区分可知总数、保存条目和安全目标类别。进程与设备 MUST 保留原 PID、名称、可知前后台和启动时间、SDK 初始化时间、API level、ABI、厂商、品牌及构建指纹，未知值不猜测。

#### Scenario: 完整诊断
- **WHEN** Native Crash 的系统及 Java 诊断均可取得
- **THEN** 事件可分别查看 Native/Java 线程、三个日志缓冲区、各项内存、FD 和进程设备环境，仍只计一个 Crash

#### Scenario: Java 回调不可用
- **WHEN** Native 核心已保存但 Java 线程或 Runtime 堆采集失败
- **THEN** 已取得的 Native 栈、日志、proc 和 FD 仍保留，Java 分区明确失败或跳过，不丢弃核心

#### Scenario: 原进程与新进程不同
- **WHEN** 下一次启动恢复旧 Crash
- **THEN** 上传旧现场或其失败状态，不用新进程的堆、日志、PID 或前后台状态填充旧事件

### Requirement: 原生线程和 Java 线程身份不得混淆
其他 Native 线程 MUST 按系统 TID 区分并排除崩溃线程，Java 线程 MUST 按 Java ID 区分；名称相同不得合并。Native 事件的 Java 线程快照无法可靠关联到崩溃线程时 MUST 标记关系未知，不得按线程名归并或排除。单独的主 Java 栈 MUST 仅在可靠关联时发布；Java ID、系统 TID、PID 与 UUID processId MUST 分开表达。

系统 MUST 优先保留可取得的主线程信息，记录已知观测/遗漏数、每线程回溯状态及截断；空栈表示实际未取得帧，不保证线程没有运行。不同线程的快照不得宣称原子一致。

#### Scenario: 同名线程
- **WHEN** 两个 Native 线程同名，两个 Java 线程也同名
- **THEN** 分别按 TID 和 Java ID 保存与展示，不能合并成一个线程

#### Scenario: Java 关联未知
- **WHEN** 无法证明某 Java 线程与崩溃 TID 的对应关系
- **THEN** 该 Java 快照保留关系未知，主 Java 栈标记不可用，不把回调线程当作崩溃线程

### Requirement: 三类内存转储从生成端排除
SDK MUST NOT 生成、落盘或上传原始栈内存 `stack`、寄存器附近内存 `memory near` 或完整虚拟内存映射 `memory map` 转储。该约束 MUST 覆盖崩溃线程、其他线程、降级、采集器错误和重启恢复路径；不得先保存完整 tombstone 再过滤，不得通过日志、通用属性、proc 文本或附件绕过。寄存器数值、backtrace、内存用量和允许的摘要仍属于采集范围；回溯必要的临时读取不生成转储。

服务端 MUST 使用明确的 Native 字段白名单，拒绝对应转储字段或完整 tombstone 文本，不得静默丢弃后确认 accepted。

#### Scenario: 正常与降级报告
- **WHEN** 分别执行正常回溯、其他线程采集和故障降级
- **THEN** 本地暂存、冻结队列、请求和服务端详情均无三类转储，调用栈、寄存器及内存统计仍可存在

#### Scenario: 绕过 SDK 提交转储
- **WHEN** 请求向 Native 载荷或诊断塞入上述转储字段或完整 tombstone
- **THEN** 服务端返回永久错误，不过滤后伪报完整接收

### Requirement: 核心先保存且恢复上传保持幂等
SDK MUST 在昂贵辅助采集前保存固定事件身份和最小 Native 核心，并且在不依赖 Java 回调成功的情况下可恢复。补充完成或中断后 MUST 只形成一份冻结事件，复用现有上传、响应校验和重试语义；上传不得读取未冻结记录。下一次启动 MUST 在安排上传前导入旧记录，保留 eventId、发生时间、构建、sessionId、UUID processId、原 PID/TID 和已采集内容。

采集、子进程和回调等待 MUST 有界，停止后迟到结果不得改写已冻结记录。正常采集先于网络上传，本期 Native 崩溃处理中 MUST NOT 联网。暂存损坏或导入中断 MUST 不造成原事件重复确认、静默丢失已提交核心或无限重试损坏内容。

#### Scenario: 诊断中途终止
- **WHEN** 核心已提交而辅助采集尚未完成，进程退出
- **THEN** 重启可上传原核心及已完成分区，未完成分区标记 interrupted

#### Scenario: 导入后崩溃或确认丢失
- **WHEN** 暂存已进入队列但清理前再次退出，或服务端写入后响应丢失
- **THEN** 恢复和重试继续使用同一 eventId、同一内容，只形成一个逻辑事件

#### Scenario: 超时与迟到写入
- **WHEN** dumper 或 Java 补充超时后才返回
- **THEN** 结束等待并回收本次资源，冻结结果不再被覆盖，不阻断后续崩溃处理

#### Scenario: 未崩溃进程退出
- **WHEN** 预留 Native 暂存的原进程已退出，且核心及辅助材料均为空
- **THEN** 回收该空预留及可安全识别的旧空隔离记录，不删除任何非空核心或仍属存活进程的材料

#### Scenario: 暂存读取暂时失败
- **WHEN** 已提交核心的身份或材料因瞬时 I/O 故障无法读取
- **THEN** 保留原暂存，不误判为损坏，并在同一进程后续后台上传前重试恢复

### Requirement: Native 诊断遵守隐私与资源上限
SDK MUST 在首次持久化前对文本执行必要脱敏，服务端存储前再次校验和脱敏；凭据、邮箱、手机号、URL 及私有路径不得进入测试约定的本地记录、请求或日志。结构化地址、构建身份和内存数值不得误脱敏。成功和失败日志 MUST 只包含安全阶段、原因、计数、字节及耗时。

服务端单事件 MUST 不超过 256 KiB，客户端 MUST 不超过 240 KiB 且可装入实际批次；共享诊断 MUST 不超过 128 KiB。Native 主帧和模块 MUST 分别不超过 128；其他 Native/Java 线程 MUST 合计不超过 64、每线程不超过 32 帧、合计不超过 48 KiB。日志 main/system/events MUST 分别不超过 200/50/50 行、每行编码后不超过 2 KiB、合计不超过 48 KiB；内存、FD、基础元数据 MUST 分别不超过 16/12/4 KiB，FD 不超过 128 条。字节 MUST 按完整转义后 UTF-8 JSON 计算；源端裁剪必须标记并优先保留核心，服务端超限必须明确拒绝。

#### Scenario: 中文日志与多线程超限
- **WHEN** Native 与 Java 线程、中文日志使完整事件超过预算
- **THEN** 客户端有标记地裁剪可选诊断并复核实际字节，保留核心和有效模块引用；服务端拒绝绕过 SDK 的超限输入

#### Scenario: 敏感内容与合法数值
- **WHEN** 原生文本含测试敏感值且寄存器含长十六进制地址
- **THEN** 首次本地记录起不保留敏感原值，寄存器仍精确保存，日志不包含原始诊断

#### Scenario: 凭据和带标签的内存转储
- **WHEN** 合成日志包含 App Key 请求头、独立 APM 凭据或带日志标签的原始栈内存行
- **THEN** 客户端首次落盘前过滤这些已识别内容，服务端对已识别凭据再次脱敏并拒绝可识别的转储行

### Requirement: 服务端可靠接收 Native 并复用内容冲突保护
Native 接收 MUST 复用 App Key 与包名鉴权、事件级校验和部分接受；只有核心、主辅栈、诊断及其一致摘要已可靠保存才确认 accepted。系统 MUST 以 appId+eventId 保持幂等；同定位但主栈、模块或诊断不同 MUST 返回 `CRASH_CONTENT_CONFLICT`，不得拼接或覆盖首次材料。存储暂不可用 MUST 保持现有可重试语义，部分写入重试必须修复缺失侧后才确认。

#### Scenario: 混合批次
- **WHEN** 同一应用批次包含合法 JVM、合法 Native 和一条事件级非法 Native
- **THEN** 按原部分接受规则确认合法事件并定位错误，不把合法 Native 统一判为不支持

#### Scenario: 部分写入后改变模块
- **WHEN** 事实或状态仅一侧保存，同 eventId 重试却改变模块 Build ID 或诊断
- **THEN** 返回永久内容冲突并保留原材料；原内容重试可修复缺失侧且只计一次

#### Scenario: 跨应用访问
- **WHEN** 上报包名不匹配 App Key，或无权限用户请求 Native 详情
- **THEN** 遵守现有整批包名拒绝和资源隐藏规则，不泄露其他应用现场

### Requirement: Native 原始归组与统计保持当前精确口径
Native MUST 使用独立版本的临时指纹，至少绑定应用、应用 buildId、运行 ABI、信号/code 和有序主帧的模块身份及相对 PC；无可靠帧时 MUST 使用明确的现场不足类别。绝对加载地址、故障地址、寄存器、日志、辅助线程、Java 补充栈和现场函数显示名 MUST NOT 参与指纹。未还原事件不得承诺跨构建正式归组。

所有 Crash 查询 MUST 在数据库侧按既有应用、时间和维度范围纳入 JVM 与 Native fatal，复用同一 app_start 分母和精确去重；原事件重传不增加事件、崩溃会话或设备数，诊断不得成为统计事件。列表和聚合 MUST NOT 读取完整栈或诊断，MUST 保持返回数量和资源预算。

#### Scenario: ASLR 与诊断不同
- **WHEN** 同构建、同 ABI 和同主栈的两次 Native 崩溃仅加载地址、日志或其他线程不同
- **THEN** 归入同一 Native 临时问题，各自现场独立保存

#### Scenario: 混合会话固定集
- **WHEN** 固定集包含 JVM/Native、重复重试、同会话多个 Crash 以及没有 app_start 的窗口
- **THEN** 各查询精确匹配人工期望，崩溃会话去重，缺少分母仍不可计算，后台 JVM 还原不改变总数

#### Scenario: 大诊断下的摘要查询
- **WHEN** 有大量 Native 大诊断事件而只查询有限时间窗的概览或列表
- **THEN** 数据库只读取必要轻量投影并执行有界过滤/聚合/分页，不在应用内加载全量现场
