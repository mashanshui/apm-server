package com.shanshui.performance.network

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
