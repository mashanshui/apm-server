package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.OtherThreads
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashDevice
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.shanshui.performance.core.AtomicFileWriter
import com.shanshui.performance.core.DeviceIdentityStore

internal data class CrashQueueRecord(
    val event: CrashEvent,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
)

internal data class CrashQueueItem(
    val record: CrashQueueRecord,
    val file: File,
)

internal class FileCrashQueue(
    private val root: File,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val maxDeadLetters: Int = 100,
) {
    /** 未冻结的核心异常；上传器不会枚举此目录。 */
    private val capturingDirectory = File(root, CAPTURING_DIRECTORY_NAME)
    private val eventsDirectory = File(root, EVENTS_DIRECTORY_NAME)
    private val deadLetterDirectory = File(root, DEAD_LETTER_DIRECTORY_NAME)

    init {
        require(root.exists() || root.mkdirs()) { "Unable to create crash queue directory" }
        require(capturingDirectory.exists() || capturingDirectory.mkdirs()) {
            "Unable to create crash capturing directory"
        }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create crash event directory"
        }
        require(deadLetterDirectory.exists() || deadLetterDirectory.mkdirs()) {
            "Unable to create crash dead-letter directory"
        }
    }

    /** 先原子保存固定身份的核心异常，完成补充采集前不允许上传。 */
    @Synchronized
    fun beginCapture(event: CrashEvent, createdAtMillis: Long = System.currentTimeMillis()): Boolean {
        require(event.eventType == "crash" && event.crash != null) { "Capturing requires a crash event" }
        // 同一事件无论已冻结还是正在采集，都不能被另一份现场覆盖。
        val target = captureFile(event.eventId)
        if (target.exists() || eventFile(event.eventId).exists()) return false
        AtomicFileWriter.write(target, gson.toJson(CrashQueueRecord(event, createdAtMillis)))
        return true
    }

    /** 只允许为已保存的相同核心异常冻结诊断，成功后再清理采集中记录。 */
    @Synchronized
    fun freezeCapture(event: CrashEvent): Boolean {
        val capture = captureFile(event.eventId)
        val target = eventFile(event.eventId)
        if (target.exists()) {
            // 崩溃发生在目标写入完成之后时，已有最终记录必须优先。
            if (capture.exists()) capture.delete()
            return false
        }
        val original = readRecord(capture) ?: return false
        require(coreEvent(original.event) == coreEvent(event)) { "Frozen crash core must match the captured core" }
        require(original.event.crash?.diagnostics?.coreTruncation == event.crash?.diagnostics?.coreTruncation) {
            "Core truncation must not change during diagnostics"
        }
        require((original.event.crash?.diagnostics?.crashedThread == null ||
            original.event.crash.diagnostics.crashedThread == event.crash?.diagnostics?.crashedThread) &&
            (original.event.crash?.diagnostics?.process == null ||
                original.event.crash.diagnostics.process == event.crash?.diagnostics?.process)
        ) { "Captured thread and process identity must not change" }
        AtomicFileWriter.write(target, gson.toJson(original.copy(event = event)))
        capture.delete()
        return true
    }

    /** 启动时恢复中断的原始现场，不使用新进程身份或重新采集。 */
    @Synchronized
    fun recoverCaptures(): Int {
        var recovered = 0
        // 启动恢复先于上传，因此可以清理上一进程留下的未完成最终记录。
        eventsDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.contains(".json.tmp-") }
            .forEach { it.delete() }
        capturingDirectory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.contains(".json.tmp-")) {
                // 原子写入尚未完成的临时文件不能作为已持久化记录发布。
                file.delete()
                return@forEach
            }
            if (file.extension != EVENT_FILE_EXTENSION) return@forEach
            val record = readRecord(file)
            if (record == null || record.event.eventType != "crash" || record.event.crash == null) {
                moveMalformedFile(file)
                return@forEach
            }
            if (eventFile(record.event.eventId).exists()) {
                file.delete()
                return@forEach
            }
            val interrupted = record.event.copy(crash = record.event.crash.copy(
                diagnostics = interruptedDiagnostics(record.event.crash.diagnostics),
            ))
            if (freezeCapture(interrupted)) recovered++
        }
        return recovered
    }

    /** 比较核心内容时不把补充诊断视为可修改的事件身份。 */
    private fun coreEvent(event: CrashEvent): CrashEvent =
        event.copy(crash = event.crash?.copy(diagnostics = null))

    /** 中断后各分区表示未完成，保留核心异常原有裁剪说明。 */
    private fun interruptedDiagnostics(existing: CrashDiagnostics?): CrashDiagnostics = CrashDiagnostics(
        coreTruncation = existing?.coreTruncation,
        crashedThread = existing?.crashedThread ?: CrashedThread(status = "skipped", reason = "interrupted"),
        otherThreads = OtherThreads(status = "skipped", reason = "interrupted"),
        logcat = CrashLogcat(status = "skipped", reason = "interrupted"),
        memory = CrashMemory(status = "skipped", reason = "interrupted"),
        openFiles = OpenFiles(status = "skipped", reason = "interrupted"),
        process = existing?.process ?: CrashProcess(status = "skipped", reason = "interrupted"),
        device = CrashDevice(status = "skipped", reason = "interrupted"),
    )

    @Synchronized
    fun enqueue(event: CrashEvent, createdAtMillis: Long = System.currentTimeMillis()): Boolean {
        val target = eventFile(event.eventId)
        if (target.exists()) {
            return false
        }
        val record = CrashQueueRecord(
            event = event,
            createdAtMillis = createdAtMillis,
        )
        AtomicFileWriter.write(target, gson.toJson(record))
        return true
    }

    /** 确认目标队列已经包含完全相同的事件，避免把不同内容当作成功导入。 */
    @Synchronized
    fun containsIdentical(event: CrashEvent): Boolean = readRecord(eventFile(event.eventId))?.event == event

    @Synchronized
    fun peekBatch(
        limit: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<CrashQueueItem> {
        require(limit > 0) { "limit must be positive" }
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                val record = readRecord(file)
                if (record == null) {
                    moveMalformedFile(file)
                    null
                } else {
                    CrashQueueItem(record, file)
                }
            }
            .filter { it.record.nextAttemptAtMillis <= nowMillis }
            .sortedWith(
                compareBy<CrashQueueItem> { it.record.createdAtMillis }
                    .thenBy { it.record.event.eventId },
            )
            .take(limit)
    }

    @Synchronized
    fun count(): Int {
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            ?.size
            ?: 0
    }

    @Synchronized
    fun acknowledge(eventIds: Collection<String>) {
        eventIds.forEach { eventId ->
            eventFile(eventId).takeIf { it.exists() }?.delete()
        }
    }

    @Synchronized
    fun markRetry(items: Collection<CrashQueueItem>, nextAttemptAtMillis: Long) {
        items.forEach { item ->
            if (!item.file.exists()) {
                return@forEach
            }
            val updated = item.record.copy(
                attempts = (item.record.attempts + 1).coerceAtMost(MAX_ATTEMPTS),
                nextAttemptAtMillis = nextAttemptAtMillis,
            )
            AtomicFileWriter.write(item.file, gson.toJson(updated))
        }
    }

    @Synchronized
    fun moveToDeadLetter(items: Collection<CrashQueueItem>, reason: String) {
        if (items.isEmpty()) {
            return
        }
        val deadLetter = File(
            deadLetterDirectory,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}.$DEAD_LETTER_EXTENSION",
        )
        val payload = DeadLetterRecord(
            movedAtMillis = System.currentTimeMillis(),
            reason = CrashSanitizer.sanitize(reason, MAX_REASON_LENGTH),
            records = items.map { it.record },
        )
        AtomicFileWriter.write(deadLetter, gson.toJson(payload))
        acknowledge(items.map { it.record.event.eventId })
        trimDeadLetters()
    }

    @Synchronized
    fun expireOlderThan(cutoffMillis: Long) {
        val expired = peekAll().filter { it.record.createdAtMillis < cutoffMillis }
        moveToDeadLetter(expired, "event_expired")
    }

    private fun peekAll(): List<CrashQueueItem> {
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                val record = readRecord(file)
                if (record == null) {
                    moveMalformedFile(file)
                    null
                } else {
                    CrashQueueItem(record, file)
                }
            }
    }

    private fun readRecord(file: File): CrashQueueRecord? {
        return runCatching {
            val record = gson.fromJson(
                file.readText(StandardCharsets.UTF_8),
                CrashQueueRecord::class.java,
            )
            requireNotNull(record)
            require(record.event.eventId.matches(EVENT_ID_PATTERN))
            require(file.nameWithoutExtension == record.event.eventId)
            record
        }.getOrNull()
    }

    private fun moveMalformedFile(file: File) {
        val diagnostic = File(
            deadLetterDirectory,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}-malformed.$DEAD_LETTER_EXTENSION",
        )
        AtomicFileWriter.write(
            diagnostic,
            gson.toJson(
                mapOf(
                    "movedAtMillis" to System.currentTimeMillis(),
                    "reason" to "malformed_queue_record",
                ),
            ),
        )
        file.delete()
        trimDeadLetters()
    }

    private fun trimDeadLetters() {
        val files = deadLetterDirectory
            .listFiles { file -> file.isFile && file.extension == DEAD_LETTER_EXTENSION }
            .orEmpty()
            .sortedBy { it.lastModified() }
        files.take((files.size - maxDeadLetters).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun eventFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "eventId contains unsupported file characters" }
        return File(eventsDirectory, "$eventId.$EVENT_FILE_EXTENSION")
    }

    /** 将已校验的事件身份映射到采集中记录。 */
    private fun captureFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "eventId contains unsupported file characters" }
        return File(capturingDirectory, "$eventId.$EVENT_FILE_EXTENSION")
    }

    private data class DeadLetterRecord(
        val movedAtMillis: Long,
        val reason: String,
        val records: List<CrashQueueRecord>,
    )

    private companion object {
        const val CAPTURING_DIRECTORY_NAME = "capturing"
        const val EVENTS_DIRECTORY_NAME = "events"
        const val DEAD_LETTER_DIRECTORY_NAME = "dead-letter"
        const val EVENT_FILE_EXTENSION = "json"
        const val DEAD_LETTER_EXTENSION = "json"
        const val MAX_ATTEMPTS = 1_000
        const val MAX_REASON_LENGTH = 256
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
