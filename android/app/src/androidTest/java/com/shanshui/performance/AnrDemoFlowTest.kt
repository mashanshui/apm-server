package com.shanshui.performance

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportSummary
import com.shanshui.performance.anr.AnrReportType
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证从示例主页进入 Matrix ANR 页面并查看本地结果。 */
@RunWith(AndroidJUnit4::class)
class AnrDemoFlowTest {
    /** 手动 trace 必须产生明确标记的已保存报告，并支持页面详情和删除。 */
    @Test
    fun navigatesAndReadsManualTrace() {
        /** 当前示例应用的本地报告入口。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        val reports = AnrComponent.reports(application)
        /** 记录触发前的报告，避免将历史结果误认成本次结果。 */
        val previousIds = summaries(reports).map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.anrTestButton)).perform(click())
            onView(withText("手动生成 trace")).perform(click())
            /** 等待 Matrix 回调和异步报告提交。 */
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var captured: AnrReportSummary? = null
            while (SystemClock.elapsedRealtime() < deadline && captured == null) {
                captured = summaries(reports).firstOrNull {
                    it.id !in previousIds && it.type == AnrReportType.MANUAL_TRACE
                }
                if (captured == null) SystemClock.sleep(100)
            }
            assertNotNull("未保存手动 trace", captured)
            /** 页面展示的报告摘要。 */
            val summary = captured!!
            val label = "${summary.type}  ${summary.createdAtMillis}"
            /** 报告保存线程和页面刷新线程彼此独立，等待摘要显示。 */
            val displayDeadline = SystemClock.elapsedRealtime() + 5_000
            var visible = false
            while (SystemClock.elapsedRealtime() < displayDeadline && !visible) {
                visible = runCatching { onView(withText(label)).check(matches(withText(label))) }.isSuccess
                if (!visible) SystemClock.sleep(100)
            }
            assertTrue("报告摘要未显示", visible)
            onView(withText(label)).perform(scrollTo(), click())
            onView(withText(containsString("来源：MANUAL_TRACE"))).check(matches(withText(containsString("来源：MANUAL_TRACE"))))
            onView(allOf(withText("删除"), hasSibling(withText(label)))).perform(scrollTo(), click())
            /** 删除提交后，独立入口也不应再找到该报告。 */
            val deleteDeadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deleteDeadline && reports.read(summary.id) is AnrReportResult.Success) {
                SystemClock.sleep(100)
            }
            assertTrue(reports.read(summary.id) is AnrReportResult.Failure)
        }
    }

    /** 从报告入口读取第一页摘要，不加载 trace 正文。 */
    private fun summaries(reports: com.shanshui.performance.anr.AnrReports): List<AnrReportSummary> =
        (reports.list(20) as? AnrReportResult.Success)?.value?.reports.orEmpty()
}
