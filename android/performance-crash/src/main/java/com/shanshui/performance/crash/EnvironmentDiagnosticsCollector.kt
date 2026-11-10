package com.shanshui.performance.crash

import android.os.Build
import android.os.Debug
import android.system.Os
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.OpenFile
import com.shanshui.performance.network.OpenFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Runtime 堆值与 Debug PSS 使用不同口径。 */
internal data class RuntimeHeapSnapshot(
    /** Java 堆已使用字节数。 */ val usedBytes: Long?,
    /** Java 堆最大字节数。 */ val maxBytes: Long?,
)

/** Debug 读取的 PSS 为 KiB，Native 分配量为字节。 */
internal data class DebugMemorySnapshot(
    /** Java 堆 PSS，KiB。 */ val javaPssKib: Long?,
    /** Native 堆 PSS，KiB。 */ val nativePssKib: Long?,
    /** 总 PSS，KiB。 */ val totalPssKib: Long?,
    /** Native 堆已分配字节数。 */ val nativeAllocatedBytes: Long?,
)

/** 文件读取结果保留是否因字节上限截断。 */
internal data class BoundedProcText(
    /** 受限 UTF-8 文本。 */ val text: String,
    /** 是否有更多原始字节。 */ val truncated: Boolean,
)

/** 启动时可安全缓存且不依赖设备进程下一次启动的环境。 */
internal data class CachedCrashEnvironment(
    /** Android 数值进程号。 */ val pid: Int?,
    /** 已脱敏进程名。 */ val processName: String?,
    /** SDK 初始化时间，Unix 毫秒。 */ val sdkInitializedAt: Long,
    /** 可可靠取得的真实进程启动时间；未知时为空。 */ val processStartedAt: Long?,
    /** Android API level。 */ val apiLevel: Int?,
    /** 支持的 ABI。 */ val abis: List<String>,
    /** 设备厂商。 */ val manufacturer: String?,
    /** 设备品牌。 */ val brand: String?,
    /** 系统构建指纹。 */ val buildFingerprint: String?,
)

/** 可注入的当前进程读取接口，避免 Crash 模块依赖其他性能模块。 */
internal interface CrashEnvironmentSource {
    /** 获取 Runtime Java 堆信息。 */
    fun runtimeHeap(): RuntimeHeapSnapshot
    /** 获取 Debug 内存信息。 */
    fun debugMemory(): DebugMemorySnapshot
    /** 有界读取自身 proc 或系统 meminfo。 */
    fun readProc(path: String, maxBytes: Int): BoundedProcText
    /** 列举当前进程 FD；无法读取时抛出异常。 */
    fun fileDescriptors(): List<Int>
    /** 读取单个 FD 目标，可能因 FD 消失而失败。 */
    fun fileTarget(fd: Int): String
}

/** 通过公开 Android API 与当前进程 proc 文件读取环境。 */
internal class AndroidCrashEnvironmentSource : CrashEnvironmentSource {
    /** Runtime 堆使用量和上限均以字节表示。 */
    override fun runtimeHeap(): RuntimeHeapSnapshot {
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        return RuntimeHeapSnapshot(if (total >= free) total - free else null, runtime.maxMemory())
    }

    /** Debug 的 PSS 字段单位为 KiB，Native 已分配量为字节。 */
    override fun debugMemory(): DebugMemorySnapshot {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return DebugMemorySnapshot(info.dalvikPss.toLong(), info.nativePss.toLong(), info.totalPss.toLong(),
            Debug.getNativeHeapAllocatedSize())
    }

    /** 分块读取上限加一个字节，避免将无限文本读入内存。 */
    override fun readProc(path: String, maxBytes: Int): BoundedProcText {
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(4096))
        var truncated = false
        File(path).inputStream().use { input ->
            val chunk = ByteArray(1024)
            while (output.size() <= maxBytes) {
                val count = input.read(chunk, 0, minOf(chunk.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(chunk, 0, count)
                if (output.size() > maxBytes) { truncated = true; break }
            }
        }
        val bytes = output.toByteArray()
        return BoundedProcText(String(bytes, 0, minOf(bytes.size, maxBytes), StandardCharsets.UTF_8), truncated)
    }

    /** 当前进程 FD 总数来自公开目录；读取竞争由调用方单独标记。 */
    override fun fileDescriptors(): List<Int> = File("/proc/self/fd").list()
        ?.mapNotNull { it.toIntOrNull()?.takeIf { number -> number >= 0 } }
        ?: throw IOException("FD directory unavailable")

    /** 只读取当前进程指定 FD 的符号链接。 */
    override fun fileTarget(fd: Int): String = Os.readlink("/proc/self/fd/$fd")

    companion object {
        /** 在初始化时缓存不需要崩溃现场才能取得的公开进程与设备信息。 */
        fun cache(sdkInitializedAt: Long): CachedCrashEnvironment {
            val source = AndroidCrashEnvironmentSource()
            val processName = runCatching { source.readProc("/proc/self/cmdline", 512).text.substringBefore('\u0000') }
                .getOrNull()?.let { CrashSanitizer.sanitize(it, 512) }
            return CachedCrashEnvironment(
                pid = android.os.Process.myPid().takeIf { it > 0 }, processName = processName,
                sdkInitializedAt = sdkInitializedAt,
                // 公开 API 返回开机后的单调时间，墙上时钟可能被调整，不能伪造精确 Unix 启动时间。
                processStartedAt = null,
                apiLevel = Build.VERSION.SDK_INT, abis = Build.SUPPORTED_ABIS.orEmpty().take(16).toList(),
                manufacturer = Build.MANUFACTURER, brand = Build.BRAND, buildFingerprint = Build.FINGERPRINT,
            )
        }
    }
}

/** 四个环境分区可分别成功、失败或因资源不足跳过。 */
internal data class EnvironmentDiagnostics(
    /** 当前进程内存。 */ val memory: CrashMemory,
    /** 当前进程文件描述符。 */ val openFiles: OpenFiles,
    /** 进程身份与前后台状态。 */ val process: CrashProcess,
    /** 设备与系统环境。 */ val device: CrashDevice,
)

/** Native 回调中只能由原进程 Java 运行时提供的环境。 */
internal data class NativeJavaEnvironment(
    /** Java 与 Native 堆数值。 */ val memory: CrashMemory,
    /** 原进程信息。 */ val process: CrashProcess,
    /** 原设备信息。 */ val device: CrashDevice,
)

/** 采集内存、FD 和缓存环境，未知值保持为空。 */
internal class EnvironmentDiagnosticsCollector(
    /** 当前进程读取器。 */ private val source: CrashEnvironmentSource,
    /** 初始化时缓存的进程与设备环境。 */ private val environment: CachedCrashEnvironment,
    /** 可知时的前后台状态；没有观察到生命周期时返回 null。 */ private val foreground: () -> Boolean?,
    /** 原采集时间。 */ private val clock: () -> Long = { System.currentTimeMillis() },
    /** 预算使用单调时间。 */ private val nanoTime: () -> Long = { System.nanoTime() },
    /** 与上传器一致的 JSON 编码。 */ private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    /** 分区独立降级，内存压力下只尽力读取廉价 Runtime 堆。 */
    fun collect(deadlineNanos: Long, skipExpensive: Boolean): EnvironmentDiagnostics {
        val capturedAt = clock()
        val memory = memory(capturedAt, deadlineNanos, skipExpensive)
        val files = openFiles(capturedAt, deadlineNanos, skipExpensive)
        val process = process(capturedAt)
        return EnvironmentDiagnostics(memory, files, process, device(capturedAt))
    }

    /** 在处理器入口冻结缓存的进程身份，采集中断后仍可恢复。 */
    fun captureProcess(): CrashProcess = process(clock())

    /** 在原进程工作者中读取堆数值；proc、FD 和日志由独立回溯进程负责。 */
    fun collectNativeJava(deadlineNanos: Long): NativeJavaEnvironment {
        val capturedAt = clock()
        val runtime = if (nanoTime() < deadlineNanos) runCatching { source.runtimeHeap() }.getOrNull() else null
        val debug = if (nanoTime() < deadlineNanos) runCatching { source.debugMemory() }.getOrNull() else null
        val timedOut = nanoTime() >= deadlineNanos
        val complete = runtime != null && debug != null && safeBytes(runtime.usedBytes) != null &&
            safeBytes(runtime.maxBytes) != null && kibToBytes(debug.javaPssKib) != null &&
            kibToBytes(debug.nativePssKib) != null && kibToBytes(debug.totalPssKib) != null &&
            safeBytes(debug.nativeAllocatedBytes) != null
        val memory = CrashMemory(status = if (complete && !timedOut) "ok" else if (runtime != null || debug != null) "partial" else "unavailable",
            reason = if (complete && !timedOut) null else if (timedOut) "timeout" else "io_error", capturedAt = capturedAt,
            javaHeapUsedBytes = safeBytes(runtime?.usedBytes), javaHeapMaxBytes = safeBytes(runtime?.maxBytes),
            javaHeapPssBytes = kibToBytes(debug?.javaPssKib), nativeHeapPssBytes = kibToBytes(debug?.nativePssKib),
            nativeHeapAllocatedBytes = safeBytes(debug?.nativeAllocatedBytes), totalPssBytes = kibToBytes(debug?.totalPssKib))
        return NativeJavaEnvironment(memory, process(capturedAt), device(capturedAt))
    }

    /** 初始化阶段保存安全的静态设备信息，以便 Java 回调中断时恢复原设备。 */
    fun captureDevice(): CrashDevice = device(clock())

    /** 从初始化缓存构造已脱敏设备分区。 */
    private fun device(capturedAt: Long): CrashDevice = CrashDevice(status = "ok", capturedAt = capturedAt,
        apiLevel = environment.apiLevel, abis = environment.abis.map { CrashSanitizer.sanitize(it, 128) },
        manufacturer = environment.manufacturer?.let { CrashSanitizer.sanitize(it, 256) },
        brand = environment.brand?.let { CrashSanitizer.sanitize(it, 256) },
        buildFingerprint = environment.buildFingerprint?.let { CrashSanitizer.sanitize(it, 1024) })

    /** 使用缓存环境和已观察到的生命周期快照构造进程分区。 */
    private fun process(capturedAt: Long): CrashProcess {
        val processIncomplete = environment.pid == null || environment.processName == null
        return CrashProcess(status = if (processIncomplete) "partial" else "ok",
            reason = if (processIncomplete) "io_error" else null, capturedAt = capturedAt, pid = environment.pid,
            name = environment.processName, foreground = runCatching { foreground() }.getOrNull(),
            startedAt = environment.processStartedAt, sdkInitializedAt = environment.sdkInitializedAt)
    }

    /** Runtime、Debug 和 proc 分别读取，不能将缺失或失败表示成零。 */
    private fun memory(capturedAt: Long, deadline: Long, skipExpensive: Boolean): CrashMemory {
        val runtime = runCatching { source.runtimeHeap() }.getOrNull()
        if (skipExpensive || nanoTime() >= deadline) {
            return CrashMemory(status = if (runtime == null) "skipped" else "partial",
                reason = if (skipExpensive) "memory_pressure" else "timeout", capturedAt = capturedAt,
                javaHeapUsedBytes = safeBytes(runtime?.usedBytes), javaHeapMaxBytes = safeBytes(runtime?.maxBytes))
        }
        val debug = runCatching { source.debugMemory() }.getOrNull()
        var reason: String? = if (runtime == null || debug == null) "io_error" else null
        var truncated = false
        var rssBytes: Long? = null
        var vssBytes: Long? = null
        var swapBytes: Long? = null
        if (runtime != null && (safeBytes(runtime.usedBytes) == null || safeBytes(runtime.maxBytes) == null)) reason = "io_error"
        if (debug != null && (kibToBytes(debug.javaPssKib) == null || kibToBytes(debug.nativePssKib) == null ||
                kibToBytes(debug.totalPssKib) == null || safeBytes(debug.nativeAllocatedBytes) == null)
        ) reason = "io_error"
        /** 读取和脱敏原文，失败只保留安全原因。 */
        fun read(path: String): String? {
            if (nanoTime() >= deadline) { reason = reason ?: "timeout"; return null }
            return try {
                val result = source.readProc(path, PROC_RAW_BYTES)
                if (path == "/proc/self/status") {
                    // 数值在文本脱敏前解析，避免把大内存值误识别成手机号。
                    rssBytes = statusMetric(result.text, "VmRSS")
                    vssBytes = statusMetric(result.text, "VmSize")
                    swapBytes = statusMetric(result.text, "VmSwap")
                }
                val cleaned = result.text.lineSequence().joinToString("\n") { CrashSanitizer.sanitize(it, PROC_RAW_BYTES) }
                val bounded = fitText(cleaned, PROC_JSON_BYTES)
                if (result.truncated || bounded != cleaned) { truncated = true; reason = reason ?: "size_limit" }
                bounded
            } catch (_: SecurityException) {
                reason = reason ?: "permission_denied"; null
            } catch (_: Throwable) {
                reason = reason ?: "io_error"; null
            }
        }
        val meminfo = read("/proc/meminfo")
        val status = read("/proc/self/status")
        val limits = read("/proc/self/limits")
        val hasData = runtime != null || debug != null || meminfo != null || status != null || limits != null
        return CrashMemory(status = if (reason == null) "ok" else if (hasData) "partial" else "unavailable", reason = reason,
            truncated = truncated, capturedAt = capturedAt,
            javaHeapUsedBytes = safeBytes(runtime?.usedBytes), javaHeapMaxBytes = safeBytes(runtime?.maxBytes),
            javaHeapPssBytes = kibToBytes(debug?.javaPssKib), nativeHeapPssBytes = kibToBytes(debug?.nativePssKib),
            nativeHeapAllocatedBytes = safeBytes(debug?.nativeAllocatedBytes), totalPssBytes = kibToBytes(debug?.totalPssKib),
            rssBytes = rssBytes, vssBytes = vssBytes, swapBytes = swapBytes,
            meminfo = meminfo, statusText = status, limits = limits)
    }

    /** 总数已知时保存有界 FD 条目，读取竞态不伪造目标。 */
    private fun openFiles(capturedAt: Long, deadline: Long, skipExpensive: Boolean): OpenFiles {
        if (skipExpensive) return OpenFiles(status = "skipped", reason = "memory_pressure", capturedAt = capturedAt)
        if (nanoTime() >= deadline) return OpenFiles(status = "skipped", reason = "timeout", capturedAt = capturedAt)
        val numbers = try { source.fileDescriptors().distinct().sorted() }
        catch (_: SecurityException) { return OpenFiles(status = "unavailable", reason = "permission_denied", capturedAt = capturedAt) }
        catch (_: Throwable) { return OpenFiles(status = "unavailable", reason = "io_error", capturedAt = capturedAt) }
        val entries = mutableListOf<OpenFile>()
        var reason: String? = if (numbers.size > MAX_FDS) "count_limit" else null
        var truncated = numbers.size > MAX_FDS
        for (fd in numbers.take(MAX_FDS)) {
            if (nanoTime() >= deadline) { reason = reason ?: "timeout"; truncated = true; break }
            val target = try { safeTarget(source.fileTarget(fd)) }
            catch (_: SecurityException) { reason = reason ?: "permission_denied"; null }
            catch (_: Throwable) { reason = reason ?: "io_error"; null }
            val candidate = OpenFile(fd, target)
            val trial = OpenFiles(status = "partial", reason = "size_limit", truncated = true,
                capturedAt = capturedAt, count = numbers.size, entries = entries + candidate)
            if (encodedBytes(trial) > MAX_FD_BYTES) { reason = reason ?: "size_limit"; truncated = true; break }
            entries += candidate
        }
        return OpenFiles(status = if (reason == null) "ok" else "partial", reason = reason,
            truncated = truncated, capturedAt = capturedAt, count = numbers.size, entries = entries)
    }

    /** FD 目标仅保留资源类别，避免私有文件名、套接字标识或匿名对象名称泄露。 */
    private fun safeTarget(value: String): String = when {
        value.startsWith("socket:") -> "[socket]"
        value.startsWith("pipe:") -> "[pipe]"
        value.startsWith("anon_inode:") -> "[anonymous]"
        value.startsWith("/dev/") -> "[device]"
        value.startsWith("/") -> "[path]"
        else -> "[other]"
    }

    /** proc 的 kB 字段按 KiB 换算，不可解析或溢出时保持缺失。 */
    private fun statusMetric(status: String?, field: String): Long? {
        val line = status?.lineSequence()?.firstOrNull { it.startsWith("$field:") } ?: return null
        val match = STATUS_VALUE.matchEntire(line.trim()) ?: return null
        return kibToBytes(match.groupValues[1].toLongOrNull())
    }

    /** 将 KiB 安全换算成 JSON 可无损表达的字节。 */
    private fun kibToBytes(value: Long?): Long? = value?.takeIf { it in 0..SAFE_INTEGER / 1024 }?.times(1024)

    /** 过滤负数与超过 JSON 安全范围的字节值。 */
    private fun safeBytes(value: Long?): Long? = value?.takeIf { it in 0..SAFE_INTEGER }

    /** 在 JSON 转义后的字节预算内保留 Unicode 前缀。 */
    private fun fitText(value: String, maxBytes: Int): String {
        if (encodedBytes(value) <= maxBytes) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (encodedBytes(prefix(value, mid)) <= maxBytes) low = mid else high = mid - 1
        }
        return prefix(value, low)
    }

    /** 避免切开 UTF-16 代理对。 */
    private fun prefix(value: String, length: Int): String {
        var end = length.coerceIn(0, value.length)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }

    /** 计算实际 JSON UTF-8 字节。 */
    private fun encodedBytes(value: Any): Int = gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size

    /** 采集上限和安全整数范围。 */
    private companion object {
        const val SAFE_INTEGER = 9_007_199_254_740_991L
        const val PROC_RAW_BYTES = 4096
        const val PROC_JSON_BYTES = 3500
        const val MAX_FDS = 128
        const val MAX_FD_BYTES = 12 * 1024
        val STATUS_VALUE = Regex("^[^:]+:\\s*(\\d+)\\s+kB\\s*$", RegexOption.IGNORE_CASE)
    }
}
