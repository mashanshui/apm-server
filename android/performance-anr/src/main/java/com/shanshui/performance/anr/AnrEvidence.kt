package com.shanshui.performance.anr

import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.OpenFiles

/** 文本分区的采集结果，与服务端 ANR 载荷使用同一状态语义。 */
data class AnrTextPartition(
    /** 采集状态。 */ val status: String,
    /** 无法完整采集时的受控原因。 */ val reason: String? = null,
    /** 文本是否因本地或上传预算裁剪。 */ val truncated: Boolean = false,
    /** 原进程采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 已脱敏文本。 */ val text: String? = null,
    /** 系统 trace 的实际采集来源。 */ val source: String? = null,
)

/** ANR 使用的通用分区，不包含 Crash 的线程快照。 */
data class AnrDiagnostics(
    /** 当前进程日志。 */ val logcat: CrashLogcat? = null,
    /** 当前进程内存。 */ val memory: CrashMemory? = null,
    /** 当前进程文件描述符。 */ val openFiles: OpenFiles? = null,
    /** 当前进程身份和前后台状态。 */ val process: CrashProcess? = null,
    /** 原进程设备和系统信息。 */ val device: CrashDevice? = null,
)
