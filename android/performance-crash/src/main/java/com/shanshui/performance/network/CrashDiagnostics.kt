package com.shanshui.performance.network

/** JVM 崩溃发生时的可选诊断现场，各分区分别表达采集状态。 */
data class CrashDiagnostics(
    /** 核心异常因事件预算被裁剪时的说明。 */ val coreTruncation: CoreTruncation? = null,
    /** 未捕获异常处理器收到的线程。 */ val crashedThread: CrashedThread? = null,
    /** 崩溃处理期间其他 JVM 线程的近似快照。 */ val otherThreads: OtherThreads? = null,
    /** 当前进程可读取的日志缓冲区。 */ val logcat: CrashLogcat? = null,
    /** 进程内存快照。 */ val memory: CrashMemory? = null,
    /** 当前进程文件描述符。 */ val openFiles: OpenFiles? = null,
    /** Android 数值进程与生命周期信息。 */ val process: CrashProcess? = null,
    /** 设备和系统构建信息。 */ val device: CrashDevice? = null,
)

/** 记录主异常因本地字节预算发生的裁剪。 */
data class CoreTruncation(
    /** 主异常是否被裁剪。 */ val truncated: Boolean,
    /** 安全原因类别。 */ val reason: String? = null,
)

/** 崩溃线程的 Java 身份和处理器入口取得的系统线程号。 */
data class CrashedThread(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 十进制字符串形式的 Java 线程标识。 */ val javaId: String? = null,
    /** 已脱敏的线程名。 */ val name: String? = null,
    /** Java 线程状态。 */ val state: String? = null,
    /** Java 线程优先级。 */ val priority: Int? = null,
    /** 是否为守护线程。 */ val daemon: Boolean? = null,
    /** 是否为 Android 主线程。 */ val mainThread: Boolean? = null,
    /** 处理器入口取得的系统 TID，不等同于 Java ID。 */ val systemTid: Int? = null,
)

/** 其他线程的有界集合及可知计数。 */
data class OtherThreads(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因数量、帧数或字节预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 可知的其他线程总数，未知时为空。 */ val observedCount: Int? = null,
    /** 可知的未保存线程数，未知时为空。 */ val omittedCount: Int? = null,
    /** 保留采集顺序的线程快照。 */ val threads: List<OtherThread> = emptyList(),
)

/** 单个其他线程及其原始 Java 帧，空帧表示未取得帧。 */
data class OtherThread(
    /** 十进制字符串形式的 Java 线程标识。 */ val javaId: String,
    /** 已脱敏的线程名。 */ val name: String,
    /** Java 线程状态。 */ val state: String,
    /** Java 线程优先级。 */ val priority: Int,
    /** 是否为守护线程。 */ val daemon: Boolean,
    /** 是否为 Android 主线程。 */ val mainThread: Boolean,
    /** 此线程的帧是否被裁剪。 */ val truncated: Boolean = false,
    /** 原始 Java 帧。 */ val frames: List<StackFrame> = emptyList(),
)

/** 三个日志缓冲区分别保存其真实状态。 */
data class CrashLogcat(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** main 缓冲区。 */ val main: LogcatBuffer? = null,
    /** system 缓冲区。 */ val system: LogcatBuffer? = null,
    /** events 缓冲区。 */ val events: LogcatBuffer? = null,
)

/** 已脱敏且受限的单个日志缓冲区。 */
data class LogcatBuffer(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 日志行；成功的空数组不同于不可用。 */ val lines: List<String> = emptyList(),
)

/** 内存数值均以字节表示，未取得的值为空。 */
data class CrashMemory(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** Runtime Java 堆已使用字节数。 */ val javaHeapUsedBytes: Long? = null,
    /** Runtime Java 堆最大字节数。 */ val javaHeapMaxBytes: Long? = null,
    /** Debug Java 堆 PSS 字节数。 */ val javaHeapPssBytes: Long? = null,
    /** Debug Native 堆 PSS 字节数。 */ val nativeHeapPssBytes: Long? = null,
    /** Debug Native 堆已分配字节数。 */ val nativeHeapAllocatedBytes: Long? = null,
    /** 进程总 PSS 字节数。 */ val totalPssBytes: Long? = null,
    /** 进程 RSS 字节数。 */ val rssBytes: Long? = null,
    /** 进程 VSS 字节数。 */ val vssBytes: Long? = null,
    /** 进程 Swap 字节数。 */ val swapBytes: Long? = null,
    /** 已脱敏的 meminfo 原文。 */ val meminfo: String? = null,
    /** 已脱敏的 proc status 原文。 */ val statusText: String? = null,
    /** 已脱敏的 proc limits 原文。 */ val limits: String? = null,
)

/** 进程文件描述符计数和有限明细。 */
data class OpenFiles(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算或读取竞态省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 可知的 FD 总数，未知时为空。 */ val count: Int? = null,
    /** 有界且已脱敏的 FD 明细。 */ val entries: List<OpenFile> = emptyList(),
)

/** 单个文件描述符及安全目标描述。 */
data class OpenFile(
    /** 文件描述符编号。 */ val fd: Int,
    /** 已脱敏的目标描述。 */ val target: String? = null,
)

/** 区分系统进程启动时间和 SDK 初始化时间。 */
data class CrashProcess(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** Android 数值进程号。 */ val pid: Int? = null,
    /** 已脱敏的进程名。 */ val name: String? = null,
    /** 可知时的前后台状态。 */ val foreground: Boolean? = null,
    /** 可可靠获得时的系统进程启动时间，Unix 毫秒。 */ val startedAt: Long? = null,
    /** SDK 初始化时间，Unix 毫秒。 */ val sdkInitializedAt: Long? = null,
)

/** 崩溃时可公开获取的设备环境。 */
data class CrashDevice(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** Android API level。 */ val apiLevel: Int? = null,
    /** 设备支持的 ABI。 */ val abis: List<String> = emptyList(),
    /** 已脱敏的厂商。 */ val manufacturer: String? = null,
    /** 已脱敏的品牌。 */ val brand: String? = null,
    /** 已脱敏的系统构建标识。 */ val buildFingerprint: String? = null,
)
