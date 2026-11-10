package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.LogcatBuffer
import com.shanshui.performance.network.OpenFile
import com.shanshui.performance.network.OpenFiles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** 独立提交的原进程日志、内存和文件描述符。 */
internal data class NativeDiagnosticsRecord(
    /** 原进程三个日志缓冲区。 */ val logcat: CrashLogcat,
    /** 原进程内存用量和受限数值摘要。 */ val memory: CrashMemory,
    /** 原进程文件描述符及安全目标类别。 */ val openFiles: OpenFiles,
)

/** 严格解析有身份和尾部校验的 Native 非堆栈诊断。 */
internal object NativeDiagnosticsCodec {
    /** 单份辅助诊断字节上限。 */ private const val MAX_BYTES = 96 * 1024
    /** JavaScript 能无损表示的正整数上限。 */ private const val SAFE_INTEGER = 9_007_199_254_740_991L
    /** 本地记录校验参数。 */ private const val FNV_OFFSET = -2128831035
    private const val FNV_PRIME = 16777619
    /** 拒绝可能带日志标签的两列原始栈内存行。 */
    private val dumpLine = Regex("(?i)^\\s*(?:[\\w.$-]{1,64}\\s*:\\s*)?(?:#\\d+\\s+)?[0-9a-f]{8,16}\\s+[0-9a-f]{8,16}(?:\\s|$)")

    /** 判定可选文本是否携带禁止的内存段或带标签原始数据行。 */
    fun containsForbiddenDump(value: String): Boolean = value.contains("memory near", ignoreCase = true) ||
        value.contains("memory map", ignoreCase = true) || value.contains("stack:", ignoreCase = true) ||
        value.lineSequence().any { dumpLine.containsMatchIn(it) }

    /** 限定状态、原因和采集时间的通用分区头。 */
    private data class Partition(
        /** 采集状态。 */ val status: String,
        /** 安全原因。 */ val reason: String?,
        /** 是否截断。 */ val truncated: Boolean,
        /** 实际采集时间，未知时为空。 */ val capturedAt: Long?,
    )

    /** 仅接受与原核心相同进程、时间和完整校验的记录。 */
    fun decode(bytes: ByteArray, core: NativeCoreRecord): NativeDiagnosticsRecord? = runCatching {
        require(bytes.size in 28..MAX_BYTES && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(65, 78, 68, 71)))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(4)
        require(input.int == 1 && input.int == bytes.size && input.long == core.occurredAt && input.int == core.pid)
        val buffers = listOf(200, 50, 50).map { limit ->
            val partition = readPartition(input)
            val count = input.int
            require(count in 0..limit && (partition.status !in listOf("unavailable", "skipped") || count == 0))
            val lines = List(count) {
                val line = readText(input, 2000)
                require(!containsForbiddenDump(line))
                CrashSanitizer.sanitize(line, 2000)
            }
            LogcatBuffer(partition.status, partition.reason, partition.truncated, partition.capturedAt, lines)
        }
        val logReason = buffers.firstNotNullOfOrNull { it.reason }
        val logStatus = if (logReason == null) "ok" else if (buffers.any { it.status == "ok" || it.status == "partial" }) "partial" else "unavailable"
        val logcat = CrashLogcat(status = logStatus, reason = logReason, truncated = buffers.any { it.truncated },
            capturedAt = buffers.mapNotNull { it.capturedAt }.minOrNull(), main = buffers[0], system = buffers[1], events = buffers[2])
        val memoryPartition = readPartition(input)
        val rss = readOptionalBytes(input)
        val vss = readOptionalBytes(input)
        val swap = readOptionalBytes(input)
        val pss = readOptionalBytes(input)
        val meminfo = readText(input, 512).takeIf { it.isNotEmpty() }
        val statusText = readText(input, 512).takeIf { it.isNotEmpty() }
        val limits = readText(input, 256).takeIf { it.isNotEmpty() }
        require(metricText(meminfo, "MemTotal|MemFree|MemAvailable") && metricText(statusText, "VmRSS|VmSize|VmSwap"))
        require(limits == null || limits.matches(Regex("Max open files [0-9]{1,31} [0-9]{1,31}\\n")))
        val memory = CrashMemory(status = memoryPartition.status, reason = memoryPartition.reason,
            truncated = memoryPartition.truncated, capturedAt = memoryPartition.capturedAt,
            rssBytes = rss, vssBytes = vss, swapBytes = swap, totalPssBytes = pss,
            meminfo = meminfo, statusText = statusText, limits = limits)
        val filePartition = readPartition(input)
        val observed = input.int
        val count = input.int
        require(count in 0..128 && (observed == -1 || observed >= count) &&
            (filePartition.status !in listOf("unavailable", "skipped") || count == 0))
        val seen = mutableSetOf<Int>()
        val entries = List(count) {
            val fd = input.int
            val category = input.int
            require(fd >= 0 && seen.add(fd) && category in 0..6)
            OpenFile(fd, when (category) {
                1 -> "[socket]"; 2 -> "[pipe]"; 3 -> "[anonymous]"; 4 -> "[device]"; 5 -> "[path]"; 6 -> "[other]"; else -> null
            })
        }
        require(input.remaining() == 4 && input.int == checksum(bytes, bytes.size - 4))
        val files = OpenFiles(status = filePartition.status, reason = filePartition.reason,
            truncated = filePartition.truncated, capturedAt = filePartition.capturedAt,
            count = observed.takeIf { it >= 0 }, entries = entries)
        NativeDiagnosticsRecord(logcat, memory, files)
    }.getOrNull()

    /** 读取并校验状态、原因、截断及实际采集时间。 */
    private fun readPartition(input: ByteBuffer): Partition {
        val statusCode = input.int
        val reasonCode = input.int
        val truncated = input.int
        val capturedAt = input.long
        require(statusCode in 0..2 && reasonCode in 0..6 && truncated in 0..1 && capturedAt in 0..SAFE_INTEGER)
        require((statusCode == 0 && reasonCode == 0 && truncated == 0) || (statusCode != 0 && reasonCode != 0))
        return Partition(status = when (statusCode) { 0 -> "ok"; 1 -> "partial"; else -> "unavailable" },
            reason = when (reasonCode) { 1 -> "io_error"; 2 -> "permission_denied"; 3 -> "timeout"; 4 -> "unsupported";
                5 -> "size_limit"; 6 -> "count_limit"; else -> null },
            truncated = truncated == 1, capturedAt = capturedAt.takeIf { it != 0L })
    }

    /** 读取有限 UTF-8 内容，拒绝非法编码或异常长度。 */
    private fun readText(input: ByteBuffer, maximum: Int): String {
        val size = input.int
        require(size in 0..maximum && size <= input.remaining() - 4)
        val bytes = ByteArray(size)
        input.get(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** 空值通过全 1 位模式表达；零内存值仍为合法数值。 */
    private fun readOptionalBytes(input: ByteBuffer): Long? {
        val value = input.long
        require(value == -1L || value in 0..SAFE_INTEGER)
        return value.takeIf { it != -1L }
    }

    /** 限定 proc 文本只能包含约定的数值行，不能携带完整映射或路径。 */
    private fun metricText(value: String?, keys: String): Boolean = value == null ||
        (value.endsWith('\n') && value.lineSequence().filter { it.isNotEmpty() }
            .all { it.matches(Regex("(?:$keys): [0-9]{1,20} kB")) })

    /** 计算 FNV-1a 校验并拒绝半写。 */
    private fun checksum(bytes: ByteArray, length: Int): Int {
        var value = FNV_OFFSET
        for (index in 0 until length) value = (value xor (bytes[index].toInt() and 0xff)) * FNV_PRIME
        return value
    }
}
