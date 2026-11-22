package com.shanshui.performance.anr

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证平台判断及原生库的按需加载。 */
@RunWith(AndroidJUnit4::class)
class AnrNativeLoadingTest {
    /** API 21/22 真机或模拟器必须保持报告可读且不加载 ANR JNI。 */
    @Test
    fun unsupportedApiStillReadsReportsWithoutNativeLoading() {
        assumeTrue(Build.VERSION.SDK_INT < AnrPlatform.MIN_API)
        /** 当前测试宿主和关闭采集后的报告入口。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        val handle = AnrComponent.start(application, AnrConfig(enabled = true))
        assertEquals(AnrInitStatus.UNSUPPORTED_API, handle.initResult.status)
        assertFalse(isNativeLoaded())
        /** 仅用于验证历史结果读取的合成报告。 */
        val report = AnrReport(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            AnrReportType.MANUAL_TRACE,
            AnrReportIdentity("device", "session", "process", application.packageName, "1", 1, "build", "test", "test"),
            trace = "test trace",
        )
        assertTrue(handle.reports.save(report) is AnrReportResult.Success)
        assertEquals(report, (handle.reports.read(report.id) as AnrReportResult.Success).value)
        assertTrue(handle.reports.delete(report.id) is AnrReportResult.Success)
        assertFalse(isNativeLoaded())
        handle.close()
    }

    /** 默认关闭和不支持平台检查不能隐式加载原生采集库。 */
    @Test
    fun onlyLoadsAfterExplicitSupportedCheck() {
        /** 仪器测试使用的独立宿主应用。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        // 完整仪器套件可能已由其他用例加载 JNI，避免把测试顺序误判为延迟加载失败。
        assumeTrue("需要尚未加载 ANR JNI 的测试进程", !isNativeLoaded())
        assertEquals(AnrInitStatus.DISABLED, AnrPlatform.check(application, AnrConfig())?.status)
        assertEquals(AnrInitStatus.UNSUPPORTED_API, AnrPlatform.check(AnrConfig(true), 22, true, true, "arm64-v8a")?.status)
        assertFalse("平台判断不应加载 ANR 原生库", isNativeLoaded())

        /** 默认关闭和不支持采集时，历史结果仍可独立读写。 */
        val reports = AnrComponent.reports(application)
        val report = AnrReport(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            AnrReportType.MANUAL_TRACE,
            AnrReportIdentity("device", "session", "process", application.packageName, "1", 1, "build", "test", "test"),
            trace = "test trace",
        )
        assertTrue(reports.save(report) is AnrReportResult.Success)
        assertEquals(report, (reports.read(report.id) as AnrReportResult.Success).value)
        assertTrue(reports.delete(report.id) is AnrReportResult.Success)
        assertFalse("读取历史报告不应加载 ANR 原生库", isNativeLoaded())

        assumeTrue(Build.VERSION.SDK_INT >= AnrPlatform.MIN_API)
        assumeTrue(android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == AnrPlatform.SUPPORTED_ABI)
        assertNull(AnrPlatform.check(application, AnrConfig(enabled = true)))
        assertTrue(AnrNativeLibrary.load())
        assertTrue("显式加载后应存在本模块的原生映射", isNativeLoaded())
    }

    /** 只判断本模块 SO 是否映射，不输出进程地址或其他映射信息。 */
    private fun isNativeLoaded(): Boolean = File("/proc/self/maps").useLines { lines ->
        lines.any { it.contains("/libapm_anr.so") }
    }
}
