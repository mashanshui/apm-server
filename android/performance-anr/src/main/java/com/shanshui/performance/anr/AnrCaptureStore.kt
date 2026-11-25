package com.shanshui.performance.anr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.core.AtomicFileWriter
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 报告、采集现场与上传队列在同一进程内共享的文件操作锁。 */
internal object AnrStorageLock

/** 待确认事件按原事件时间计算的最长保留期。 */
internal const val ANR_PENDING_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000

/** 独立保存采集进度、冻结现场和待确认事件。 */
internal class AnrCaptureStore(
    /** ANR 私有目录。 */ private val root: File,
    /** 事件冻结与字节预算。 */ private val factory: AnrEventFactory = AnrEventFactory(),
    /** 与传输一致的编码器。 */ private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    /** 单调时钟仅用于当前进程的补充窗口。 */ private val nanoTime: () -> Long = System::nanoTime,
    /** 墙上时间用于队列容量不足时清理过期项。 */ private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    /** 删除本地诊断副本，供失败重试场景验证使用。 */ private val deleteEvidenceFile: (File) -> Boolean = { it.delete() },
    /** 阶段日志不包含正文或凭据。 */ private val log: (String) -> Unit = {},
) {
    /** 核心已提交但尚未冻结的记录。 */ private val capturing = File(root, "capturing")
    /** 待服务端确认的冻结事件。 */ private val pending = File(root, "pending")
    /** 独立于上传状态的本地已冻结现场。 */ private val evidence = File(root, "evidence")
    /** 永久拒绝的有界记录。 */ private val dead = File(root, "dead-letter")
    /** 进程内截止时间，不将单调时钟作为跨进程时间保存。 */ private val deadlines = mutableMapOf<String, Long>()

    /** 仅 SIGNAL_ANR 可以建立采集，先提交核心再启动异步诊断。 */
    fun begin(record: AnrCaptureRecord): Boolean = synchronized(AnrStorageLock) {
        val report = record.report
        if (report.type != AnrReportType.SIGNAL_ANR || report.captureId != report.id || !validId(report.id)) return false
        if (file(capturing, report.id).exists() || file(evidence, report.id).exists() || file(pending, report.id).exists()) return false
        if (files(capturing).size + files(pending).size >= MAX_PENDING) {
            expire(wallTimeMillis() - ANR_PENDING_RETENTION_MILLIS)
        }
        if (files(capturing).size + files(pending).size >= MAX_PENDING) {
            log("ANR 采集队列已满")
            return false
        }
        return try {
            /** 系统判定已取得的说明与核心同时落盘。 */
            val initial = record.copy(info = record.info ?: factory.initialInfo(report))
            AtomicFileWriter.write(file(capturing, report.id), gson.toJson(initial))
            deadlines[report.id] = nanoTime() + TimeUnit.MILLISECONDS.toNanos(CAPTURE_MILLIS)
            log("ANR 核心已提交")
            true
        } catch (failure: Exception) {
            log("ANR 核心提交失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 捕获窗口内按 ID 原子提交一个已完成分区。 */
    fun update(id: String, change: (AnrCaptureRecord) -> AnrCaptureRecord): Boolean = synchronized(AnrStorageLock) {
        if (!validId(id)) return false
        val deadline = deadlines[id] ?: return false
        if (nanoTime() >= deadline) return false
        val target = file(capturing, id)
        val current = read(target, AnrCaptureRecord::class.java) ?: return false
        return try {
            /** 保护原核心、目标和设备身份，避免异步分区改写身份。 */
            val updated = change(current)
            if (updated.report != current.report || updated.target != current.target ||
                updated.osVersion != current.osVersion || updated.deviceModel != current.deviceModel ||
                updated.discardEvidence != current.discardEvidence
            ) return false
            AtomicFileWriter.write(target, gson.toJson(updated))
            log("ANR 诊断分区已提交")
            true
        } catch (failure: Exception) {
            log("ANR 诊断分区提交失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 到期后固定事件，并让迟到结果无法再修改。 */
    fun freeze(id: String, reason: String = "timeout"): Boolean = synchronized(AnrStorageLock) {
        if (!validId(id)) return false
        deadlines.remove(id)
        return finish(file(capturing, id), reason)
    }

    /** 原进程中断后立即冻结已提交分区，未完成分区标记 interrupted。 */
    fun recover(): Int = synchronized(AnrStorageLock) {
        var recovered = 0
        for (capture in files(capturing).take(MAX_PENDING)) {
            if (finish(capture, "interrupted")) recovered++
        }
        if (recovered > 0) log("ANR 中断采集已恢复：$recovered 条")
        return recovered
    }

    /** 读取本地已冻结事件供报告展示；上传确认不删除它。 */
    fun readEvidence(id: String): AnrCaptureRecord? = synchronized(AnrStorageLock) {
        if (!validId(id)) return null
        read(file(evidence, id), AnrCaptureRecord::class.java)?.takeIf { it.report.id == id }
    }

    /** 返回当前目标可发送的有界集合；不同目标不会被选中。 */
    fun eligible(target: String, now: Long, limit: Int): List<AnrPendingRecord> = synchronized(AnrStorageLock) {
        files(pending).asSequence().take(MAX_PENDING).mapNotNull { read(it, AnrPendingRecord::class.java) }
            .filter { it.target == target && it.nextAttemptAtMillis <= now }
            .sortedBy { it.event.occurredAt }.take(limit.coerceIn(1, MAX_BATCH)).toList()
    }

    /** 仅在有效确认后删除待上传记录。 */
    fun acknowledge(target: String, id: String): Boolean = synchronized(AnrStorageLock) {
        val entry = pendingRecord(target, id) ?: return false
        val removed = file(pending, entry.event.eventId).delete()
        if (removed) log("ANR 事件已确认") else log("ANR 确认后清理失败")
        return removed
    }

    /** 保留原冻结事件，只更新退避元数据。 */
    fun retry(target: String, id: String, nextAttemptAtMillis: Long): Boolean = synchronized(AnrStorageLock) {
        val entry = pendingRecord(target, id) ?: return false
        return try {
            AtomicFileWriter.write(file(pending, id), gson.toJson(entry.copy(
                attempts = (entry.attempts + 1).coerceAtMost(MAX_ATTEMPTS), nextAttemptAtMillis = nextAttemptAtMillis)))
            log("ANR 事件已保留等待重试")
            true
        } catch (failure: Exception) {
            log("ANR 重试状态保存失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 永久拒绝先写死信再删除待上传项，保留原事件供排查。 */
    fun reject(target: String, id: String, reason: String): Boolean = synchronized(AnrStorageLock) {
        val entry = pendingRecord(target, id) ?: return false
        return try {
            AtomicFileWriter.write(file(dead, id), gson.toJson(DeadLetter(reason.take(128), entry)))
            if (!file(pending, id).delete()) return false
            files(dead).sortedByDescending { it.lastModified() }.drop(MAX_DEAD).forEach { it.delete() }
            log("ANR 事件已转入死信")
            true
        } catch (failure: Exception) {
            log("ANR 死信保存失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 删除所有目标超过保留期的事件，其他目标的有效事件继续保留。 */
    fun expire(cutoff: Long) = synchronized(AnrStorageLock) {
        /** 记录实际删除数，避免输出事件内容或目标信息。 */
        var removed = 0
        for (item in files(pending).take(MAX_PENDING)) {
            val record = read(item, AnrPendingRecord::class.java) ?: continue
            if (record.event.occurredAt < cutoff) {
                if (item.delete()) removed++ else log("ANR 过期待上传事件清理失败")
            }
        }
        if (removed > 0) log("ANR 过期待上传事件已清理：$removed 条")
    }

    /** 删除本地副本并固定进行中采集的删除状态，不影响待确认事件。 */
    fun discardLocalEvidence(id: String): Boolean = synchronized(AnrStorageLock) {
        if (!validId(id)) return false
        try {
            /** 先提交删除状态，避免冻结与重启恢复重新创建副本。 */
            val capture = ownedFile(capturing, id)
            if (capture.exists()) {
                val record = read(capture, AnrCaptureRecord::class.java) ?: return false
                if (record.report.id != id) return false
                if (!record.discardEvidence) AtomicFileWriter.write(capture, gson.toJson(record.copy(discardEvidence = true)))
            }
            /** 已冻结的上传队列与本地副本分别管理。 */
            val local = ownedFile(evidence, id)
            if (local.exists() && !deleteEvidenceFile(local)) {
                log("ANR 本地诊断副本清理失败")
                return false
            }
            log("ANR 本地诊断副本已清理")
            true
        } catch (failure: Exception) {
            log("ANR 本地诊断副本清理失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 清理已经没有本地报告的历史副本及进行中采集。 */
    fun discardUnlistedEvidence(reportIds: Set<String>): Boolean = synchronized(AnrStorageLock) {
        /** 汇总需要核对的目录，并在目录不可读时保留失败状态。 */
        val candidates = mutableListOf<File>()
        var success = true
        for (directory in listOf(capturing, evidence)) {
            try {
                if (!directory.exists()) continue
                if (!directory.isDirectory || directory.canonicalFile.parentFile != root.canonicalFile) {
                    throw IOException("invalid ANR storage directory")
                }
                val entries = directory.listFiles() ?: throw IOException("cannot list ANR storage directory")
                candidates.addAll(entries.filter { it.isFile && it.name.endsWith(".json") })
            } catch (failure: Exception) {
                log("ANR 本地诊断目录读取失败：${failure.javaClass.simpleName}")
                success = false
            }
        }
        /** 各目录仅接受模块生成的 UUID 文件名。 */
        val ids = candidates.map { it.name.removeSuffix(".json") }
            .filter { validId(it) && it !in reportIds }.toSet()
        for (id in ids) {
            if (!discardLocalEvidence(id)) success = false
        }
        if (ids.isNotEmpty() && success) log("ANR 无报告诊断副本已清理：${ids.size} 条")
        success
    }

    /** 已完成时优先使用已提交记录，避免崩溃恢复重复冻结。 */
    private fun finish(captureFile: File, missingReason: String): Boolean {
        val record = read(captureFile, AnrCaptureRecord::class.java) ?: return false
        val id = record.report.id
        if (!validId(id) || captureFile.name != "$id.json") return false
        return try {
            val stored = read(file(evidence, id), AnrCaptureRecord::class.java)
            val queued = read(file(pending, id), AnrPendingRecord::class.java)
            val complete = if (queued != null) record.copy(info = queued.event.anr.anrInfo,
                trace = queued.event.anr.trace, diagnostics = queued.event.anr.diagnostics)
                else factory.completeRecord(record, missingReason)
            if (record.target != null && stored == null && queued == null) {
                val event = factory.freeze(complete, missingReason)
                AtomicFileWriter.write(file(pending, id), gson.toJson(AnrPendingRecord(record.target, event)))
            }
            if (stored == null && !record.discardEvidence) {
                AtomicFileWriter.write(file(evidence, id), gson.toJson(complete))
            }
            if (!captureFile.delete() && captureFile.exists()) return false
            files(evidence).sortedByDescending { it.lastModified() }.drop(MAX_EVIDENCE).forEach { it.delete() }
            log("ANR 现场已冻结")
            true
        } catch (failure: Exception) {
            log("ANR 现场冻结失败：${failure.javaClass.simpleName}")
            false
        }
    }

    /** 只允许操作目标匹配且事件 ID 相同的队列项。 */
    private fun pendingRecord(target: String, id: String): AnrPendingRecord? {
        if (!validId(id)) return null
        return read(file(pending, id), AnrPendingRecord::class.java)
            ?.takeIf { it.target == target && it.event.eventId == id }
    }

    /** 安全读取单个有界 JSON 文件。 */
    private fun <T> read(file: File, type: Class<T>): T? = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) null else gson.fromJson(file.readText(Charsets.UTF_8), type)
    } catch (failure: Exception) {
        log("ANR 记录读取失败：${failure.javaClass.simpleName}")
        null
    }

    /** 只列出本模块的规范 JSON 文件。 */
    private fun files(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    /** 仅根据合法 ID 派生本模块的文件名。 */
    private fun file(directory: File, id: String): File = File(directory, "$id.json")

    /** 清理时拒绝指向本模块目录外的符号链接。 */
    private fun ownedFile(directory: File, id: String): File {
        /** 比较规范路径以限制删除和采集状态写入范围。 */
        val canonicalDirectory = directory.canonicalFile
        val target = file(directory, id).canonicalFile
        if (canonicalDirectory.parentFile != root.canonicalFile || target.parentFile != canonicalDirectory ||
            target.name != "$id.json"
        ) {
            throw IOException("invalid ANR storage path")
        }
        return target
    }

    /** 捕获 ID 采用规范 UUID v4。 */
    private fun validId(id: String): Boolean = try {
        val parsed = UUID.fromString(id)
        parsed.version() == 4 && parsed.toString() == id
    } catch (_: IllegalArgumentException) { false }

    /** 永久拒绝的记录只包含受控原因和原事件。 */
    private data class DeadLetter(
        /** 受控原因类别。 */ val reason: String,
        /** 原待确认项。 */ val record: AnrPendingRecord,
    )

    /** 存储访问与容量边界。 */
    private companion object {
        const val CAPTURE_MILLIS = 5_000L
        const val MAX_PENDING = 200
        const val MAX_EVIDENCE = 200
        const val MAX_DEAD = 100
        const val MAX_BATCH = 20
        const val MAX_ATTEMPTS = 1_000
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}
