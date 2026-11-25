package com.shanshui.performance.anr

import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.shanshui.performance.core.AtomicFileWriter
import java.io.File
import java.io.IOException
import java.util.UUID

/** 报告索引的原子文件访问边界，供设备实现和 JVM 测试共用。 */
internal interface AnrAtomicIndex {
    /** 读取已提交的索引；索引不存在时返回 null。 */
    fun read(): String?

    /** 原子提交完整索引。 */
    fun write(content: String)
}

/** 使用 Android AtomicFile 保留失败写入前的索引。 */
internal class AndroidAnrAtomicIndex(
    /** 本地索引文件。 */
    private val file: File,
) : AnrAtomicIndex {
    /** 读取已提交的索引及系统保留的备份。 */
    override fun read(): String? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 先写入临时文件，成功后由 AtomicFile 提交。 */
    override fun write(content: String) {
        /** 索引所属的私有目录。 */
        val parent = file.parentFile ?: throw IOException("missing index directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create index directory")
        /** Android 平台原子文件包装。 */
        val atomic = AtomicFile(file)
        /** 尚未提交的输出流。 */
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }
}

/** 本地报告存储日志，只接收不包含报告正文的阶段信息。 */
internal fun interface AnrStorageLog {
    /** 按结果等级记录存储阶段。 */
    fun record(success: Boolean, message: String)
}

/** Android 环境下的报告存储日志。 */
internal object AndroidAnrStorageLog : AnrStorageLog {
    /** 记录安全阶段和失败原因。 */
    override fun record(success: Boolean, message: String) {
        if (success) Log.i("PerformanceAnr", message) else Log.w("PerformanceAnr", message)
    }
}

/** 可独立于 JNI 和采集状态读取的本地报告存储。 */
internal class AnrReportStore(
    /** 本功能专用的应用私有目录。 */
    private val root: File,
    /** 本地报告容量配置。 */
    private val config: AnrConfig,
    /** 原子索引实现。 */
    private val indexFile: AnrAtomicIndex = AndroidAnrAtomicIndex(File(root, "index.json")),
    /** 不包含业务内容的阶段日志。 */
    private val logger: AnrStorageLog = AndroidAnrStorageLog,
    /** 与其他客户端模块一致的 JSON 实现。 */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    /** 删除已提交报告正文的文件操作，供失败场景验证使用。 */
    private val deleteReportFile: (File) -> Boolean = { it.delete() },
    /** 本地诊断副本与报告共用清理生命周期。 */
    private val captures: AnrCaptureStore = AnrCaptureStore(root),
) {
    /** 仅存放已完成报告正文，不包括 Matrix 活动采集文件。 */
    private val reportsDir = File(root, "reports")

    /** 按稳定顺序读取摘要，整个操作不读取 trace 正文。 */
    fun list(pageSize: Int, afterId: String? = null): AnrReportResult<AnrReportPage> = synchronized(AnrStorageLock) {
        if (pageSize !in 1..20 || (afterId != null && !validId(afterId))) {
            return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_PAGE)
        }
        try {
            /** 按时间和 ID 排序的已提交索引。 */
            val entries = readIndex().entries.sortedWith(ORDER)
            /** 页游标指向上一页最后一条记录。 */
            val start = if (afterId == null) 0 else entries.indexOfFirst { it.id == afterId } + 1
            if (afterId != null && start == 0) return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_PAGE)
            /** 当前页与可能存在的后续页面。 */
            val page = entries.drop(start).take(pageSize)
            val next = if (start + page.size < entries.size) page.last().id else null
            logger.record(true, "ANR 报告摘要读取完成：${page.size} 条")
            AnrReportResult.Success(AnrReportPage(page, next))
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 报告摘要读取失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 报告摘要不可读")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        }
    }

    /** 按安全 ID 读取正文，区分缺失、损坏和 I/O 失败。 */
    fun read(id: String): AnrReportResult<AnrReport> = synchronized(AnrStorageLock) {
        if (!validId(id)) return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_ID)
        try {
            /** 先确认目标属于已提交索引。 */
            val entries = readIndex().entries
            val entry = entries.firstOrNull { it.id == id }
                ?: return@synchronized AnrReportResult.Failure(AnrReportError.NOT_FOUND)
            /** 只允许访问报告目录内由 ID 派生的文件。 */
            val file = reportFile(id)
            if (!file.isFile) return@synchronized AnrReportResult.Failure(AnrReportError.NOT_FOUND)
            /** 正文缺字段或与索引不一致时不能当作有效报告。 */
            val report = try {
                gson.fromJson(file.readText(Charsets.UTF_8), AnrReport::class.java)
            } catch (failure: RuntimeException) {
                null
            }
            if (report == null || !validReport(report, entry) || file.length() != entry.sizeBytes) {
                logger.record(false, "ANR 报告正文损坏")
                return@synchronized AnrReportResult.Failure(AnrReportError.CORRUPT)
            }
            logger.record(true, "ANR 报告正文读取完成")
            AnrReportResult.Success(withAssociatedTrace(report, entries))
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 报告正文读取失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 报告正文不可读")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        }
    }

    /** 从索引删除指定报告后清理正文，不触碰 Matrix 活动采集文件。 */
    fun delete(id: String): AnrReportResult<Unit> = synchronized(AnrStorageLock) {
        if (!validId(id)) return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_ID)
        try {
            /** 重试此前已经移出索引但尚未清理成功的目标文件。 */
            val original = readIndex()
            val wasPending = original.pendingDeletion.any { it.id == id }
            val index = clearPending(original)
            if (wasPending) {
                return@synchronized if (index.pendingDeletion.any { it.id == id }) {
                    AnrReportResult.Failure(AnrReportError.IO_ERROR)
                } else {
                    AnrReportResult.Success(Unit)
                }
            }
            /** 只删除当前已提交的报告。 */
            val entry = index.entries.firstOrNull { it.id == id }
                ?: return@synchronized AnrReportResult.Failure(AnrReportError.NOT_FOUND)
            /** 先原子记录待删除文件，清理失败时可在下次操作重试。 */
            reportFile(id)
            val updated = index.copy(entries = index.entries.filterNot { it.id == id }, pendingDeletion = index.pendingDeletion + entry)
            writeIndex(updated)
            if (clearPending(updated).pendingDeletion.any { it.id == id }) throw IOException("cannot delete report")
            logger.record(true, "ANR 报告删除完成")
            AnrReportResult.Success(Unit)
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 报告删除失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 报告删除失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        }
    }

    /** 保存实际回调结果，只有正文和索引均提交后才返回成功。 */
    fun save(report: AnrReport): AnrReportResult<AnrReportSummary> = synchronized(AnrStorageLock) {
        if (!validId(report.id)) return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_ID)
        if (report.captureId != null && (!validId(report.captureId) ||
                report.type !in setOf(AnrReportType.SIGNAL_ANR, AnrReportType.ANR_TRACE) ||
                report.type == AnrReportType.SIGNAL_ANR && report.id != report.captureId)
        ) return@synchronized AnrReportResult.Failure(AnrReportError.INVALID_ID)
        /** 新正文路径；失败时仅清理本次新建的文件。 */
        var created: File? = null
        try {
            /** 先读取旧索引并重试待清理文件，避免损坏索引被覆盖。 */
            val index = clearPending(readIndex())
            if (index.pendingDeletion.isNotEmpty()) throw IOException("pending report cleanup failed")
            if ((index.entries + index.pendingDeletion).any { it.id == report.id }) throw IOException("duplicate report")
            /** 报告正文实际 UTF-8 大小。 */
            val content = gson.toJson(report)
            val size = content.toByteArray(Charsets.UTF_8).size.toLong()
            if (size > config.maxStorageBytes) return@synchronized AnrReportResult.Failure(AnrReportError.TOO_LARGE)
            /** 有效 ID 必须解析到本模块私有报告目录。 */
            val target = reportFile(report.id)
            if (target.exists()) throw IOException("report already exists")
            AtomicFileWriter.write(target, content)
            created = target
            /** 新索引只保留容量和有效期内的报告。 */
            val summary = AnrReportSummary(report.id, report.createdAtMillis, report.type, size, report.captureId)
            val all = (index.entries + summary).sortedWith(ORDER)
            val kept = retained(all, report.createdAtMillis, config.maxStorageBytes)
            if (kept.none { it.id == report.id }) throw IOException("new report not retained")
            val removed = all.filterNot { entry -> kept.any { it.id == entry.id } }
            val committed = Index(entries = kept, pendingDeletion = index.pendingDeletion + removed)
            writeIndex(committed)
            created = null
            runCatching { clearPending(committed) }.onFailure {
                logger.record(false, "ANR 旧报告清理失败，将在后续操作重试")
            }
            logger.record(true, "ANR 报告保存完成：$size 字节")
            AnrReportResult.Success(summary)
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏，未保存结果")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 报告保存失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 报告保存失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: IllegalArgumentException) {
            logger.record(false, "ANR 报告保存失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } finally {
            runCatching { created?.delete() }
        }
    }

    /** 按容量和过期时间清理已完成报告，不修改活动 trace。 */
    fun clean(nowMillis: Long): AnrReportResult<Int> = synchronized(AnrStorageLock) {
        try {
            /** 以已提交索引为清理范围，并先重试之前未删除的文件。 */
            val index = clearPending(readIndex())
            if (index.pendingDeletion.isNotEmpty()) throw IOException("pending report cleanup failed")
            val kept = retained(index.entries.sortedWith(ORDER), nowMillis, config.maxStorageBytes)
            val removed = index.entries.filterNot { entry -> kept.any { it.id == entry.id } }
            var updated = index
            if (removed.isNotEmpty()) {
                updated = index.copy(entries = kept, pendingDeletion = index.pendingDeletion + removed)
                writeIndex(updated)
            }
            if (clearPending(updated).pendingDeletion.isNotEmpty()) throw IOException("cannot delete pending reports")
            discardUnlistedEvidence(updated)
            logger.record(true, "ANR 报告清理完成：${removed.size} 条")
            AnrReportResult.Success(removed.size)
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏，未清理")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 报告清理失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 报告清理失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        }
    }

    /** 恢复时清除历史遗留或未提交报告对应的本地诊断副本。 */
    fun reconcileEvidence(): AnrReportResult<Unit> = synchronized(AnrStorageLock) {
        try {
            /** 待删除报告先沿用索引中的重试记录。 */
            val index = clearPending(readIndex())
            if (index.pendingDeletion.isNotEmpty()) throw IOException("pending report cleanup failed")
            discardUnlistedEvidence(index)
            logger.record(true, "ANR 本地诊断副本核对完成")
            AnrReportResult.Success(Unit)
        } catch (failure: CorruptIndexException) {
            logger.record(false, "ANR 报告索引损坏，未清理诊断副本")
            AnrReportResult.Failure(AnrReportError.CORRUPT)
        } catch (failure: IOException) {
            logger.record(false, "ANR 本地诊断副本核对失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        } catch (failure: SecurityException) {
            logger.record(false, "ANR 本地诊断副本核对失败")
            AnrReportResult.Failure(AnrReportError.IO_ERROR)
        }
    }

    /** 仅保留已登记报告对应的采集副本。 */
    private fun discardUnlistedEvidence(index: Index) {
        /** 发生删除失败时，保留索引中的待删除条目供重试。 */
        val reportIds = (index.entries + index.pendingDeletion).mapTo(mutableSetOf()) { it.id }
        if (!captures.discardUnlistedEvidence(reportIds)) throw IOException("cannot delete unlisted evidence")
    }

    /** 根据容量和过期时间选择从新到旧保留的摘要。 */
    private fun retained(entries: List<AnrReportSummary>, nowMillis: Long, maxBytes: Long): List<AnrReportSummary> {
        /** 累计已保留正文大小。 */
        var used = 0L
        /** 已保留的报告摘要。 */
        val retained = mutableListOf<AnrReportSummary>()
        for (entry in entries) {
            if (entry.createdAtMillis < nowMillis - config.maxReportAgeMillis) continue
            if (retained.size >= config.maxReportCount || entry.sizeBytes > maxBytes - used) continue
            used += entry.sizeBytes
            retained.add(entry)
        }
        return retained
    }

    /** 重试待删除文件，成功后原子更新索引；失败条目留待下一次重试。 */
    private fun clearPending(index: Index): Index {
        if (index.pendingDeletion.isEmpty()) return index
        /** 尚未成功删除的摘要。 */
        val remaining = index.pendingDeletion.filter { entry ->
            val deleted = try {
                val file = reportFile(entry.id)
                (!file.exists() || deleteReportFile(file)) && captures.discardLocalEvidence(entry.id)
            } catch (failure: IOException) {
                false
            } catch (failure: SecurityException) {
                false
            }
            if (!deleted) logger.record(false, "ANR 旧报告文件删除失败")
            !deleted
        }
        if (remaining.size == index.pendingDeletion.size) return index
        /** 已删除文件不会再纳入容量，索引提交失败时可再次安全重试。 */
        val updated = index.copy(pendingDeletion = remaining)
        writeIndex(updated)
        return updated
    }

    /** 读取并验证版本、ID 和索引摘要。 */
    private fun readIndex(): Index {
        /** 不存在索引代表尚无已提交报告。 */
        val content = indexFile.read() ?: return Index()
        /** JSON 解析失败不能隐式替换旧索引。 */
        val index = try {
            gson.fromJson(content, Index::class.java)
        } catch (failure: RuntimeException) {
            null
        } ?: throw CorruptIndexException()
        try {
            val all = index.entries + index.pendingDeletion
            if (index.version != 1 || all.any { !validId(it.id) || it.sizeBytes < 0 || (it.type as AnrReportType?) == null ||
                    (it.captureId != null && !validId(it.captureId)) } ||
                all.map { it.id }.distinct().size != all.size
            ) throw CorruptIndexException()
        } catch (failure: NullPointerException) {
            throw CorruptIndexException()
        }
        return index
    }

    /** 原子提交版本化索引。 */
    private fun writeIndex(index: Index) {
        indexFile.write(gson.toJson(index))
    }

    /** 由 UUID 派生报告路径并拒绝指向目录外的引用。 */
    private fun reportFile(id: String): File {
        if (!validId(id)) throw IOException("invalid report id")
        /** 规范化后的报告路径。 */
        val file = File(reportsDir, "$id.json").canonicalFile
        /** 报告目录本身也必须位于本功能目录下。 */
        val canonicalReports = reportsDir.canonicalFile
        if (canonicalReports.parentFile != root.canonicalFile || file.parentFile != canonicalReports) {
            throw IOException("invalid report path")
        }
        return file
    }

    /** 验证正文与索引一致，避免缺失原进程身份的报告被当作有效数据。 */
    private fun validReport(report: AnrReport, entry: AnrReportSummary): Boolean {
        /** Gson 读取损坏 JSON 时可能为非空类型填入 null。 */
        val identity = report.identity as AnrReportIdentity? ?: return false
        /** 保存时必须存在的原进程身份字段。 */
        val fields: List<String?> = listOf(
            identity.deviceId,
            identity.sessionId,
            identity.processId,
            identity.packageName,
            identity.versionName,
            identity.buildId,
            identity.environment,
            identity.channel,
        )
        return report.id == entry.id && report.type == entry.type && report.captureId == entry.captureId &&
            report.createdAtMillis == entry.createdAtMillis && identity.versionCode >= 0 &&
            fields.none { it.isNullOrBlank() }
    }

    /** 仅在唯一采集身份与原进程身份一致时，将独立 trace 投影到判定报告。 */
    private fun withAssociatedTrace(report: AnrReport, entries: List<AnrReportSummary>): AnrReport {
        /** 只有携带相同采集身份的判定报告参与关联。 */
        val captureId = report.captureId ?: return report
        if (report.type != AnrReportType.SIGNAL_ANR || report.id != captureId) return report
        /** 多个候选无法确认归属，保留原有判定报告。 */
        val matches = entries.filter { it.type == AnrReportType.ANR_TRACE && it.captureId == captureId }
        if (matches.size != 1) return report
        return try {
            /** 只读取已提交且字节数完整的候选。 */
            val entry = matches.single()
            val file = reportFile(entry.id)
            if (!file.isFile || file.length() != entry.sizeBytes) return report
            /** 进程身份不一致时不得合并正文。 */
            val trace = gson.fromJson(file.readText(Charsets.UTF_8), AnrReport::class.java) ?: return report
            if (!validReport(trace, entry) || trace.identity != report.identity) return report
            report.copy(trace = trace.trace, traceBase64 = trace.traceBase64, traceTruncated = trace.traceTruncated)
        } catch (failure: Exception) {
            logger.record(false, "关联的 ANR trace 不可读取")
            report
        }
    }

    /** 验证规范小写 UUID v4。 */
    private fun validId(id: String): Boolean = try {
        /** 只接受与 SDK 生成格式一致的 UUID。 */
        val uuid = UUID.fromString(id)
        uuid.version() == 4 && uuid.variant() == 2 && uuid.toString() == id
    } catch (failure: IllegalArgumentException) {
        false
    }

    /** 持久化索引格式；只包含摘要而不包含 trace。 */
    private data class Index(
        /** 当前索引格式版本。 */
        @SerializedName("version") val version: Int = 1,
        /** 已提交报告的摘要。 */
        @SerializedName("entries") val entries: List<AnrReportSummary> = emptyList(),
        /** 索引已移除、正文待重试删除的报告。 */
        @SerializedName("pendingDeletion") val pendingDeletion: List<AnrReportSummary> = emptyList(),
    )

    /** 表示已存在但无法解析或验证的索引。 */
    private class CorruptIndexException : IOException()

    /** 报告摘要的稳定排序规则。 */
    private companion object {
        /** 最新报告优先，时间相同时按 ID 稳定排序。 */
        val ORDER = compareByDescending<AnrReportSummary> { it.createdAtMillis }.thenByDescending { it.id }
    }
}
