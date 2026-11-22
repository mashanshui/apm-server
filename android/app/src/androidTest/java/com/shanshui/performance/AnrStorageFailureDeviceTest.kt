package com.shanshui.performance

import android.app.Application
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrHandle
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportSummary
import com.shanshui.performance.anr.AnrReportType
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离设备上验证真实文件写入失败不会产生成功回调。 */
@RunWith(AndroidJUnit4::class)
class AnrStorageFailureDeviceTest {
    /** 报告目录不可写时保留旧报告；恢复权限后再次采集应成功。 */
    @Test
    fun reportWriteFailureDoesNotReportSuccess() {
        /** 仅在不自动启动示例 SDK 的测试进程中运行。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync { handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true))) }
        val handle = handleRef.get()
        assertTrue(handle.isAvailable)
        /** 接收成功提交的报告摘要。 */
        val saved = LinkedBlockingQueue<AnrReportSummary>()
        handle.setReportListener { summary -> if (summary.type == AnrReportType.MANUAL_TRACE) saved.offer(summary) }
        /** 报告目录权限与 Matrix 活动 trace 路径。 */
        val reportsDirectory = File(application.noBackupFilesDir, "performance-anr/reports")
        val activeTrace = File(application.noBackupFilesDir, "performance-anr/active/manual.trace")
        var originalMode: Int? = null
        try {
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            val first = saved.poll(20, TimeUnit.SECONDS)
            assertNotNull("首次报告未保存", first)
            assertTrue(handle.reports.read(first!!.id) is AnrReportResult.Success)
            /** 移除测试应用报告目录的写权限，实际触发文件系统错误。 */
            originalMode = Os.stat(reportsDirectory.absolutePath).st_mode and 0x1ff
            Os.chmod(reportsDirectory.absolutePath, 0x140)
            val previousWrite = activeTrace.lastModified()
            Thread.sleep(1_100)
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (activeTrace.lastModified() <= previousWrite && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
            assertTrue("Matrix 未生成第二次活动 trace", activeTrace.lastModified() > previousWrite)
            assertNull("正文写入失败不应回调成功", saved.poll(2, TimeUnit.SECONDS))
            assertTrue("失败后旧报告应仍可读", handle.reports.read(first.id) is AnrReportResult.Success)

            Os.chmod(reportsDirectory.absolutePath, originalMode)
            originalMode = null
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            val restored = saved.poll(20, TimeUnit.SECONDS)
            assertNotNull("恢复权限后未能保存报告", restored)
            assertTrue(handle.reports.delete(first.id) is AnrReportResult.Success)
            assertTrue(handle.reports.delete(restored!!.id) is AnrReportResult.Success)
        } finally {
            originalMode?.let { Os.chmod(reportsDirectory.absolutePath, it) }
            instrumentation.runOnMainSync { handle.close() }
        }
    }
}
