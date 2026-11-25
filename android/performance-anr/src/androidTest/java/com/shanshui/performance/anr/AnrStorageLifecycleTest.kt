package com.shanshui.performance.anr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android 文件系统与 AtomicFile 索引上验证 ANR 的恢复和清理。 */
@RunWith(AndroidJUnit4::class)
class AnrStorageLifecycleTest {
    /** 采集中删除报告后，重启补传仍包含已提交的系统说明。 */
    @Test
    fun deletingDuringCapturePreservesCommittedInfoForUpload() {
        /** 当前测试应用提供私有缓存目录。 */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** 测试专属目录不影响设备已有报告。 */
        val root = File(context.cacheDir, "anr-storage-${UUID.randomUUID()}")
        try {
            /** 采集记录和本地报告共用同一个私有目录。 */
            val captures = AnrCaptureStore(root)
            /** 使用真实 Android AtomicFile 的报告索引。 */
            val reports = AnrReportStore(root, AnrConfig(), captures = captures)
            /** 本次报告和事件共用的唯一标识。 */
            val id = UUID.randomUUID().toString()
            /** 原进程事件身份。 */
            val identity = AnrReportIdentity("device", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "com.example.test", "1.0", 1, "test-build", "test", "test")
            /** 系统状态判定时已经取得并裁剪的说明。 */
            val report = AnrReport(id, System.currentTimeMillis(), AnrReportType.SIGNAL_ANR, identity,
                captureId = id, javaStack = "example.main(Main.kt:1)", fromProcessErrorState = true,
                anrInfo = "system info", anrInfoTruncated = true)
            assertTrue(captures.begin(AnrCaptureRecord(report, "target", "15", "test")))
            assertTrue(reports.save(report) is AnrReportResult.Success)
            assertTrue(reports.delete(id) is AnrReportResult.Success)

            /** 新实例模拟原进程中断后的队列恢复。 */
            val restarted = AnrCaptureStore(root)
            assertEquals(1, restarted.recover())
            assertNull(restarted.readEvidence(id))
            /** 上传事件继续使用原身份及已提交说明。 */
            val event = restarted.eligible("target", System.currentTimeMillis(), 1).single().event
            assertEquals(id, event.eventId)
            assertEquals("process_error_state", event.anr.source)
            assertEquals("system info", event.anr.anrInfo.text)
            assertEquals("size_limit", event.anr.anrInfo.reason)
            assertTrue(event.anr.anrInfo.truncated)
        } finally {
            root.deleteRecursively()
        }
    }
}
