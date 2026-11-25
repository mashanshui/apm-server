package com.shanshui.performance.network

/** 崩溃发生时的可选诊断现场，各分区分别表达采集状态。 */
data class CrashDiagnostics(
    /** 核心异常因事件预算被裁剪时的说明。 */ val coreTruncation: CoreTruncation? = null,
    /** 未捕获异常处理器收到的线程。 */ val crashedThread: CrashedThread? = null,
    /** 崩溃处理期间其他 JVM 线程的近似快照。 */ val otherThreads: OtherThreads? = null,
    /** 当前进程可读取的日志缓冲区。 */ val logcat: CrashLogcat? = null,
    /** 进程内存快照。 */ val memory: CrashMemory? = null,
    /** 当前进程文件描述符。 */ val openFiles: OpenFiles? = null,
    /** Android 数值进程与生命周期信息。 */ val process: CrashProcess? = null,
    /** 设备和系统构建信息。 */ val device: CrashDevice? = null,
    /** Native 崩溃线程之外的原始 Native 线程快照。 */ val nativeThreads: NativeThreads? = null,
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
    /** 可靠取得时的系统 TID。 */ val systemTid: Int? = null,
    /** Native 事件中与崩溃线程的关系。 */ val relationToCrash: String? = null,
)

/** 其他 Native 线程的有界集合。 */
data class NativeThreads(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否因数量、帧数或字节预算省略内容。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 可知的其他线程总数。 */ val observedCount: Int? = null,
    /** 可知的遗漏线程数。 */ val omittedCount: Int? = null,
    /** 按系统 TID 区分的线程快照。 */ val threads: List<NativeThread> = emptyList(),
)

/** 单个其他 Native 线程。 */
data class NativeThread(
    /** 系统线程号。 */ val systemTid: Int,
    /** 已脱敏的线程名。 */ val name: String? = null,
    /** 可可靠判断时的主线程标记。 */ val mainThread: Boolean? = null,
    /** 原始回溯及其采集状态。 */ val backtrace: NativeBacktrace,
)
