package com.shanshui.performance.crash

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.network.CoreTruncation
import com.shanshui.performance.network.CrashBatchRequest
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.LogcatBuffer
import com.shanshui.performance.network.NativeBacktrace
import com.shanshui.performance.network.NativeCrash
import com.shanshui.performance.network.NativeThreads
import com.shanshui.performance.network.OtherThreads
import java.nio.charset.StandardCharsets

/** 在事件冻结之前按实际 JSON UTF-8 字节保护核心异常和批次上限。 */
internal class CrashEventBudget(
    /** 客户端配置的完整批次大小上限。 */
    private val maxBatchBytes: Int,
    /** 与上传器一致的 JSON 编码。 */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    /** 先裁剪核心异常，并为中断恢复和单事件批次外壳预留空间。 */
    fun prepareCore(event: CrashEvent): CrashEvent {
        require(event.crash != null && event.eventType == "crash") { "Crash core is required" }
        var current = if (event.crash.kind == "native") normalizeNativeCore(event) else event
        // 为设备分区的最小状态预留空间，冻结后不能改写已经落盘的身份。
        val minimumDeviceBytes = encodedBytes(minimalDiagnostics(CrashDiagnostics(), preserveSkipped = false).device)
        while (encodedBytes(current.crash?.diagnostics?.crashedThread).toLong() +
            encodedBytes(current.crash?.diagnostics?.process) + minimumDeviceBytes > METADATA_BYTES
        ) {
            val crash = requireNotNull(current.crash)
            val metadata = requireNotNull(crash.diagnostics)
            val reduced = trimMetadata(metadata) ?: throw IllegalArgumentException("Minimum crash identity exceeds metadata budget")
            current = current.copy(crash = crash.copy(diagnostics = reduced))
        }
        while (!fits(withReservedStatuses(current))) {
            val crash = requireNotNull(current.crash)
            if (crash.kind == "native") {
                val reduced = trimNativeCore(requireNotNull(crash.nativeCrash))
                    ?: throw IllegalArgumentException("Minimum native crash core exceeds the batch limit")
                current = current.copy(crash = crash.copy(nativeCrash = reduced,
                    diagnostics = (crash.diagnostics ?: CrashDiagnostics()).copy(
                        coreTruncation = CoreTruncation(truncated = true, reason = "size_limit"),
                    )))
                continue
            }
            val nodes = requireNotNull(crash.throwableChain) { "JVM crash chain is required" }.toMutableList()
            val messageIndex = nodes.indexOfLast { !it.message.isNullOrEmpty() }
            val frameIndex = nodes.indexOfLast { it.frames.size > 1 }
            when {
                messageIndex >= 0 -> {
                    // 优先逐步缩短异常消息，不改变异常类型及堆栈。
                    val node = nodes[messageIndex]
                    val message = requireNotNull(node.message)
                    nodes[messageIndex] = node.copy(message = safePrefix(message, message.length / 2).ifEmpty { null })
                }
                frameIndex >= 0 -> {
                    val node = nodes[frameIndex]
                    nodes[frameIndex] = node.copy(frames = node.frames.dropLast(1))
                }
                nodes.size > 1 -> nodes.removeAt(nodes.lastIndex)
                else -> throw IllegalArgumentException("Minimum crash core exceeds the batch limit")
            }
            val originalDiagnostics = crash.diagnostics
            current = current.copy(crash = crash.copy(
                throwableChain = nodes,
                diagnostics = (originalDiagnostics ?: CrashDiagnostics()).copy(
                    coreTruncation = CoreTruncation(truncated = true, reason = "size_limit"),
                ),
            ))
        }
        return current
    }

    /** 保存诊断前执行数量、分区和完整事件预算，按固定顺序丢弃低优先级内容。 */
    fun freeze(core: CrashEvent, snapshot: CrashDiagnostics): CrashEvent {
        val crash = requireNotNull(core.crash)
        var diagnostics = normalize(snapshot.copy(coreTruncation = crash.diagnostics?.coreTruncation), crash.nativeCrash)
        while (true) {
            val event = core.copy(crash = crash.copy(diagnostics = diagnostics))
            val threadsTooLarge = encodedBytes(diagnostics.otherThreads).toLong() + encodedBytes(diagnostics.nativeThreads) > THREAD_BYTES
            val logsTooLarge = encodedBytes(diagnostics.logcat) > LOG_BYTES
            val memoryTooLarge = encodedBytes(diagnostics.memory) > MEMORY_BYTES
            val filesTooLarge = encodedBytes(diagnostics.openFiles) > FILE_BYTES
            val metadataTooLarge = encodedBytes(diagnostics.crashedThread).toLong() +
                encodedBytes(diagnostics.process) + encodedBytes(diagnostics.device) > METADATA_BYTES
            if (fits(event) && encodedBytes(diagnostics) <= DIAGNOSTIC_BYTES &&
                !threadsTooLarge && !logsTooLarge && !memoryTooLarge && !filesTooLarge && !metadataTooLarge
            ) return event

            // 分区自身超限时先压缩该分区；总预算不足时先丢旧日志、FD 再丢其他线程。
            val next = when {
                logsTooLarge -> trimLog(diagnostics)
                filesTooLarge -> trimFiles(diagnostics)
                threadsTooLarge -> trimThreadData(diagnostics)
                memoryTooLarge -> trimMemory(diagnostics)
                metadataTooLarge -> trimMetadata(diagnostics, preserveIdentity = true)
                else -> trimLog(diagnostics, preserveLast = true) ?: trimFiles(diagnostics) ?: trimThreadData(diagnostics)
                    ?: trimMemory(diagnostics) ?: trimMetadata(diagnostics, preserveIdentity = true) ?: trimLog(diagnostics)
            } ?: minimalDiagnostics(diagnostics).copy(
                crashedThread = crash.diagnostics?.crashedThread ?: minimalDiagnostics(diagnostics).crashedThread,
                process = crash.diagnostics?.process ?: minimalDiagnostics(diagnostics).process,
            )
            check(next != diagnostics) { "Minimum diagnostic status exceeds the batch limit" }
            diagnostics = next
        }
    }

    /** 统计 JSON 对象经 UTF-8 编码后的字节，空值不占分区预算。 */
    internal fun encodedBytes(value: Any?): Int =
        if (value == null) 0 else gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size

    /** 同时检查单事件目标和含固定长度 UUID 的单事件批次。 */
    private fun fits(event: CrashEvent): Boolean = encodedBytes(event) <= EVENT_BYTES &&
        encodedBytes(CrashBatchRequest(RESERVED_REQUEST_ID, listOf(event))) <= maxBatchBytes

    /** 核心阶段预留七个最长的不可用状态，确保中断恢复不会超批次。 */
    private fun withReservedStatuses(event: CrashEvent): CrashEvent {
        val crash = requireNotNull(event.crash)
        val snapshot = crash.diagnostics ?: CrashDiagnostics()
        val reserved = minimalDiagnostics(snapshot, preserveSkipped = false).let {
            if (crash.kind == "native") it.copy(nativeThreads = NativeThreads(status = "unavailable", reason = "permission_denied")) else it
        }
        return event.copy(crash = crash.copy(diagnostics = reserved.copy(
            crashedThread = snapshot.crashedThread ?: reserved.crashedThread,
            process = snapshot.process ?: reserved.process,
        )))
    }

    /** 数量和单行先做有限归一化，保留主线程及最近的日志行。 */
    private fun normalize(snapshot: CrashDiagnostics, native: NativeCrash?): CrashDiagnostics {
        var result = snapshot
        val oldThreads = result.otherThreads
        if (oldThreads != null) {
            val normalizedThreads = oldThreads.threads.map { thread ->
                if (thread.frames.size <= FRAME_COUNT) thread
                else thread.copy(frames = thread.frames.take(FRAME_COUNT), truncated = true)
            }
            val mainIndices = normalizedThreads.indices.filter { normalizedThreads[it].mainThread }
            val selected = (mainIndices + normalizedThreads.indices.filter { it !in mainIndices })
                .take(THREAD_COUNT).toSet()
            val kept = normalizedThreads.filterIndexed { index, _ -> index in selected }
            if (kept.size != oldThreads.threads.size || kept != oldThreads.threads) {
                result = result.copy(otherThreads = oldThreads.copy(
                    status = "partial", reason = oldThreads.reason ?: "count_limit", truncated = true,
                    omittedCount = oldThreads.observedCount?.let { (it - kept.size).coerceAtLeast(0) },
                    threads = kept,
                ))
            }
        }
        if (native != null) result = normalizeNativeThreads(result, native)
        val logs = result.logcat
        if (logs != null) {
            val main = normalizeBuffer(logs.main, 200)
            val system = normalizeBuffer(logs.system, 50)
            val events = normalizeBuffer(logs.events, 50)
            if (main != logs.main || system != logs.system || events != logs.events) {
                result = result.copy(logcat = logs.copy(
                    status = "partial", reason = logs.reason ?: "count_limit", truncated = true,
                    main = main, system = system, events = events,
                ))
            }
        }
        val files = result.openFiles
        if (files != null && files.entries.size > FILE_COUNT) {
            result = result.copy(openFiles = files.copy(
                status = "partial", reason = files.reason ?: "count_limit", truncated = true,
                entries = files.entries.take(FILE_COUNT),
            ))
        }
        return result
    }

    /** 先保留主栈和其模块，确保主回溯与模块字典均符合协议数量上限。 */
    private fun normalizeNativeCore(event: CrashEvent): CrashEvent {
        val crash = requireNotNull(event.crash)
        val native = requireNotNull(crash.nativeCrash)
        val mainFrames = native.backtrace.frames.take(NATIVE_MAIN_FRAMES)
        val primaryIds = mainFrames.mapNotNull { it.moduleId }.toSet()
        val modules = (native.modules.filter { it.id in primaryIds } + native.modules.filter { it.id !in primaryIds })
            .distinctBy { it.id }.take(NATIVE_MODULES)
        val moduleIds = modules.map { it.id }.toSet()
        val frames = mainFrames.mapIndexed { index, frame ->
            if (frame.moduleId != null && frame.moduleId !in moduleIds) frame.copy(index = index, moduleId = null, relativePc = null)
            else frame.copy(index = index)
        }
        val javaStack = native.javaStack?.let { stack ->
            if (stack.frames.size <= NATIVE_MAIN_FRAMES) stack else stack.copy(status = "partial", reason = "count_limit",
                truncated = true, frames = stack.frames.take(NATIVE_MAIN_FRAMES))
        }
        val truncated = modules.size != native.modules.size || frames != native.backtrace.frames || javaStack != native.javaStack
        val trace = if (frames != native.backtrace.frames) native.backtrace.copy(status = "partial", reason = "count_limit",
            truncated = true, frames = frames) else native.backtrace
        if (!truncated) return event
        return event.copy(crash = crash.copy(nativeCrash = native.copy(modules = modules, backtrace = trace, javaStack = javaStack),
            diagnostics = (crash.diagnostics ?: CrashDiagnostics()).copy(coreTruncation = CoreTruncation(true, "size_limit"))))
    }

    /** 缩小 Native 可选文本和主帧，始终保留信号、身份与寄存器。 */
    private fun trimNativeCore(native: NativeCrash): NativeCrash? {
        val signal = native.signal
        if (signal.abortMessage != null) return native.copy(signal = signal.copy(abortMessage = null))
        if (native.threadName != null) return native.copy(threadName = null)
        val modules = native.modules.toMutableList()
        val path = modules.indexOfLast { it.path != null }
        if (path >= 0) {
            modules[path] = modules[path].copy(path = null)
            return native.copy(modules = modules)
        }
        val frames = native.backtrace.frames.toMutableList()
        val function = frames.indexOfLast { it.functionName != null }
        if (function >= 0) {
            frames[function] = frames[function].copy(functionName = null, functionOffset = null)
            return native.copy(backtrace = native.backtrace.copy(status = "partial", reason = "size_limit", truncated = true, frames = frames))
        }
        val java = native.javaStack
        if (java != null && java.frames.isNotEmpty()) return native.copy(javaStack = java.copy(status = "partial", reason = "size_limit",
            truncated = true, frames = java.frames.dropLast(1)))
        if (frames.isNotEmpty()) {
            frames.removeAt(frames.lastIndex)
            val used = frames.mapNotNull { it.moduleId }.toSet()
            return native.copy(backtrace = native.backtrace.copy(status = "partial", reason = "size_limit", truncated = true, frames = frames),
                modules = modules.filter { it.id in used })
        }
        if (modules.isNotEmpty()) return native.copy(modules = emptyList())
        return null
    }

    /** Native 和 Java 共用线程数量预算，优先保留已知主线程并修复模块引用。 */
    private fun normalizeNativeThreads(snapshot: CrashDiagnostics, native: NativeCrash): CrashDiagnostics {
        val nativeGroup = snapshot.nativeThreads
        val javaGroup = snapshot.otherThreads
        val nativeThreads = nativeGroup?.threads.orEmpty().map { thread ->
            val frameLimit = thread.backtrace.frames.take(FRAME_COUNT)
            val moduleIds = native.modules.map { it.id }.toSet()
            val frames = frameLimit.mapIndexed { index, frame ->
                if (frame.moduleId != null && frame.moduleId !in moduleIds) frame.copy(index = index, moduleId = null, relativePc = null)
                else frame.copy(index = index)
            }
            if (frames == thread.backtrace.frames) thread else thread.copy(backtrace = thread.backtrace.copy(
                status = "partial", reason = "count_limit", truncated = true, frames = frames))
        }
        val javaThreads = javaGroup?.threads.orEmpty()
        val candidates = nativeThreads.indices.map { true to it } + javaThreads.indices.map { false to it }
        val chosen = candidates.sortedBy { (isNative, index) ->
            if (if (isNative) nativeThreads[index].mainThread == true else javaThreads[index].mainThread) 0 else 1
        }.take(THREAD_COUNT).toSet()
        val keptNative = nativeThreads.filterIndexed { index, _ -> (true to index) in chosen }
        val keptJava = javaThreads.filterIndexed { index, _ -> (false to index) in chosen }
        var result = snapshot
        if (nativeGroup != null && (keptNative != nativeGroup.threads)) result = result.copy(nativeThreads = nativeGroup.copy(
            status = "partial", reason = nativeGroup.reason ?: "count_limit", truncated = true,
            omittedCount = nativeGroup.observedCount?.let { (it - keptNative.size).coerceAtLeast(0) }, threads = keptNative))
        if (javaGroup != null && keptJava != javaGroup.threads) result = result.copy(otherThreads = javaGroup.copy(
            status = "partial", reason = javaGroup.reason ?: "count_limit", truncated = true,
            omittedCount = javaGroup.observedCount?.let { (it - keptJava.size).coerceAtLeast(0) }, threads = keptJava))
        return result
    }

    /** 截断单行 JSON 字节并只保留指定数量的最新日志。 */
    private fun normalizeBuffer(buffer: LogcatBuffer?, maxLines: Int): LogcatBuffer? {
        if (buffer == null) return null
        val lines = buffer.lines.takeLast(maxLines).map { fitText(it, LOG_LINE_BYTES) }
        if (lines == buffer.lines) return buffer
        return buffer.copy(status = "partial", reason = buffer.reason ?: "size_limit", truncated = true, lines = lines)
    }

    /** 使用二分查找截取不超过 JSON 字节上限的 Unicode 前缀。 */
    private fun fitText(value: String, maxBytes: Int): String {
        if (encodedBytes(value) <= maxBytes) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (encodedBytes(safePrefix(value, middle)) <= maxBytes) low = middle else high = middle - 1
        }
        return safePrefix(value, low)
    }

    /** 避免在 UTF-16 代理对中间截断文本。 */
    private fun safePrefix(value: String, length: Int): String {
        var end = length.coerceIn(0, value.length)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) end--
        return value.substring(0, end)
    }

    /** 删除一个最旧日志行，并保留该缓冲区及总分区的截断状态。 */
    private fun trimLog(snapshot: CrashDiagnostics, preserveLast: Boolean = false): CrashDiagnostics? {
        val logcat = snapshot.logcat ?: return null
        val count = (logcat.main?.lines?.size ?: 0) + (logcat.system?.lines?.size ?: 0) +
            (logcat.events?.lines?.size ?: 0)
        if (preserveLast && count <= 1) return null
        val selected = when {
            !logcat.main?.lines.isNullOrEmpty() -> "main"
            !logcat.system?.lines.isNullOrEmpty() -> "system"
            !logcat.events?.lines.isNullOrEmpty() -> "events"
            else -> return null
        }
        fun trim(buffer: LogcatBuffer): LogcatBuffer = buffer.copy(
            status = "partial", reason = buffer.reason ?: "size_limit", truncated = true, lines = buffer.lines.drop(1),
        )
        val shortened = when (selected) {
            "main" -> logcat.copy(main = trim(requireNotNull(logcat.main)))
            "system" -> logcat.copy(system = trim(requireNotNull(logcat.system)))
            else -> logcat.copy(events = trim(requireNotNull(logcat.events)))
        }
        return snapshot.copy(logcat = shortened.copy(status = "partial", reason = logcat.reason ?: "size_limit", truncated = true))
    }

    /** 从尾部移除一个 FD 明细，总数保持原观测值。 */
    private fun trimFiles(snapshot: CrashDiagnostics): CrashDiagnostics? {
        val files = snapshot.openFiles ?: return null
        if (files.entries.isEmpty()) return null
        return snapshot.copy(openFiles = files.copy(
            status = "partial", reason = files.reason ?: "size_limit", truncated = true,
            entries = files.entries.dropLast(1),
        ))
    }

    /** 优先移除非主线程，必要时再裁剪保留下来的线程帧。 */
    private fun trimThreads(snapshot: CrashDiagnostics): CrashDiagnostics? {
        val threads = snapshot.otherThreads ?: return null
        val index = threads.threads.indexOfLast { !it.mainThread }
        val updated = threads.threads.toMutableList()
        if (index >= 0) {
            updated.removeAt(index)
        } else {
            val frameIndex = updated.indexOfLast { it.frames.isNotEmpty() }
            if (frameIndex < 0) return null
            val thread = updated[frameIndex]
            updated[frameIndex] = thread.copy(frames = thread.frames.dropLast(1), truncated = true)
        }
        return snapshot.copy(otherThreads = threads.copy(
            status = "partial", reason = threads.reason ?: "size_limit", truncated = true,
            omittedCount = threads.observedCount?.let { (it - updated.size).coerceAtLeast(0) },
            threads = updated,
        ))
    }

    /** 两类线程共享空间时先移除非主线程，再裁剪主线程的帧。 */
    private fun trimThreadData(snapshot: CrashDiagnostics): CrashDiagnostics? {
        if (snapshot.otherThreads?.threads?.any { !it.mainThread } == true) return trimThreads(snapshot)
        if (snapshot.nativeThreads?.threads?.any { it.mainThread != true } == true) return trimNativeThreads(snapshot)
        return trimThreads(snapshot) ?: trimNativeThreads(snapshot)
    }

    /** 从 Native 集合尾部移除非主线程或缩短仍保留的主线程回溯。 */
    private fun trimNativeThreads(snapshot: CrashDiagnostics): CrashDiagnostics? {
        val group = snapshot.nativeThreads ?: return null
        val threads = group.threads.toMutableList()
        val removable = threads.indexOfLast { it.mainThread != true }
        if (removable >= 0) {
            threads.removeAt(removable)
        } else {
            val frameOwner = threads.indexOfLast { it.backtrace.frames.isNotEmpty() }
            if (frameOwner < 0) return null
            val thread = threads[frameOwner]
            threads[frameOwner] = thread.copy(backtrace = thread.backtrace.copy(status = "partial", reason = "size_limit",
                truncated = true, frames = thread.backtrace.frames.dropLast(1)))
        }
        return snapshot.copy(nativeThreads = group.copy(status = "partial", reason = group.reason ?: "size_limit", truncated = true,
            omittedCount = group.observedCount?.let { (it - threads.size).coerceAtLeast(0) }, threads = threads))
    }

    /** 在保留数值摘要的前提下逐项删除较大的 proc 原文。 */
    private fun trimMemory(snapshot: CrashDiagnostics): CrashDiagnostics? {
        val memory = snapshot.memory ?: return null
        val shortened = when {
            memory.meminfo != null -> memory.copy(meminfo = null)
            memory.statusText != null -> memory.copy(statusText = null)
            memory.limits != null -> memory.copy(limits = null)
            else -> return null
        }
        return snapshot.copy(memory = shortened.copy(status = "partial", reason = memory.reason ?: "size_limit", truncated = true))
    }

    /** 优先缩短可选设备与进程文本，保留线程身份和数值进程号。 */
    private fun trimMetadata(snapshot: CrashDiagnostics, preserveIdentity: Boolean = false): CrashDiagnostics? {
        val device = snapshot.device
        if (device != null && (device.buildFingerprint != null || device.manufacturer != null || device.brand != null || device.abis.isNotEmpty())) {
            return snapshot.copy(device = device.copy(status = "partial", reason = device.reason ?: "size_limit", truncated = true,
                buildFingerprint = null, manufacturer = null, brand = null, abis = emptyList()))
        }
        if (preserveIdentity) {
            // 核心文件已经固定身份，设备仍超预算时只退回该分区的最小状态。
            val minimumDevice = minimalDiagnostics(snapshot).device
            return if (device != null && device != minimumDevice) snapshot.copy(device = minimumDevice) else null
        }
        val process = snapshot.process
        if (process?.name != null) return snapshot.copy(process = process.copy(
            status = "partial", reason = process.reason ?: "size_limit", truncated = true, name = null,
        ))
        val thread = snapshot.crashedThread
        if (thread?.name != null && thread.name.length > 32) return snapshot.copy(crashedThread = thread.copy(
            status = "partial", reason = thread.reason ?: "size_limit", truncated = true,
            name = safePrefix(thread.name, 32),
        ))
        return null
    }

    /** 所有辅助数据均放不下时保留已尝试但无可用内容的安全状态。 */
    private fun minimalDiagnostics(snapshot: CrashDiagnostics, preserveSkipped: Boolean = true): CrashDiagnostics {
        /** 原本未尝试的分区必须保持 skipped，其他分区说明结果因空间不可用。 */
        fun status(original: String?): String = if (preserveSkipped && (original == null || original == "skipped")) "skipped" else "unavailable"
        /** 保留跳过时的原始安全原因，不把 OOM 等情况改写为空间问题。 */
        fun reason(originalStatus: String?, originalReason: String?): String = when {
            !preserveSkipped -> "permission_denied" // 预留最长安全原因，容纳后续中断状态。
            originalStatus == "skipped" -> originalReason ?: "size_limit"
            else -> "size_limit"
        }
        return CrashDiagnostics(
        coreTruncation = snapshot.coreTruncation,
        crashedThread = com.shanshui.performance.network.CrashedThread(
            status = status(snapshot.crashedThread?.status), reason = reason(snapshot.crashedThread?.status, snapshot.crashedThread?.reason)),
        otherThreads = OtherThreads(status = status(snapshot.otherThreads?.status),
            reason = reason(snapshot.otherThreads?.status, snapshot.otherThreads?.reason)),
        logcat = CrashLogcat(status = status(snapshot.logcat?.status), reason = reason(snapshot.logcat?.status, snapshot.logcat?.reason)),
        memory = com.shanshui.performance.network.CrashMemory(status = status(snapshot.memory?.status),
            reason = reason(snapshot.memory?.status, snapshot.memory?.reason)),
        openFiles = com.shanshui.performance.network.OpenFiles(status = status(snapshot.openFiles?.status),
            reason = reason(snapshot.openFiles?.status, snapshot.openFiles?.reason)),
        process = com.shanshui.performance.network.CrashProcess(status = status(snapshot.process?.status),
            reason = reason(snapshot.process?.status, snapshot.process?.reason)),
        device = com.shanshui.performance.network.CrashDevice(status = status(snapshot.device?.status),
            reason = reason(snapshot.device?.status, snapshot.device?.reason)),
        nativeThreads = snapshot.nativeThreads?.let { NativeThreads(status = status(it.status), reason = reason(it.status, it.reason)) },
        )
    }

    /** 固定内部上限，后续设备验收不得将其解释为性能承诺。 */
    private companion object {
        const val RESERVED_REQUEST_ID = "00000000-0000-4000-8000-000000000000"
        const val EVENT_BYTES = 240 * 1024
        const val DIAGNOSTIC_BYTES = 128 * 1024
        const val THREAD_BYTES = 48 * 1024
        const val LOG_BYTES = 48 * 1024
        const val MEMORY_BYTES = 16 * 1024
        const val FILE_BYTES = 12 * 1024
        const val METADATA_BYTES = 4 * 1024
        const val LOG_LINE_BYTES = 2 * 1024
        const val THREAD_COUNT = 64
        const val FRAME_COUNT = 32
        const val FILE_COUNT = 128
        const val NATIVE_MAIN_FRAMES = 128
        const val NATIVE_MODULES = 128
    }
}
