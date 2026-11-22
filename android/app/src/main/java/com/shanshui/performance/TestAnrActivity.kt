package com.shanshui.performance

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReports
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Matrix ANR 手动触发和本地报告查看页面。 */
class TestAnrActivity : AppCompatActivity() {
    /** 本地读写放在后台，避免报告正文阻塞页面。 */
    private val io = Executors.newSingleThreadExecutor()
    /** 本地报告操作入口。 */
    private lateinit var reports: AnrReports
    /** 当前监控和回调状态。 */
    private lateinit var status: TextView
    /** 报告摘要容器。 */
    private lateinit var reportRows: LinearLayout
    /** 所选报告的正文。 */
    private lateinit var detail: TextView
    /** 分页按钮。 */
    private lateinit var more: Button
    /** 下一页游标。 */
    private var nextAfterId: String? = null

    /** 创建受控测试入口并观察报告保存回调。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        /** 全量 SDK 中的 Matrix 句柄。 */
        val handle = PerformanceSdk.current()?.anr
        reports = handle?.reports ?: AnrComponent.reports(application)
        /** 整页纵向布局。 */
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply {
            text = "ANR 状态：${handle?.initResult?.status ?: "未初始化"} ${handle?.initResult?.reason.orEmpty()}"
        }
        content.addView(status)
        content.addView(button("手动生成 trace") {
            if (handle?.printTrace() == true) {
                status.text = "已按 Matrix 方式请求手动 trace，等待保存回调"
                Log.i(TAG, "已请求 Matrix 手动 trace")
            } else {
                status.text = "ANR 采集器未启动"
            }
        }.apply { isEnabled = handle?.isAvailable == true })
        content.addView(button("触发输入 ANR（主线程阻塞 15 秒）") {
            Log.i(TAG, "开始受控输入 ANR 测试")
            Thread.sleep(15_000)
            Log.i(TAG, "受控输入 ANR 测试结束")
        })
        content.addView(button("触发主线程死锁（需重启应用）") {
            Log.i(TAG, "开始受控主线程死锁测试")
            triggerDeadlock()
        })
        content.addView(button("刷新本地报告") { loadReports(reset = true) })
        reportRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(reportRows)
        more = button("加载更多") { loadReports(reset = false) }.apply { isEnabled = false }
        content.addView(more)
        detail = TextView(this).apply { setTextIsSelectable(true) }
        content.addView(detail)
        content.addView(button("返回") { finish() })
        /** Android 15 的全屏布局需要避开系统栏，确保顶部按钮可点击。 */
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            /** 系统状态栏和导航栏占用的空间。 */
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        handle?.setReportListener { summary ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = "报告已保存：${summary.type}"
                    loadReports(reset = true)
                }
            }
        }
        loadReports(reset = true)
    }

    /** 创建一枚绑定操作的按钮。 */
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    /** 后台读取摘要，不提前加载所有 trace 正文。 */
    private fun loadReports(reset: Boolean) {
        /** 上一页最后一个报告 ID。 */
        val cursor = if (reset) null else nextAfterId ?: return
        io.execute {
            /** 本页本地读取结果。 */
            val result = reports.list(20, cursor)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is AnrReportResult.Success -> {
                        if (reset) reportRows.removeAllViews()
                        result.value.reports.forEach { summary ->
                            /** 每行分别提供详情和删除操作。 */
                            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                            row.addView(button("${summary.type}  ${summary.createdAtMillis}") { showReport(summary.id) },
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            row.addView(button("删除") { deleteReport(summary.id) })
                            reportRows.addView(row)
                        }
                        nextAfterId = result.value.nextAfterId
                        more.isEnabled = nextAfterId != null
                    }
                    is AnrReportResult.Failure -> status.text = "读取报告列表失败：${result.error}"
                }
            }
        }
    }

    /** 按需读取并显示选中的 Matrix 原始结果。 */
    private fun showReport(id: String) {
        io.execute {
            /** 指定报告的读取结果。 */
            val result = reports.read(id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                detail.text = when (result) {
                    is AnrReportResult.Success -> with(result.value) {
                        "来源：$type\n时间：$createdAtMillis\n确认来源：$fromProcessErrorState\n" +
                            "消息：${message.orEmpty()}\n主线程 Java 栈：\n${javaStack.orEmpty()}\n" +
                            "Matrix trace：\n${trace.orEmpty()}\n锁等待：\n${mainThread.orEmpty()}\n" +
                            "${lockThread1.orEmpty()}\n${lockThread2.orEmpty()}"
                    }
                    is AnrReportResult.Failure -> "读取报告失败：${result.error}"
                }
            }
        }
    }

    /** 删除一条已完成报告并刷新摘要。 */
    private fun deleteReport(id: String) {
        io.execute {
            /** 删除操作的实际结果。 */
            val result = reports.delete(id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = when (result) {
                    is AnrReportResult.Success -> "报告已删除"
                    is AnrReportResult.Failure -> "删除报告失败：${result.error}"
                }
                detail.text = ""
                loadReports(reset = true)
            }
        }
    }

    /** 制造两线程循环持锁，供人工验证 Matrix 的死锁解析。 */
    private fun triggerDeadlock() {
        /** 主线程先持有的锁。 */
        val mainLock = Any()
        /** 工作线程先持有的锁。 */
        val workerLock = Any()
        /** 保证工作线程已经持有第二把锁。 */
        val workerLocked = CountDownLatch(1)
        synchronized(mainLock) {
            Thread({
                synchronized(workerLock) {
                    workerLocked.countDown()
                    synchronized(mainLock) { Log.i(TAG, "工作线程意外取得主线程锁") }
                }
            }, "anr-deadlock-demo").start()
            workerLocked.await()
            synchronized(workerLock) { Log.i(TAG, "主线程意外取得工作线程锁") }
        }
    }

    /** 页面退出时移除回调并结束本页面的报告读取线程。 */
    override fun onDestroy() {
        PerformanceSdk.current()?.anr?.setReportListener(null)
        io.shutdown()
        super.onDestroy()
    }

    /** 不记录报告正文的示例日志标签。 */
    private companion object {
        const val TAG = "AnrDemo"
    }
}
