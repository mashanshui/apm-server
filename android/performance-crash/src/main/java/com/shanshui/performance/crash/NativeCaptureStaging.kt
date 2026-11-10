package com.shanshui.performance.crash

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.core.AtomicFileWriter
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.NativeBacktrace
import com.shanshui.performance.network.NativeCrash
import com.shanshui.performance.network.NativeJavaStack
import com.shanshui.performance.network.NativeRegisters
import com.shanshui.performance.network.NativeSignal
import com.shanshui.performance.network.NativeThreads
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.OtherThreads
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 信号发生之前固定的原进程身份，不包含 App Key。 */
internal data class NativeCaptureIdentity(
    /** 暂存协议版本。 */ val version: Int,
    /** 预先生成事件及其原会话、进程、构建和设备上下文。 */ val event: CrashEvent,
    /** 此身份对应的 Android 进程 PID。 */ val pid: Int,
    /** 原进程在系统启动后的内核启动计数，用于识别 PID 复用。 */ val processStartTicks: Long? = null,
    /** 崩溃前缓存的安全进程身份，不把初始化前后台状态当作崩溃现场。 */ val process: CrashProcess? = null,
    /** 崩溃前缓存的安全设备环境。 */ val device: CrashDevice? = null,
)

/** 带完整性校验的身份信封；校验输入是原始 UTF-8 JSON 字节。 */
private data class NativeIdentityEnvelope(
    /** 信封版本。 */ val version: Int,
    /** 原身份 JSON。 */ val content: String,
    /** 身份内容 SHA-256。 */ val sha256: String,
)

/** 一次预备暂存的路径；核心文件由 Native 信号入口独占创建和写入。 */
internal data class NativeCaptureHandle(
    /** 原事件 ID。 */ val eventId: String,
    /** 身份文件。 */ val identityFile: File,
    /** 信号入口写入的有限核心文件。 */ val coreFile: File,
    /** 独立回溯进程写入的有限辅助记录。 */ val traceFile: File,
    /** 原进程日志、内存和 FD 的独立记录。 */ val diagnosticsFile: File,
    /** 已脱敏的原进程 Java 补充记录。 */ val javaFile: File,
    /** 信号处理器固定的 Java 补充接纳决定。 */ val decisionFile: File,
    /** 队列确认后写入的清理提交标记。 */ val committedFile: File,
)

/** 仅负责有界暂存及导入已有队列，不在崩溃期间执行网络或重新采集。 */
internal class NativeCaptureStaging(
    /** 现有 Crash 队列目录下的 Native 暂存目录。 */ private val root: File,
    /** 与上传器一致的 JSON 编码。 */ private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    /** 避免处理仍在运行的其他进程暂存。 */ private val isProcessAlive: (Int) -> Boolean = { File("/proc/$it").exists() },
    /** 只读取进程 stat 中的启动计数，不持久化原始 proc 文本。 */
    private val processStartTicks: (Int) -> Long? = ::readProcessStartTicks,
    /** 可注入身份读取，确保瞬时 I/O 失败可重试而不是误判损坏。 */
    private val readIdentityText: (File) -> String = { it.readText(StandardCharsets.UTF_8) },
    /** 只记录阶段和安全原因，不记录现场内容或私有路径。 */
    private val logger: (String) -> Unit = { Log.i(TAG, it) },
) {
    init {
        require(root.exists() || root.mkdirs()) { "Unable to create native capture staging" }
    }

    /** 在注册信号前保存身份并保留额度；失败时不影响 JVM Crash。 */
    fun prepare(event: CrashEvent, pid: Int, process: CrashProcess? = null, device: CrashDevice? = null): NativeCaptureHandle? = locked {
        require(event.eventType == "crash" && event.crash == null && event.eventId.matches(ID_PATTERN) && pid > 0)
        require(process == null || process.pid == pid)
        val files = root.listFiles().orEmpty().filter { it.isFile && it.name != LOCK_FILE }
        val identifiers = files.mapNotNull { stageId(it.name) }.toSet()
        val bytes = files.sumOf { it.length() }
        if (identifiers.size >= MAX_RECORDS || bytes > MAX_TOTAL_BYTES - MAX_RECORD_BYTES || event.eventId in identifiers) {
            logger("native capture preparation skipped: staging_limit")
            return@locked null
        }
        val identity = NativeCaptureIdentity(VERSION, event, pid, processStartTicks(pid), process?.copy(foreground = null), device)
        val content = gson.toJson(identity)
        val json = gson.toJson(NativeIdentityEnvelope(VERSION, content, identityDigest(content)))
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_IDENTITY_BYTES) {
            logger("native capture preparation skipped: identity_limit")
            return@locked null
        }
        val handle = handle(event.eventId)
        if (handle.identityFile.exists() || handle.coreFile.exists() || handle.traceFile.exists() || handle.diagnosticsFile.exists() ||
            handle.javaFile.exists() || handle.decisionFile.exists() || handle.committedFile.exists()) return@locked null
        AtomicFileWriter.write(handle.identityFile, json)
        logger("native capture identity prepared")
        handle
    }

    /** 仅在确认未发生崩溃且信号处理器已关闭时清理本次空暂存。 */
    fun discardUnused(handle: NativeCaptureHandle): Boolean = locked {
        cleanupUnused(handle, handle.identityFile)
    }

    /** 在任何上传前恢复完整核心；其他进程仍存活时保留其暂存。 */
    fun recover(queue: FileCrashQueue, budget: CrashEventBudget): Int = locked {
        var recovered = 0
        // 旧版本曾把空核心隔离；只回收身份仍完整、原进程已退出且全部辅助文件为空的记录。
        for (file in root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(INVALID_SUFFIX) }) {
            val id = file.name.removeSuffix(INVALID_SUFFIX)
            val identity = runCatching { readIdentity(file, id) }.getOrNull() ?: continue
            if (!ownerAlive(identity) && !handle(id).identityFile.exists()) cleanupUnused(handle(id), file)
        }
        // 队列已确认且标记完整时，重复清理不会重新构造或重新上传事件。
        val committed = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(COMMITTED_SUFFIX) }
        val pendingCleanup = mutableSetOf<String>()
        for (marker in committed) {
            val id = marker.name.removeSuffix(COMMITTED_SUFFIX)
            if (!id.matches(ID_PATTERN) || marker.length() !in 1..128 ||
                runCatching { marker.readText(StandardCharsets.UTF_8) }.getOrNull() != id) continue
            if (!cleanupCommitted(handle(id))) {
                pendingCleanup += id
                logger("native capture cleanup deferred: io_error")
            }
        }
        val identities = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(IDENTITY_SUFFIX) }.sortedBy { it.name }
        for (file in identities) {
            val id = file.name.removeSuffix(IDENTITY_SUFFIX)
            if (id in pendingCleanup) continue
            val identity = try { readIdentity(file, id) } catch (_: Exception) {
                logger("native capture recovery deferred: identity_read_error")
                continue
            }
            if (identity == null) {
                quarantine(file)
                continue
            }
            if (ownerAlive(identity)) continue
            val handle = handle(id)
            // 系统结束未崩溃进程时预建文件仍为空；不能把它计入永久隔离额度。
            if (cleanupUnused(handle, file)) continue
            if (file.length() + handle.coreFile.length() + handle.traceFile.length() + handle.diagnosticsFile.length() +
                handle.javaFile.length() + handle.decisionFile.length() > MAX_RECORD_BYTES) {
                quarantine(file)
                continue
            }
            val length = handle.coreFile.length()
            if (!handle.coreFile.isFile || length !in MIN_CORE_BYTES..MAX_CORE_BYTES) {
                quarantine(file)
                continue
            }
            val bytes = runCatching { handle.coreFile.readBytes() }.getOrNull()
            if (bytes == null) {
                logger("native capture recovery deferred: read_error")
                continue
            }
            val core = NativeCoreCodec.decode(bytes)
            if (core == null || core.pid != identity.pid) {
                quarantine(file)
                continue
            }
            var trace: NativeTraceRecord? = null
            if (handle.traceFile.isFile && handle.traceFile.length() in 1..MAX_TRACE_BYTES) {
                val traceBytes = runCatching { handle.traceFile.readBytes() }.getOrNull()
                if (traceBytes == null) {
                    logger("native capture recovery deferred: trace_read_error")
                    continue
                }
                trace = NativeTraceCodec.decode(traceBytes, core)
                if (trace == null) logger("native capture trace incomplete: interrupted")
            }
            var nativeDiagnostics: NativeDiagnosticsRecord? = null
            if (handle.diagnosticsFile.isFile && handle.diagnosticsFile.length() in 1..MAX_DIAGNOSTICS_BYTES) {
                val diagnosticsBytes = runCatching { handle.diagnosticsFile.readBytes() }.getOrNull()
                if (diagnosticsBytes == null) {
                    logger("native capture recovery deferred: diagnostics_read_error")
                    continue
                }
                nativeDiagnostics = NativeDiagnosticsCodec.decode(diagnosticsBytes, core)
                if (nativeDiagnostics == null) logger("native capture diagnostics incomplete: interrupted")
            }
            val javaDecision = NativeJavaDecision.accepts(handle.decisionFile, core)
            val javaDiagnostics = if (javaDecision != false && handle.javaFile.isFile) NativeJavaCodec.decode(handle.javaFile, core) else null
            if (javaDecision == true && javaDiagnostics == null) logger("native Java capture incomplete: interrupted")
            val frozen = runCatching {
                val event = recoveredEvent(identity, core, trace, nativeDiagnostics, javaDiagnostics)
                val prepared = budget.prepareCore(event)
                budget.freeze(prepared, requireNotNull(event.crash?.diagnostics))
            }.getOrNull()
            if (frozen == null) {
                logger("native capture recovery deferred: budget_error")
                continue
            }
            val queued = runCatching { queue.enqueue(frozen, core.occurredAt) || queue.containsIdentical(frozen) }.getOrElse {
                logger("native capture recovery deferred: queue_error")
                false
            }
            if (!queued) {
                logger("native capture recovery deferred: identity_conflict")
                continue
            }
            // 标记只在队列确认后写入，之后任何一次中断都可安全恢复清理。
            val marked = runCatching { AtomicFileWriter.write(handle.committedFile, id); true }.getOrDefault(false)
            if (!marked) {
                logger("native capture cleanup deferred: commit_marker_error")
                continue
            }
            if (!cleanupCommitted(handle)) logger("native capture cleanup deferred: io_error")
            recovered++
        }
        logger("native capture recovery complete: count=$recovered")
        recovered
    }

    /** 仅在原进程及其启动代次仍相同时保留正在写入的暂存。 */
    private fun ownerAlive(identity: NativeCaptureIdentity): Boolean {
        if (!isProcessAlive(identity.pid)) return false
        /** 当前 PID 的进程启动代次。 */
        val currentStart = processStartTicks(identity.pid)
        return identity.processStartTicks == null || currentStart == null || identity.processStartTicks == currentStart
    }

    /** 仅删除全部数据文件均为空且没有提交标记或临时补充的占位记录。 */
    private fun cleanupUnused(handle: NativeCaptureHandle, identityFile: File): Boolean {
        /** 不允许删除任何非空核心或辅助数据。 */
        val files = listOf(handle.coreFile, handle.traceFile, handle.diagnosticsFile, handle.javaFile, handle.decisionFile)
        if (handle.committedFile.exists() || files.any { it.exists() && (!it.isFile || it.length() != 0L) } ||
            root.listFiles().orEmpty().any { it.name.startsWith(handle.javaFile.name + ".tmp-") }) return false
        for (file in files) if (file.exists() && !file.delete()) return false
        /** 以身份文件作为最后删除的占位记录。 */
        val removed = !identityFile.exists() || identityFile.delete()
        if (removed) logger("unused native capture released") else logger("unused native capture cleanup deferred")
        return removed
    }

    /** 只有队列确认标记存在时才删除核心；标记最后删除以支持任意清理中断。 */
    private fun cleanupCommitted(handle: NativeCaptureHandle): Boolean {
        val temporary = root.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(handle.javaFile.name + ".tmp-") }
        val files = listOf(handle.coreFile, handle.traceFile, handle.diagnosticsFile, handle.javaFile,
            handle.decisionFile, handle.identityFile) + temporary
        var complete = true
        for (file in files) if (file.exists() && !file.delete()) complete = false
        if (!complete) return false
        return !handle.committedFile.exists() || handle.committedFile.delete()
    }

    /** 从崩溃核心与原身份构造合法回退事件，不使用新进程的运行数据。 */
    private fun recoveredEvent(identity: NativeCaptureIdentity, core: NativeCoreRecord, trace: NativeTraceRecord?,
                               nativeDiagnostics: NativeDiagnosticsRecord?, java: NativeJavaDiagnostics?): CrashEvent {
        val mainThread = core.systemTid == core.pid
        val registers = if (core.registers.isEmpty()) NativeRegisters(status = "unavailable", reason = "interrupted")
            else NativeRegisters(status = "ok", capturedAt = core.occurredAt, values = core.registers)
        val native = NativeCrash(abi = core.abi, systemTid = core.systemTid, threadName = trace?.crashedThreadName, mainThread = mainThread,
            signal = NativeSignal(core.signal, signalName(core.signal), core.code, faultAddress = core.faultAddress,
                abortMessage = trace?.abortMessage),
            registers = registers, modules = trace?.modules.orEmpty(),
            backtrace = trace?.backtrace ?: NativeBacktrace(status = "unavailable", reason = "interrupted"),
            javaStack = java?.javaStack ?: NativeJavaStack(status = "unavailable", reason = "interrupted"))
        val diagnostics = CrashDiagnostics(
            crashedThread = java?.crashedThread?.copy(systemTid = core.systemTid, mainThread = mainThread)
                ?: CrashedThread(status = "ok", systemTid = core.systemTid, name = trace?.crashedThreadName,
                    mainThread = mainThread, capturedAt = core.occurredAt),
            otherThreads = java?.otherThreads ?: OtherThreads(status = "skipped", reason = "interrupted"),
            nativeThreads = trace?.nativeThreads ?: NativeThreads(status = "skipped", reason = "interrupted"),
            logcat = nativeDiagnostics?.logcat ?: CrashLogcat(status = "skipped", reason = "interrupted"),
            memory = mergeMemory(nativeDiagnostics?.memory, java?.memory),
            openFiles = nativeDiagnostics?.openFiles ?: OpenFiles(status = "skipped", reason = "interrupted"),
            process = java?.process ?: identity.process?.copy(status = "partial", reason = "interrupted", foreground = null)
                ?: CrashProcess(status = "partial", reason = "interrupted", pid = core.pid),
            device = java?.device ?: identity.device ?: CrashDevice(status = "skipped", reason = "interrupted"),
        )
        return identity.event.copy(occurredAt = core.occurredAt,
            crash = CrashPayload(kind = "native", nativeCrash = native, diagnostics = diagnostics))
    }

    /** 合并同一原进程的独立内存分区，缺少任一来源时标明部分采集。 */
    private fun mergeMemory(native: CrashMemory?, java: CrashMemory?): CrashMemory {
        if (native == null && java == null) return CrashMemory(status = "skipped", reason = "interrupted")
        val complete = native?.status == "ok" && java?.status == "ok"
        val base = native ?: CrashMemory(status = "unavailable", reason = "interrupted")
        val hasData = listOf(base.totalPssBytes, base.rssBytes, base.vssBytes, base.swapBytes,
            java?.javaHeapUsedBytes, java?.javaHeapMaxBytes, java?.javaHeapPssBytes, java?.nativeHeapPssBytes,
            java?.nativeHeapAllocatedBytes, java?.totalPssBytes).any { it != null } ||
            base.meminfo != null || base.statusText != null || base.limits != null
        val status = when {
            complete -> "ok"
            hasData -> "partial"
            native?.status == "skipped" && (java == null || java.status == "skipped") -> "skipped"
            else -> "unavailable"
        }
        return base.copy(status = status,
            reason = if (complete) null else native?.reason ?: java?.reason ?: "interrupted",
            truncated = base.truncated || (java?.truncated == true), capturedAt = base.capturedAt ?: java?.capturedAt,
            javaHeapUsedBytes = java?.javaHeapUsedBytes, javaHeapMaxBytes = java?.javaHeapMaxBytes,
            javaHeapPssBytes = java?.javaHeapPssBytes, nativeHeapPssBytes = java?.nativeHeapPssBytes,
            nativeHeapAllocatedBytes = java?.nativeHeapAllocatedBytes, totalPssBytes = base.totalPssBytes ?: java?.totalPssBytes)
    }

    /** 仅接受当前协议、原事件名匹配和正 PID 的身份文件。 */
    private fun readIdentity(file: File, id: String): NativeCaptureIdentity? {
        if (!id.matches(ID_PATTERN) || file.length() !in 1..MAX_IDENTITY_BYTES.toLong()) return null
        /** 已限制长度的身份信封文本。 */
        val contents = readIdentityText(file)
        return runCatching {
        val envelope = requireNotNull(gson.fromJson(contents, NativeIdentityEnvelope::class.java))
        require(envelope.version == VERSION && envelope.sha256.matches(Regex("[a-f0-9]{64}")) &&
            MessageDigest.isEqual(envelope.sha256.toByteArray(StandardCharsets.US_ASCII),
                identityDigest(envelope.content).toByteArray(StandardCharsets.US_ASCII)))
        val identity = requireNotNull(gson.fromJson(envelope.content, NativeCaptureIdentity::class.java))
        require(identity.version == VERSION && identity.pid > 0 && (identity.process == null || identity.process.pid == identity.pid) && identity.event.eventId == id &&
            identity.event.eventType == "crash" && identity.event.crash == null)
        identity
        }.getOrNull()
    }

    /** 计算身份字节的固定长度摘要，用于拒绝损坏或半写的身份。 */
    private fun identityDigest(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 将损坏的身份改名为不可重试记录，保留核心文件供人工排查。 */
    private fun quarantine(file: File) {
        val target = File(root, file.name.removeSuffix(IDENTITY_SUFFIX) + INVALID_SUFFIX)
        if (!target.exists() && file.renameTo(target)) logger("native capture quarantined: invalid_record")
        else logger("native capture quarantine deferred: io_error")
    }

    /** 将约束内事件 ID 映射为本目录的固定文件名。 */
    private fun handle(id: String): NativeCaptureHandle = NativeCaptureHandle(id, File(root, id + IDENTITY_SUFFIX),
        File(root, id + CORE_SUFFIX), File(root, id + TRACE_SUFFIX), File(root, id + DIAGNOSTICS_SUFFIX),
        File(root, id + JAVA_SUFFIX), File(root, id + DECISION_SUFFIX), File(root, id + COMMITTED_SUFFIX))

    /** 识别暂存、待处理和隔离文件所属的同一个事件。 */
    private fun stageId(name: String): String? {
        val suffix = listOf(IDENTITY_SUFFIX, CORE_SUFFIX, TRACE_SUFFIX, DIAGNOSTICS_SUFFIX, JAVA_SUFFIX, DECISION_SUFFIX,
            COMMITTED_SUFFIX, INVALID_SUFFIX)
            .firstOrNull { name.endsWith(it) } ?: return null
        return name.removeSuffix(suffix).takeIf { it.matches(ID_PATTERN) }
    }

    /** 串行化同进程实例并使用文件锁协调其他进程的暂存管理。 */
    private fun <T> locked(action: () -> T): T = synchronized(LOCAL_LOCK) {
        FileOutputStream(File(root, LOCK_FILE), true).channel.use { channel -> channel.lock().use { action() } }
    }

    /** 使用已支持的致命信号名称，不根据其他现场文本推断。 */
    private fun signalName(signal: Int): String = when (signal) {
        4 -> "SIGILL"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        11 -> "SIGSEGV"
        else -> error("Unsupported native signal")
    }

    /** 暂存上限是安全边界，不是成功采集承诺。 */
    private companion object {
        const val TAG = "NativeCaptureStaging"
        const val VERSION = 1
        const val MAX_RECORDS = 32
        const val MAX_RECORD_BYTES = 512L * 1024
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        const val MAX_IDENTITY_BYTES = 64 * 1024
        const val MIN_CORE_BYTES = 60L
        const val MAX_CORE_BYTES = 60L + 64 * 16
        const val MAX_TRACE_BYTES = 320L * 1024
        const val MAX_DIAGNOSTICS_BYTES = 96L * 1024
        const val LOCK_FILE = ".staging.lock"
        const val IDENTITY_SUFFIX = ".identity"
        const val CORE_SUFFIX = ".core"
        const val TRACE_SUFFIX = ".trace"
        const val DIAGNOSTICS_SUFFIX = ".diagnostics"
        const val JAVA_SUFFIX = ".java"
        const val DECISION_SUFFIX = ".decision"
        const val COMMITTED_SUFFIX = ".committed"
        const val INVALID_SUFFIX = ".invalid"
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val LOCAL_LOCK = Any()
    }
}

/** 只解析 Linux stat 的进程启动计数；无法读取或解析时返回未知。 */
private fun readProcessStartTicks(pid: Int): Long? {
    return runCatching {
        if (pid <= 0) return@runCatching null
        val stat = File("/proc/$pid/stat").readText(StandardCharsets.US_ASCII)
        val end = stat.lastIndexOf(')')
        if (end < 0 || end + 2 >= stat.length) return@runCatching null
        stat.substring(end + 2).trim().split(Regex("\\s+"))[19].toLongOrNull()?.takeIf { it >= 0 }
    }.getOrNull()
}
