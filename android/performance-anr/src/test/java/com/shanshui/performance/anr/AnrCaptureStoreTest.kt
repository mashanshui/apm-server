package com.shanshui.performance.anr

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.shanshui.performance.network.CrashMemory
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证核心先提交、分区恢复、冻结和原目标隔离。 */
class AnrCaptureStoreTest {
    /** 核心无法提交时不创建可恢复或可上传事件。 */
    @Test
    fun doesNotQueueWhenCoreCannotBeCommitted() {
        val directory = Files.createTempDirectory("anr-no-core").toFile()
        try {
            val blocked = java.io.File(directory, "not-a-directory").apply { writeText("x") }
            val store = AnrCaptureStore(blocked)
            val record = capture("target")
            assertFalse(store.begin(record))
            assertEquals(0, store.recover())
            assertTrue(store.eligible("target", Long.MAX_VALUE, 1).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 进程中断后只恢复已提交分区，重试不改写原事件。 */
    @Test
    fun recoversPartialCaptureWithoutChangingIdentityOrPayload() {
        val root = Files.createTempDirectory("anr-capture").toFile()
        try {
            val store = AnrCaptureStore(root)
            val record = capture("target-a")
            assertTrue(store.begin(record))
            assertTrue(store.update(record.report.id) { it.copy(diagnostics = it.diagnostics.copy(
                memory = CrashMemory(status = "ok", javaHeapUsedBytes = 0))) })

            val restarted = AnrCaptureStore(root)
            assertEquals(1, restarted.recover())
            val event = restarted.eligible("target-a", 0, 20).single().event
            assertEquals(record.report.id, event.eventId)
            assertEquals(record.report.identity.sessionId, event.sessionId)
            assertEquals(record.report.identity.processId, event.processId)
            assertEquals(0L, event.anr.diagnostics.memory?.javaHeapUsedBytes)
            assertEquals("interrupted", event.anr.anrInfo.reason)
            assertEquals("interrupted", event.anr.trace.reason)
            assertFalse(restarted.update(record.report.id) { it.copy(info = AnrTextPartition("ok", text = "late")) })
            assertTrue(restarted.retry("target-a", event.eventId, 10))
            assertEquals(event, restarted.eligible("target-a", 10, 20).single().event)
            assertTrue(restarted.eligible("other-target", 10, 20).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 系统判定已取得的说明随核心提交，重启后仍可按原来源补传。 */
    @Test
    fun recoversSystemInfoCommittedWithCore() {
        /** 专用于本次恢复测试的私有目录。 */
        val root = Files.createTempDirectory("anr-core-info").toFile()
        try {
            /** 从系统状态判定得到说明和截断状态。 */
            val original = capture("target")
            /** 与核心同时提交的现场。 */
            val record = original.copy(report = original.report.copy(fromProcessErrorState = true,
                anrInfo = "system blocked", anrInfoTruncated = true))
            /** 原进程中的采集存储。 */
            val store = AnrCaptureStore(root)
            assertTrue(store.begin(record))
            /** 检查异步分区运行前已经提交的核心。 */
            val stored = JsonParser.parseString(File(root, "capturing/${record.report.id}.json").readText()).asJsonObject
            assertEquals("partial", stored.getAsJsonObject("info").get("status").asString)

            /** 新实例模拟原进程退出。 */
            val restarted = AnrCaptureStore(root)
            assertEquals(1, restarted.recover())
            /** 重新构造的待确认事件。 */
            val event = restarted.eligible("target", 0, 1).single().event
            assertEquals("process_error_state", event.anr.source)
            assertEquals("partial", event.anr.anrInfo.status)
            assertEquals("size_limit", event.anr.anrInfo.reason)
            assertTrue(event.anr.anrInfo.truncated)
            assertTrue(event.anr.anrInfo.text!!.contains("system blocked"))
            assertEquals(event.anr.anrInfo, restarted.readEvidence(event.eventId)?.info)
            assertTrue(restarted.retry("target", event.eventId, 10))
            assertEquals(event, restarted.eligible("target", 10, 1).single().event)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 旧核心中的系统说明可恢复，明确标记为归属不明的分区不能被覆盖。 */
    @Test
    fun recoversLegacyCoreInfoWithoutOverridingAmbiguousStatus() {
        /** 测试独立的历史核心和归属不明分区。 */
        val root = Files.createTempDirectory("anr-legacy-info").toFile()
        try {
            /** 原进程采集入口。 */
            val store = AnrCaptureStore(root)
            /** 系统判定前的基础事件。 */
            val original = capture("target")
            /** 模拟旧版只在报告字段中保存系统说明。 */
            val legacy = original.copy(report = original.report.copy(fromProcessErrorState = true, anrInfo = "legacy info"))
            assertTrue(store.begin(legacy))
            /** 从已保存核心中移除新版分区字段。 */
            val file = File(root, "capturing/${legacy.report.id}.json")
            /** 不改变其余已提交字段。 */
            val json = JsonParser.parseString(file.readText()).asJsonObject
            json.remove("info")
            json.remove("discardEvidence")
            file.writeText(json.toString())

            /** 第二条事件明确记录归属不明。 */
            val other = capture("target")
            /** 已记录的失败状态不能被报告中的旧说明覆盖。 */
            val ambiguous = other.copy(report = other.report.copy(fromProcessErrorState = true, anrInfo = "uncertain"),
                info = AnrTextPartition("unavailable", "ambiguous"))
            assertTrue(store.begin(ambiguous))
            /** 新进程恢复两份采集。 */
            val restarted = AnrCaptureStore(root)
            assertEquals(2, restarted.recover())
            /** 按事件身份核对各自的说明状态。 */
            val events = restarted.eligible("target", 0, 20).associateBy { it.event.eventId }
            assertEquals("legacy info", events.getValue(legacy.report.id).event.anr.anrInfo.text)
            assertNull(events.getValue(legacy.report.id).event.anr.anrInfo.capturedAt)
            assertEquals("ambiguous", events.getValue(ambiguous.report.id).event.anr.anrInfo.reason)
            assertNull(events.getValue(ambiguous.report.id).event.anr.anrInfo.text)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 旧目标的过期记录不会占满新目标的队列，未过期记录仍归属原目标。 */
    @Test
    fun removesExpiredRecordsAcrossTargetsWhenQueueIsFull() {
        /** 保存旧目标事件的独立目录。 */
        val root = Files.createTempDirectory("anr-expired-target").toFile()
        try {
            /** 固定当前时间以构造过期和未过期事件。 */
            val now = 2 * ANR_PENDING_RETENTION_MILLIS
            /** 队列容量不足时使用受控时间。 */
            val store = AnrCaptureStore(root, wallTimeMillis = { now })
            repeat(199) {
                /** 旧目标下超过保留期的事件。 */
                val expired = capture("old-target")
                assertTrue(store.begin(expired))
                assertTrue(store.freeze(expired.report.id))
            }
            /** 同一旧目标下仍在保留期内的事件。 */
            val original = capture("old-target")
            /** 更新发生时间，确保不会被过期清理删除。 */
            val recent = original.copy(report = original.report.copy(createdAtMillis = now))
            assertTrue(store.begin(recent))
            assertTrue(store.freeze(recent.report.id))

            /** 使用新凭据产生的事件。 */
            val newOriginal = capture("new-target")
            /** 新事件使用当前时间并绑定新目标。 */
            val fresh = newOriginal.copy(report = newOriginal.report.copy(createdAtMillis = now))
            assertTrue(store.begin(fresh))
            assertTrue(store.freeze(fresh.report.id))
            assertEquals(recent.report.id, store.eligible("old-target", now, 20).single().event.eventId)
            assertEquals(fresh.report.id, store.eligible("new-target", now, 20).single().event.eventId)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 过期结果不能修改已冻结现场，本地模式不产生待上传事件。 */
    @Test
    fun closesCaptureWindowAndKeepsLocalModeLocal() {
        val root = Files.createTempDirectory("anr-window").toFile()
        try {
            var now = 0L
            val store = AnrCaptureStore(root, nanoTime = { now })
            val record = capture(null)
            assertTrue(store.begin(record))
            now += TimeUnit.SECONDS.toNanos(5)
            assertFalse(store.update(record.report.id) { it.copy(info = AnrTextPartition("ok", text = "late")) })
            assertTrue(store.freeze(record.report.id))
            assertEquals("timeout", store.readEvidence(record.report.id)?.info?.reason)
            assertTrue(store.eligible("target", Long.MAX_VALUE, 20).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 单独 trace、手动或 debugger 报告不进入捕获和上传队列。 */
    @Test
    fun neverEnqueuesTraceWithoutConfirmedAnr() {
        val root = Files.createTempDirectory("anr-trace-only").toFile()
        try {
            val store = AnrCaptureStore(root)
            val original = capture("target")
            for (type in listOf(AnrReportType.ANR_TRACE, AnrReportType.MANUAL_TRACE, AnrReportType.DEBUGGER_SIGNAL)) {
                assertFalse(store.begin(original.copy(report = original.report.copy(type = type))))
            }
            assertTrue(store.eligible("target", Long.MAX_VALUE, 20).isEmpty())
            assertNull(store.readEvidence(original.report.id))
        } finally {
            root.deleteRecursively()
        }
    }

    /** 中文、代理对和 JSON 转义都按真实字节裁剪并保留状态。 */
    @Test
    fun freezesWithinEscapedUtf8Budgets() {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val original = capture("target")
        val large = "中😀\"\\\n".repeat(40_000)
        val event = AnrEventFactory().freeze(original.copy(
            report = original.report.copy(javaStack = large),
            info = AnrTextPartition("ok", text = large),
            trace = AnrTextPartition("ok", text = large, source = "art_first_write"),
        ), "timeout")
        assertTrue(gson.toJson(event.anr.mainThreadStack).toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertTrue(gson.toJson(event.anr.anrInfo).toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertTrue(gson.toJson(event.anr.trace).toByteArray(Charsets.UTF_8).size <= 96 * 1024)
        assertTrue(gson.toJson(event).toByteArray(Charsets.UTF_8).size <= 240 * 1024)
        assertTrue(event.anr.mainThreadStackTruncated)
        assertEquals("size_limit", event.anr.anrInfo.reason)
        assertTrue(event.anr.trace.truncated)
        assertNotNull(event.anr.trace.text)
    }

    /** 构造真实格式的原始报告和可选目标。 */
    private fun capture(target: String?): AnrCaptureRecord {
        val id = UUID.randomUUID().toString()
        val identity = AnrReportIdentity("device", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "com.example.app", "1.0", 1, "1.0-1", "debug", "test")
        val report = AnrReport(id, 100, AnrReportType.SIGNAL_ANR, identity,
            captureId = id, javaStack = "sample.main(Main.kt:1)", fromProcessErrorState = false)
        return AnrCaptureRecord(report, target, "35", "model")
    }
}
