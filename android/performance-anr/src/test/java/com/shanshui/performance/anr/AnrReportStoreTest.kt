package com.shanshui.performance.anr

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 验证报告提交、恢复、边界及不修改 Matrix 活动文件。 */
class AnrReportStoreTest {
    /** 每个测试的独立目录。 */
    private lateinit var root: File
    /** JVM 测试使用的索引访问。 */
    private lateinit var index: TestIndex
    /** 禁止测试输出报告内容。 */
    private val logger = AnrStorageLog { _, _ -> }

    /** 创建与其他测试隔离的私有目录。 */
    @Before
    fun setUp() {
        root = Files.createTempDirectory("anr-store-test").toFile()
        index = TestIndex(File(root, "index.json"))
    }

    /** 清理测试临时目录。 */
    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 模拟重启后按摘要分页并读取原进程报告。 */
    @Test
    fun savesAndReadsAcrossStoreInstances() {
        /** 首个进程保存的报告。 */
        val first = report(100, "first trace").copy(
            javaStack = "main stack",
            javaStackTruncated = true,
            anrInfo = "system message",
            anrInfoTruncated = true,
        )
        /** 第二个报告具有不同时间和来源。 */
        val second = report(200, "manual trace", AnrReportType.MANUAL_TRACE)
        assertTrue(store().save(first) is AnrReportResult.Success)
        assertTrue(store().save(second) is AnrReportResult.Success)

        /** 新存储对象模拟后续进程。 */
        val restarted = store()
        /** 首页只包含一条摘要。 */
        val page = (restarted.list(1) as AnrReportResult.Success).value
        assertEquals(listOf(second.id), page.reports.map { it.id })
        assertEquals(second.id, page.nextAfterId)
        /** 下一页按已有游标继续，正文仍保留原始身份。 */
        val next = (restarted.list(1, page.nextAfterId) as AnrReportResult.Success).value
        assertEquals(listOf(first.id), next.reports.map { it.id })
        assertEquals(first, (restarted.read(first.id) as AnrReportResult.Success).value)
        assertFalse(index.file.readText().contains("first trace"))
    }

    /** 判定与 trace 回调无论先后均按同一采集身份关联。 */
    @Test
    fun associatesTraceInEitherCallbackOrder() {
        val store = store()
        val firstId = UUID.randomUUID().toString()
        val firstSignal = report(100, "", AnrReportType.SIGNAL_ANR).copy(id = firstId, captureId = firstId, trace = null)
        val firstTrace = report(101, "trace-first").copy(captureId = firstId, traceTruncated = true)
        assertTrue(store.save(firstTrace) is AnrReportResult.Success)
        assertTrue(store.save(firstSignal) is AnrReportResult.Success)
        val linkedFirst = (store.read(firstId) as AnrReportResult.Success).value
        assertEquals("trace-first", linkedFirst.trace)
        assertEquals(true, linkedFirst.traceTruncated)

        val secondId = UUID.randomUUID().toString()
        val secondSignal = report(200, "", AnrReportType.SIGNAL_ANR).copy(id = secondId, captureId = secondId, trace = null)
        assertTrue(store.save(secondSignal) is AnrReportResult.Success)
        assertNull((store.read(secondId) as AnrReportResult.Success).value.trace)
        val secondTrace = report(201, "trace-second").copy(captureId = secondId)
        assertTrue(store.save(secondTrace) is AnrReportResult.Success)
        assertEquals("trace-second", (store.read(secondId) as AnrReportResult.Success).value.trace)
        assertEquals("trace-first", (store.read(firstId) as AnrReportResult.Success).value.trace)
    }

    /** 身份不一致、重复候选或手动 trace 均不能关联到 ANR。 */
    @Test
    fun rejectsAmbiguousAndUnrelatedTrace() {
        val store = store()
        val captureId = UUID.randomUUID().toString()
        val signal = report(100, "", AnrReportType.SIGNAL_ANR).copy(id = captureId, captureId = captureId, trace = null)
        val unrelated = report(101, "other").copy(captureId = UUID.randomUUID().toString())
        val manual = report(102, "manual", AnrReportType.MANUAL_TRACE)
        val wrongProcess = report(103, "wrong-process").copy(captureId = captureId,
            identity = signal.identity.copy(processId = "different-process"))
        listOf(signal, unrelated, manual, wrongProcess).forEach { assertTrue(store.save(it) is AnrReportResult.Success) }
        assertNull((store.read(captureId) as AnrReportResult.Success).value.trace)
        val matching = report(104, "matching").copy(captureId = captureId)
        assertTrue(store.save(matching) is AnrReportResult.Success)
        assertNull((store.read(captureId) as AnrReportResult.Success).value.trace)
    }

    /** 单独 trace 只形成本地 trace 报告，不会生成 SIGNAL_ANR 判定报告。 */
    @Test
    fun keepsTraceOnlyAsLocalReport() {
        val store = store()
        val trace = report(100, "trace-only").copy(captureId = UUID.randomUUID().toString())
        assertTrue(store.save(trace) is AnrReportResult.Success)
        val page = (store.list(20) as AnrReportResult.Success).value
        assertEquals(listOf(AnrReportType.ANR_TRACE), page.reports.map { it.type })
    }

    /** 历史仅本地报告不会因启动新上传器而自动进入待上传队列。 */
    @Test
    fun doesNotBackfillHistoricalLocalAnr() {
        val historical = report(100, "", AnrReportType.SIGNAL_ANR).copy(captureId = null)
        assertTrue(store().save(historical) is AnrReportResult.Success)
        val captures = AnrCaptureStore(root)
        assertEquals(0, captures.recover())
        assertTrue(captures.eligible("new-target", Long.MAX_VALUE, 20).isEmpty())
        assertEquals(historical, (store().read(historical.id) as AnrReportResult.Success).value)
    }

    /** 注入空间不足的索引写入错误，不能丢失旧报告或伪称新报告成功。 */
    @Test
    fun failedIndexCommitKeepsPreviousReport() {
        /** 先提交一条可恢复的报告。 */
        val previous = report(100, "old")
        assertTrue(store().save(previous) is AnrReportResult.Success)
        index.failWrites = true
        /** 原子索引提交失败的新报告。 */
        val failed = report(200, "new")
        assertEquals(AnrReportError.IO_ERROR, (store().save(failed) as AnrReportResult.Failure).error)
        index.failWrites = false
        assertEquals(previous, (store().read(previous.id) as AnrReportResult.Success).value)
        assertEquals(AnrReportError.NOT_FOUND, (store().read(failed.id) as AnrReportResult.Failure).error)
        assertFalse(File(root, "reports/${failed.id}.json").exists())
    }

    /** 正文无法写入时不提交新索引，已有报告恢复后仍可读取。 */
    @Test
    fun failedReportWriteKeepsPreviousReport() {
        /** 先保存一份已完成报告。 */
        val previous = report(100, "old")
        val configured = store()
        assertTrue(configured.save(previous) is AnrReportResult.Success)
        /** 暂时用普通文件替代报告目录，模拟正文路径不可写。 */
        val reportsDirectory = File(root, "reports")
        val unavailableDirectory = File(root, "reports-unavailable")
        assertTrue(reportsDirectory.renameTo(unavailableDirectory))
        val failed = report(200, "new")
        try {
            reportsDirectory.writeText("not a directory")
            assertEquals(AnrReportError.IO_ERROR, (configured.save(failed) as AnrReportResult.Failure).error)
        } finally {
            reportsDirectory.delete()
            assertTrue(unavailableDirectory.renameTo(reportsDirectory))
        }
        assertEquals(previous, (configured.read(previous.id) as AnrReportResult.Success).value)
        assertEquals(AnrReportError.NOT_FOUND, (configured.read(failed.id) as AnrReportResult.Failure).error)
    }

    /** 清理仅作用于已完成报告，不改变 Matrix 正在使用的文件。 */
    @Test
    fun appliesCapacityAndAgeWithoutDeletingActiveTrace() {
        /** 容量只允许保留两条报告。 */
        val configured = store(AnrConfig(maxReportCount = 2, maxReportAgeMillis = 1_000))
        /** Matrix 活动目录中的原始文件。 */
        val active = File(root, "active/matrix.trace").apply {
            parentFile!!.mkdirs()
            writeText("active")
        }
        /** 分别代表旧、中、新报告。 */
        val old = report(100, "old")
        val middle = report(200, "middle")
        val newest = report(300, "newest")
        listOf(old, middle, newest).forEach { assertTrue(configured.save(it) is AnrReportResult.Success) }
        assertEquals(AnrReportError.NOT_FOUND, (configured.read(old.id) as AnrReportResult.Failure).error)
        assertEquals(2, (configured.list(20) as AnrReportResult.Success).value.reports.size)
        assertEquals(2, (configured.clean(1_301) as AnrReportResult.Success).value)
        assertEquals("active", active.readText())
    }

    /** 删除已冻结和采集中报告时，诊断副本均被清理，待确认事件仍可恢复。 */
    @Test
    fun deletingReportRemovesEvidenceWithoutCancellingUpload() {
        /** 共用测试目录的现场与报告存储。 */
        val captures = AnrCaptureStore(root)
        /** 报告删除会调用同一现场存储。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 已经冻结的报告及待确认事件。 */
        val frozen = saveCaptured(configured, captures, 100, freeze = true)
        assertTrue(File(root, "evidence/${frozen.id}.json").isFile)
        assertTrue(configured.delete(frozen.id) is AnrReportResult.Success)
        assertNull(captures.readEvidence(frozen.id))
        assertEquals(frozen.id, captures.eligible("target", 0, 1).single().event.eventId)

        /** 先删除报告，再模拟进程退出并恢复尚未冻结的采集。 */
        val collecting = saveCaptured(configured, captures, 200, freeze = false)
        assertTrue(configured.delete(collecting.id) is AnrReportResult.Success)
        assertFalse(captures.update(collecting.id) { it.copy(discardEvidence = false) })
        assertTrue(captures.update(collecting.id) { it.copy(info = AnrTextPartition("ok", text = "captured")) })
        /** 模拟下一进程恢复。 */
        val restarted = AnrCaptureStore(root)
        assertEquals(1, restarted.recover())
        assertNull(restarted.readEvidence(collecting.id))
        /** 核对两次事件仍以原身份待确认。 */
        val pending = restarted.eligible("target", 0, 20).associateBy { it.event.eventId }
        assertEquals(setOf(frozen.id, collecting.id), pending.keys)
        assertEquals("captured", pending.getValue(collecting.id).event.anr.anrInfo.text)
    }

    /** 冻结和删除并发时，最终只保留待确认事件。 */
    @Test
    fun concurrentFreezeAndDeleteDoNotRestoreEvidence() {
        /** 两个操作访问同一份测试报告及其采集记录。 */
        val captures = AnrCaptureStore(root)
        /** 使用共享现场存储的报告入口。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 已提交但尚未冻结的报告。 */
        val report = saveCaptured(configured, captures, 100, freeze = false)
        /** 并发释放两个任务，覆盖删除与冻结的不同先后顺序。 */
        val start = CountDownLatch(1)
        /** 专用于本次并发测试的线程池。 */
        val pool = Executors.newFixedThreadPool(2)
        try {
            /** 冻结任务与删除任务由同一门闩启动。 */
            val freeze = pool.submit<Boolean> {
                start.await()
                captures.freeze(report.id)
            }
            /** 另一任务从报告索引中删除同一事件。 */
            val delete = pool.submit<Boolean> {
                start.await()
                configured.delete(report.id) is AnrReportResult.Success
            }
            start.countDown()
            assertTrue(freeze.get(5, TimeUnit.SECONDS))
            assertTrue(delete.get(5, TimeUnit.SECONDS))
            assertNull(captures.readEvidence(report.id))
            assertEquals(report.id, captures.eligible("target", 0, 1).single().event.eventId)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 按数量和期限移除报告时，其本地副本同步删除，上传队列独立保留。 */
    @Test
    fun retentionRemovesEvidenceForEvictedAndExpiredReports() {
        /** 本地报告与诊断共用的现场存储。 */
        val captures = AnrCaptureStore(root)
        /** 最多保留一份报告，并设置有限期限。 */
        val configured = AnrReportStore(root, AnrConfig(maxReportCount = 1, maxReportAgeMillis = 1_000),
            index, logger, captures = captures)
        /** 旧报告应在新报告保存时被淘汰。 */
        val older = saveCaptured(configured, captures, 100, freeze = true)
        /** 新报告随后因超过期限被清理。 */
        val newer = saveCaptured(configured, captures, 200, freeze = true)
        assertNull(captures.readEvidence(older.id))
        assertTrue(File(root, "evidence/${newer.id}.json").isFile)
        assertEquals(1, (configured.clean(1_201) as AnrReportResult.Success).value)
        assertNull(captures.readEvidence(newer.id))
        assertEquals(setOf(older.id, newer.id), captures.eligible("target", 0, 20).map { it.event.eventId }.toSet())
    }

    /** 报告正文按字节容量淘汰时，已冻结的现场也不再残留。 */
    @Test
    fun byteCapacityEvictionRemovesEvidence() {
        /** 与报告共用的现场存储。 */
        val captures = AnrCaptureStore(root)
        /** 先保存一份报告以取得其实际正文大小。 */
        val initial = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 将被字节容量淘汰的报告。 */
        val older = saveCaptured(initial, captures, 100, freeze = true)
        /** 报告正文的真实 UTF-8 字节数。 */
        val bytes = (initial.list(20) as AnrReportResult.Success).value.reports.single().sizeBytes
        /** 容量只允许保留一份正文。 */
        val limited = AnrReportStore(root, AnrConfig(maxStorageBytes = bytes), index, logger, captures = captures)
        /** 保存后会淘汰旧报告的新报告。 */
        val newer = saveCaptured(limited, captures, 200, freeze = true)
        assertNull(captures.readEvidence(older.id))
        assertTrue(File(root, "evidence/${newer.id}.json").isFile)
        assertEquals(setOf(older.id, newer.id), captures.eligible("target", 0, 20).map { it.event.eventId }.toSet())
    }

    /** 副本删除失败时保留待清理索引，调用同一删除操作可重试。 */
    @Test
    fun retriesEvidenceDeletionAfterFailure() {
        /** 首次删除诊断文件时注入失败。 */
        var failDelete = true
        /** 可恢复的诊断删除函数。 */
        val captures = AnrCaptureStore(root, deleteEvidenceFile = { file -> !failDelete && file.delete() })
        /** 与诊断存储关联的报告入口。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 已冻结的测试报告。 */
        val saved = saveCaptured(configured, captures, 100, freeze = true)
        assertEquals(AnrReportError.IO_ERROR, (configured.delete(saved.id) as AnrReportResult.Failure).error)
        assertTrue(File(root, "evidence/${saved.id}.json").isFile)
        assertEquals(saved.id, captures.eligible("target", 0, 1).single().event.eventId)
        failDelete = false
        assertTrue(configured.delete(saved.id) is AnrReportResult.Success)
        assertNull(captures.readEvidence(saved.id))
    }

    /** 清理历史孤立副本及未提交报告的采集，同时保留已登记上传任务。 */
    @Test
    fun cleansOrphanEvidenceAndPreservesPendingEvents() {
        /** 本次使用独立的报告与现场存储。 */
        val captures = AnrCaptureStore(root)
        /** 索引中没有测试事件的报告入口。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 模拟历史遗留的已冻结孤立副本。 */
        val frozen = signalReport(100)
        assertTrue(captures.begin(AnrCaptureRecord(frozen, "target", "35", "model")))
        assertTrue(captures.freeze(frozen.id))
        /** 模拟报告提交前中断的进行中采集。 */
        val collecting = signalReport(200)
        assertTrue(captures.begin(AnrCaptureRecord(collecting, "target", "35", "model")))
        assertTrue(configured.clean(200) is AnrReportResult.Success)
        assertNull(captures.readEvidence(frozen.id))
        /** 新进程恢复前次采集。 */
        val restarted = AnrCaptureStore(root)
        assertEquals(1, restarted.recover())
        assertNull(restarted.readEvidence(collecting.id))
        assertEquals(setOf(frozen.id, collecting.id), restarted.eligible("target", 0, 20).map { it.event.eventId }.toSet())
    }

    /** 报告索引损坏时不能把有效诊断副本误判为孤立数据。 */
    @Test
    fun damagedIndexDoesNotDeleteEvidence() {
        /** 与报告存储共用的现场存储。 */
        val captures = AnrCaptureStore(root)
        /** 带有可注入损坏的测试索引。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 索引损坏前已提交的现场。 */
        val saved = saveCaptured(configured, captures, 100, freeze = true)
        index.file.writeText("{")
        assertEquals(AnrReportError.CORRUPT, (configured.reconcileEvidence() as AnrReportResult.Failure).error)
        assertTrue(File(root, "evidence/${saved.id}.json").isFile)
    }

    /** 诊断目录不可读取时返回失败，恢复目录后允许重试。 */
    @Test
    fun retriesWhenEvidenceDirectoryCannotBeListed() {
        /** 用普通文件模拟不可读取的诊断目录。 */
        val directory = File(root, "evidence")
        directory.writeText("not a directory")
        /** 核对过程会将目录访问失败传递给调用方。 */
        val configured = store()
        assertEquals(AnrReportError.IO_ERROR, (configured.reconcileEvidence() as AnrReportResult.Failure).error)
        assertTrue(directory.delete())
        assertTrue(configured.reconcileEvidence() is AnrReportResult.Success)
    }

    /** 诊断目录指向外部时拒绝删除，并保留待重试的索引。 */
    @Test
    fun rejectsEvidenceDirectoryOutsidePrivateRoot() {
        /** 与报告存储共用的现场存储。 */
        val captures = AnrCaptureStore(root)
        /** 指向测试目录的报告存储。 */
        val configured = AnrReportStore(root, AnrConfig(), index, logger, captures = captures)
        /** 需要清理的已冻结报告。 */
        val saved = saveCaptured(configured, captures, 100, freeze = true)
        /** 本模块之外的测试目录。 */
        val outside = Files.createTempDirectory("anr-evidence-outside").toFile()
        /** 原诊断目录和临时备份。 */
        val directory = File(root, "evidence")
        val backup = File(root, "evidence-backup")
        /** 将诊断目录替换为符号链接。 */
        val link = directory.toPath()
        try {
            assertTrue(directory.renameTo(backup))
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (failure: Exception) {
                assumeNoException(failure)
            }
            /** 不属于本模块的同名文件必须保留。 */
            val foreign = File(outside, "${saved.id}.json")
            foreign.writeText("other data")
            assertEquals(AnrReportError.IO_ERROR, (configured.delete(saved.id) as AnrReportResult.Failure).error)
            assertEquals("other data", foreign.readText())
            Files.deleteIfExists(link)
            assertTrue(backup.renameTo(directory))
            assertTrue(configured.delete(saved.id) is AnrReportResult.Success)
        } finally {
            if (Files.isSymbolicLink(link)) Files.deleteIfExists(link)
            if (backup.exists()) backup.renameTo(directory)
            outside.deleteRecursively()
        }
    }

    /** 区分非法路径、缺失、损坏索引及损坏正文。 */
    @Test
    fun rejectsInvalidIdsAndDistinguishesCorruptFiles() {
        /** 已提交的报告及其正文文件。 */
        val report = report(100, "trace")
        val store = store()
        assertTrue(store.save(report) is AnrReportResult.Success)
        assertEquals(AnrReportError.INVALID_ID, (store.read("../outside") as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.NOT_FOUND, (store.read(UUID.randomUUID().toString()) as AnrReportResult.Failure).error)
        File(root, "reports/${report.id}.json").writeText("{")
        assertEquals(AnrReportError.CORRUPT, (store.read(report.id) as AnrReportResult.Failure).error)
        File(root, "reports/${report.id}.json").writeText("{\"id\":\"${report.id}\",\"type\":\"ANR_TRACE\"}")
        assertEquals(AnrReportError.CORRUPT, (store.read(report.id) as AnrReportResult.Failure).error)
        index.file.writeText("{")
        assertEquals(AnrReportError.CORRUPT, (store.list(20) as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.CORRUPT, (store.save(report(200, "new")) as AnrReportResult.Failure).error)
    }

    /** 超过容量的单条报告不能出现在索引中。 */
    @Test
    fun refusesReportLargerThanCapacity() {
        /** 单字节容量小于任何有效的报告正文。 */
        val configured = store(AnrConfig(maxStorageBytes = 1))
        assertEquals(AnrReportError.TOO_LARGE, (configured.save(report(100, "trace")) as AnrReportResult.Failure).error)
        assertTrue((configured.list(20) as AnrReportResult.Success).value.reports.isEmpty())
    }

    /** 报告总字节数超额时保留较新的报告。 */
    @Test
    fun appliesByteCapacityToCompletedReports() {
        /** 两份正文大小一致的报告。 */
        val older = report(100, "trace")
        val newer = report(200, "trace")
        /** 先取得实际提交的正文大小，避免依赖 JSON 字段排列。 */
        val first = (store().save(older) as AnrReportResult.Success).value
        val limited = store(AnrConfig(maxStorageBytes = first.sizeBytes))
        assertTrue(limited.save(newer) is AnrReportResult.Success)
        assertEquals(AnrReportError.NOT_FOUND, (limited.read(older.id) as AnrReportResult.Failure).error)
        assertEquals(newer, (limited.read(newer.id) as AnrReportResult.Success).value)
        assertFalse(File(root, "reports/${older.id}.json").exists())
    }

    /** 清理 ANR 报告时不会修改相邻功能的私有文件。 */
    @Test
    fun cleanupDoesNotTouchOtherFeatureDirectory() {
        /** 模拟应用私有目录中并列的 ANR 和 Crash 目录。 */
        val privateDirectory = Files.createTempDirectory("anr-sibling-test").toFile()
        try {
            val anrRoot = File(privateDirectory, "performance-anr")
            val otherFile = File(privateDirectory, "performance-crash/report.json")
            assertTrue(otherFile.parentFile!!.mkdirs())
            otherFile.writeText("other feature")
            val configured = AnrReportStore(
                anrRoot,
                AnrConfig(maxReportAgeMillis = 1),
                TestIndex(File(anrRoot, "index.json")),
                logger,
            )
            assertTrue(configured.save(report(100, "trace")) is AnrReportResult.Success)
            assertTrue(configured.clean(102) is AnrReportResult.Success)
            assertEquals("other feature", otherFile.readText())
        } finally {
            privateDirectory.deleteRecursively()
        }
    }

    /** 清理失败后记录待删除条目，不能把未释放的文件当作已经清理。 */
    @Test
    fun retriesFailedCleanupWithoutLosingCompletedReport() {
        /** 首次模拟底层文件删除失败。 */
        var failDelete = true
        val configured = AnrReportStore(
            root,
            AnrConfig(maxReportCount = 1),
            index,
            logger,
            deleteReportFile = { file -> !failDelete && file.delete() },
        )
        /** 新报告提交后，旧报告应进入可重试的清理列表。 */
        val old = report(100, "old")
        val newest = report(200, "new")
        assertTrue(configured.save(old) is AnrReportResult.Success)
        assertTrue(configured.save(newest) is AnrReportResult.Success)
        assertTrue(File(root, "reports/${old.id}.json").exists())
        assertEquals(AnrReportError.IO_ERROR, (configured.clean(200) as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.IO_ERROR, (configured.save(report(300, "later")) as AnrReportResult.Failure).error)
        assertEquals(newest, (configured.read(newest.id) as AnrReportResult.Success).value)

        failDelete = false
        assertTrue(configured.clean(200) is AnrReportResult.Success)
        assertFalse(File(root, "reports/${old.id}.json").exists())
        assertEquals(newest, (configured.read(newest.id) as AnrReportResult.Success).value)
    }

    /** 显式删除失败后，使用相同 ID 重试应完成待清理文件删除。 */
    @Test
    fun retriesExplicitDeleteAfterIoFailure() {
        /** 模拟文件系统暂时拒绝删除。 */
        var failDelete = true
        val configured = AnrReportStore(
            root,
            AnrConfig(),
            index,
            logger,
            deleteReportFile = { file -> !failDelete && file.delete() },
        )
        /** 需要显式删除的已提交报告。 */
        val saved = report(100, "trace")
        assertTrue(configured.save(saved) is AnrReportResult.Success)
        assertEquals(AnrReportError.IO_ERROR, (configured.delete(saved.id) as AnrReportResult.Failure).error)
        assertTrue(File(root, "reports/${saved.id}.json").exists())
        failDelete = false
        assertTrue(configured.delete(saved.id) is AnrReportResult.Success)
        assertFalse(File(root, "reports/${saved.id}.json").exists())
    }

    /** 报告目录不能通过符号链接指向功能目录之外。 */
    @Test
    fun rejectsReportDirectoryOutsidePrivateRoot() {
        /** 测试专属的外部目录和目录链接。 */
        val outside = Files.createTempDirectory("anr-outside-test").toFile()
        val link = File(root, "reports").toPath()
        try {
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (failure: Exception) {
                // 部分 Windows 环境未开放创建符号链接的权限。
                assumeNoException(failure)
            }
            assertEquals(AnrReportError.IO_ERROR, (store().save(report(100, "trace")) as AnrReportResult.Failure).error)
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        } finally {
            Files.deleteIfExists(link)
            outside.deleteRecursively()
        }
    }

    /** 读取 I/O 错误与索引损坏使用不同的结果。 */
    @Test
    fun distinguishesIndexIoFailureFromCorruption() {
        /** 永远抛出 I/O 错误的索引读取器。 */
        val failingIndex = object : AnrAtomicIndex {
            /** 模拟底层读取失败。 */
            override fun read(): String? = throw IOException("injected failure")

            /** 本用例不执行写入。 */
            override fun write(content: String) = throw IOException("injected failure")
        }
        val failing = AnrReportStore(root, AnrConfig(), failingIndex, logger)
        assertEquals(AnrReportError.IO_ERROR, (failing.list(20) as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.IO_ERROR, (failing.read(UUID.randomUUID().toString()) as AnrReportResult.Failure).error)
    }

    /** 即使文件大小与索引一致，缺失原进程身份也必须识别为损坏。 */
    @Test
    fun rejectsIncompleteIdentityInCommittedReport() {
        /** 提交报告后构造缺少身份字段的损坏正文。 */
        val original = report(100, "trace")
        val configured = store()
        assertTrue(configured.save(original) is AnrReportResult.Success)
        val damaged = "{\"id\":\"${original.id}\",\"createdAtMillis\":100,\"type\":\"ANR_TRACE\",\"identity\":{}}"
        File(root, "reports/${original.id}.json").writeText(damaged)
        /** 使索引大小与损坏正文一致，单独验证身份字段检查。 */
        val json = JsonParser.parseString(index.file.readText()).asJsonObject
        json.getAsJsonArray("entries")[0].asJsonObject.addProperty("sizeBytes", damaged.toByteArray().size)
        index.file.writeText(json.toString())
        assertEquals(AnrReportError.CORRUPT, (configured.read(original.id) as AnrReportResult.Failure).error)
    }

    /** 支持在加入待清理字段前已经提交的版本 1 索引。 */
    @Test
    fun readsIndexWithoutPendingDeletionField() {
        index.file.writeText("{\"version\":1,\"entries\":[]}")
        assertTrue((store().list(20) as AnrReportResult.Success).value.reports.isEmpty())
    }

    /** 页大小和游标无效时返回明确错误。 */
    @Test
    fun rejectsInvalidPageRequests() {
        assertEquals(AnrReportError.INVALID_PAGE, (store().list(0) as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.INVALID_PAGE, (store().list(21) as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.INVALID_PAGE, (store().list(1, "../invalid") as AnrReportResult.Failure).error)
        assertEquals(AnrReportError.INVALID_PAGE, (store().list(1, UUID.randomUUID().toString()) as AnrReportResult.Failure).error)
    }

    /** 列表仅读索引，缺失正文时仍可识别出具体读取错误。 */
    @Test
    fun listsSummaryWithoutLoadingTraceBody() {
        /** 删除正文以确认摘要查询不会读取其内容。 */
        val saved = report(100, "trace")
        val configured = store()
        assertTrue(configured.save(saved) is AnrReportResult.Success)
        assertTrue(File(root, "reports/${saved.id}.json").delete())
        assertEquals(saved.id, (configured.list(20) as AnrReportResult.Success).value.reports.single().id)
        assertEquals(AnrReportError.NOT_FOUND, (configured.read(saved.id) as AnrReportResult.Failure).error)
    }

    /** 正文不可读时与缺失和损坏分别返回 I/O 失败。 */
    @Test
    fun reportsUnreadableBodyAsIoFailure() {
        /** 已提交的正文文件。 */
        val saved = report(100, "trace")
        val configured = store()
        assertTrue(configured.save(saved) is AnrReportResult.Success)
        val file = File(root, "reports/${saved.id}.json")
        try {
            assumeTrue(file.setReadable(false, false) && !file.canRead())
            assertEquals(AnrReportError.IO_ERROR, (configured.read(saved.id) as AnrReportResult.Failure).error)
        } finally {
            file.setReadable(true, true)
        }
    }

    /** 列表与过期清理并发时索引仍保持可读。 */
    @Test
    fun listsWhileAnotherThreadCleans() {
        /** 准备多条会被过期策略清理的报告。 */
        val configured = store(AnrConfig(maxReportCount = 20, maxReportAgeMillis = 1_000))
        repeat(8) { assertTrue(configured.save(report(100L + it, "trace")) is AnrReportResult.Success) }
        /** 由独立线程并发读取摘要和执行清理。 */
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit<Boolean> {
                repeat(30) {
                    if (configured.list(20) !is AnrReportResult.Success) return@submit false
                }
                true
            }
            val cleaner = pool.submit<Boolean> { configured.clean(2_000) is AnrReportResult.Success }
            assertTrue(reader.get(5, TimeUnit.SECONDS))
            assertTrue(cleaner.get(5, TimeUnit.SECONDS))
            assertTrue(configured.list(20) is AnrReportResult.Success)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 构造使用 JVM 测试索引的报告存储。 */
    private fun store(config: AnrConfig = AnrConfig()): AnrReportStore =
        AnrReportStore(root, config, index, logger)

    /** 构造可形成待上传事件的系统判定报告。 */
    private fun signalReport(time: Long): AnrReport {
        /** 为测试生成带原进程身份的基础报告。 */
        val report = report(time, "", AnrReportType.SIGNAL_ANR)
        return report.copy(captureId = report.id, trace = null, javaStack = "main", fromProcessErrorState = false)
    }

    /** 模拟核心提交、报告提交及按需冻结。 */
    private fun saveCaptured(store: AnrReportStore, captures: AnrCaptureStore, time: Long, freeze: Boolean): AnrReport {
        /** 使用同一个事件身份提交核心及报告。 */
        val report = signalReport(time)
        assertTrue(captures.begin(AnrCaptureRecord(report, "target", "35", "model")))
        assertTrue(store.save(report) is AnrReportResult.Success)
        if (freeze) assertTrue(captures.freeze(report.id))
        return report
    }

    /** 使用固定身份生成可区分的测试报告。 */
    private fun report(time: Long, trace: String, type: AnrReportType = AnrReportType.ANR_TRACE): AnrReport =
        AnrReport(
            id = UUID.randomUUID().toString(),
            createdAtMillis = time,
            type = type,
            identity = AnrReportIdentity("device", "session", "process", "app.test", "1", 1, "build", "test", "test"),
            trace = trace,
        )

    /** 可注入失败的 JVM 索引实现。 */
    private class TestIndex(
        /** 索引测试文件。 */
        val file: File,
    ) : AnrAtomicIndex {
        /** 是否模拟索引提交失败。 */
        var failWrites = false

        /** 读取测试索引。 */
        override fun read(): String? = file.takeIf { it.exists() }?.readText()

        /** 写入测试索引，按需模拟 I/O 错误。 */
        override fun write(content: String) {
            if (failWrites) throw IOException("No space left on device (injected)")
            file.parentFile!!.mkdirs()
            file.writeText(content)
        }
    }
}
