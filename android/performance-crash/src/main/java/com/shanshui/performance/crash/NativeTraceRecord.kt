package com.shanshui.performance.crash

import com.shanshui.performance.network.NativeBacktrace
import com.shanshui.performance.network.NativeFrame
import com.shanshui.performance.network.NativeModule
import com.shanshui.performance.network.NativeThread
import com.shanshui.performance.network.NativeThreads
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 已校验的辅助回溯结果，身份必须与先前提交的核心一致。 */
internal data class NativeTraceRecord(
    /** 崩溃线程的已脱敏名称。 */ val crashedThreadName: String?,
    /** 仅包含实际引用模块的字典。 */ val modules: List<NativeModule>,
    /** 使用原信号寄存器展开的崩溃线程栈。 */ val backtrace: NativeBacktrace,
    /** 其他 Native 线程及独立状态。 */ val nativeThreads: NativeThreads,
    /** 可安全取得的 abort 消息；缺失时不猜测。 */ val abortMessage: String? = null,
)

/** 解析 dumper 的有限结构化记录，不接受原始内存或完整映射。 */
internal object NativeTraceCodec {
    /** 允许读取的最大辅助记录字节。 */ private const val MAX_BYTES = 320 * 1024
    /** 固定头及尾部校验所需的最短字节数。 */ private const val MIN_BYTES = 48
    /** 模块和线程上限。 */ private const val MAX_MODULES = 128
    private const val MAX_THREADS = 65
    /** 各种整数的完整性校验参数。 */ private const val FNV_OFFSET = -2128831035
    private const val FNV_PRIME = 16777619

    /** 仅返回长度、校验、身份、帧引用和位宽均合法的完整记录。 */
    fun decode(bytes: ByteArray, core: NativeCoreRecord): NativeTraceRecord? = runCatching {
        require(bytes.size in MIN_BYTES..MAX_BYTES && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(65, 78, 84, 82)))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(4)
        /** 旧版只含线程回溯；第二版追加可选 abort 消息。 */
        val version = input.int
        require(version in 1..2 && input.int == bytes.size && input.long == core.occurredAt)
        require(input.int == core.pid && input.int == core.systemTid)
        val observed = input.int
        val collectionStatus = input.int
        val threadCount = input.int
        val moduleCount = input.int
        require(observed >= 0 && collectionStatus in 0..2 && threadCount in 1..MAX_THREADS && moduleCount in 0..MAX_MODULES)
        val modules = List(moduleCount) { index ->
            val name = readText(input, 128)
            val buildId = readText(input, 128)
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && safeText(name))
            require(buildId.isEmpty() || buildId.matches(Regex("(?:[0-9a-f]{2}){1,64}")))
            NativeModule(id = "m$index", name = name, abi = core.abi, elfBuildId = buildId.ifEmpty { null })
        }
        val tids = mutableSetOf<Int>()
        val threads = List(threadCount) { threadIndex ->
            val tid = input.int
            val isMain = input.int
            val status = input.int
            val truncated = input.int
            val name = readText(input, 128)
            val frameCount = input.int
            require(tid > 0 && tids.add(tid) && isMain in 0..1 && (isMain == 1) == (tid == core.pid))
            require((threadIndex == 0 && tid == core.systemTid) || (threadIndex != 0 && tid != core.systemTid))
            require(status in 0..2 && truncated in 0..1 && !(status == 0 && truncated == 1) && safeText(name))
            require(frameCount in 0..if (threadIndex == 0) 128 else 32)
            require((status != 0 || frameCount > 0) && (status != 2 || frameCount == 0))
            val frames = List(frameCount) { frameIndex ->
                val module = input.int
                val pc = input.long
                val offset = input.long
                val function = readText(input, 96)
                require((module == -1 && pc == 0L) || module in modules.indices)
                require(function.isNotEmpty() || offset == 0L)
                require(safeText(function) && fitsAbi(pc, core.abi) && fitsAbi(offset, core.abi))
                NativeFrame(index = frameIndex, moduleId = if (module == -1) null else modules[module].id,
                    relativePc = if (module == -1) null else hex(pc), functionName = function.ifEmpty { null },
                    functionOffset = if (function.isEmpty()) null else hex(offset))
            }
            val reason = when {
                status == 2 -> "io_error"
                status == 1 && truncated == 1 -> "count_limit"
                status == 1 -> "unsupported"
                else -> null
            }
            val trace = NativeBacktrace(status = when (status) { 0 -> "ok"; 1 -> "partial"; else -> "unavailable" },
                reason = reason, truncated = truncated == 1, capturedAt = core.occurredAt, frames = frames)
            NativeThread(systemTid = tid, name = name.ifEmpty { null }, mainThread = isMain == 1, backtrace = trace)
        }
        /** 已在原生首次落盘前检查过的完整可选消息。 */
        val abortMessage = if (version >= 2) readText(input, 255) else ""
        require((core.signal == 6 || abortMessage.isEmpty()) && safeText(abortMessage) &&
            !NativeDiagnosticsCodec.containsForbiddenDump(abortMessage))
        require(input.remaining() == 4 && input.int == checksum(bytes, bytes.size - 4))
        val others = threads.drop(1)
        require(observed >= others.size && (collectionStatus != 0 || observed == others.size) &&
            (collectionStatus != 2 || others.isEmpty()))
        val omitted = observed - others.size
        val nativeThreads = NativeThreads(status = when (collectionStatus) { 0 -> "ok"; 1 -> "partial"; else -> "unavailable" },
            reason = when (collectionStatus) { 1 -> "count_limit"; 2 -> "io_error"; else -> null },
            truncated = collectionStatus == 1, capturedAt = core.occurredAt,
            observedCount = if (collectionStatus == 2) null else observed,
            omittedCount = if (collectionStatus == 2) null else omitted, threads = others)
        NativeTraceRecord(threads.first().name, modules, threads.first().backtrace, nativeThreads, abortMessage.ifEmpty { null })
    }.getOrNull()

    /** 限制字符串长度并拒绝非 ASCII 字节和异常剩余长度。 */
    private fun readText(input: ByteBuffer, limit: Int): String {
        val size = input.int
        require(size in 0..limit && size <= input.remaining() - 4)
        val bytes = ByteArray(size)
        input.get(bytes)
        require(bytes.all { it.toInt() in 32..126 })
        return bytes.toString(Charsets.US_ASCII)
    }

    /** 与已有脱敏规则交叉检查，不能将不安全的可选文本写入最终队列。 */
    private fun safeText(value: String): Boolean = value == CrashSanitizer.sanitize(value, value.length)

    /** 32 位设备不能接收超过其位宽的地址。 */
    private fun fitsAbi(value: Long, abi: String): Boolean = abi == "arm64-v8a" || abi == "x86_64" || value ushr 32 == 0L

    /** 无损输出无符号地址并保留零。 */
    private fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    /** FNV-1a 校验与原生输出协议一致。 */
    private fun checksum(bytes: ByteArray, length: Int): Int {
        var value = FNV_OFFSET
        for (index in 0 until length) value = (value xor (bytes[index].toInt() and 0xff)) * FNV_PRIME
        return value
    }
}
