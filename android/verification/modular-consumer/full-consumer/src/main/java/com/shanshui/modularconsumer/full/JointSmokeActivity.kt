package com.shanshui.modularconsumer.full

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.shanshui.performance.PerformanceSdk
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportType
import com.shanshui.performance.jank.JankDiagnostics
import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportRequestStatus
import com.shanshui.performance.jank.JankExportStatus
import com.shanshui.performance.jank.JankSamplingStatus
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** 在独立发布消费者中验证 ANR、Jank 与 Native Crash 联合运行。 */
class JointSmokeActivity : Activity() {
    /** 在主线程协调采集与关闭。 */
    private val handler = Handler(Looper.getMainLooper())
    /** 实际启动的全量 SDK。 */
    private var sdk: PerformanceSdk? = null
    /** 本次验证模式，决定正常关闭或触发测试信号。 */
    private var mode: String = "close"

    /** 启动联合验证，恢复模式仅检查 Application 已完成初始化。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "close"
        if (mode == "recover") {
            runCatching {
                check(hasRecoveredNativeSignal()) { "未恢复预期的 Native 信号报告" }
                Log.i(TAG, "FULL_JOINT_NATIVE_RECOVERED")
            }.onFailure { fail(it) }
            finish()
            return
        }
        runCatching {
            sdk = (application as FullConsumerApplication).startJointSmoke()
            check(sdk!!.anr?.isAvailable == true) { "ANR 不可用" }
            check(sdk!!.isJankAvailable) { "Jank 不可用" }
            waitForSampling(SystemClock.elapsedRealtime() + 15_000L)
        }.onFailure { fail(it) }
    }

    /** 等待真实异步采集源就绪，再触发 Matrix 手动 trace。 */
    private fun waitForSampling(deadline: Long) {
        if (JankDiagnostics.getAsyncSamplingStatus() == JankSamplingStatus.RUNNING) {
            captureAnr()
        } else if (SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed({ waitForSampling(deadline) }, 100L)
        } else {
            fail(IllegalStateException("Jank 异步采集未就绪"))
        }
    }

    /** 等报告实际保存后，再运行 Jank 导出验证。 */
    private fun captureAnr() {
        /** 当前全量 ANR 句柄。 */
        val anr = requireNotNull(sdk?.anr)
        anr.setReportListener { summary ->
            if (summary.type == AnrReportType.MANUAL_TRACE) {
                runCatching {
                    /** 只有正文可读才视为手动 trace 完成。 */
                    val report = (anr.reports.read(summary.id) as AnrReportResult.Success).value
                    check(!report.traceBase64.isNullOrEmpty()) { "ANR 报告正文为空" }
                    check(anr.reports.delete(summary.id) is AnrReportResult.Success)
                    Log.i(TAG, "FULL_JOINT_ANR_OK")
                    handler.post { exportJank() }
                }.onFailure { fail(it) }
            }
        }
        check(anr.printTrace()) { "无法请求手动 trace" }
    }

    /** 创建短暂主线程负载，并验证真实 Jank 文件导出及入队。 */
    private fun exportJank() {
        runCatching {
            /** 当前 SDK 与事件的起止时间。 */
            val active = requireNotNull(sdk)
            val started = SystemClock.elapsedRealtimeNanos()
            Thread.sleep(400)
            val ended = SystemClock.elapsedRealtimeNanos()
            val event = JankEvent(
                eventId = "joint-${UUID.randomUUID()}",
                occurredAt = System.currentTimeMillis(),
                sessionId = active.sessionId,
                scene = "joint-smoke",
                messageStartNs = started,
                messageEndNs = ended,
                thresholdNs = 100_000_000L,
                attemptedSampleCount = 0,
            )
            /** 导出回调在真正生成产物后继续执行关闭或崩溃验证。 */
            val accepted = active.exportAndEnqueue(event) { result ->
                runCatching {
                    check(result.status == JankExportStatus.SUCCESS || result.status == JankExportStatus.PARTIAL)
                    check(result.recordCount > 0 && result.artifactSizeBytes > 0 && result.queued)
                    Log.i(TAG, "FULL_JOINT_JANK_OK")
                    handler.post { finishJoint() }
                }.onFailure { fail(it) }
            }
            check(accepted == JankExportRequestStatus.ACCEPTED) { "Jank 导出未受理：$accepted" }
        }.onFailure { fail(it) }
    }

    /** 正常关闭组合实例，或向自身发送受控致命信号验证 Native Crash。 */
    private fun finishJoint() {
        if (mode == "crash") {
            Log.i(TAG, "FULL_JOINT_NATIVE_SIGNAL")
            Process.sendSignal(Process.myPid(), 11)
        } else {
            sdk?.close()
            Log.i(TAG, "FULL_JOINT_CLOSE_OK")
            finish()
        }
    }

    /** 检查本测试进程先前触发的 SIGSEGV 已恢复到 Crash 队列。 */
    private fun hasRecoveredNativeSignal(): Boolean {
        /** 已提交的 Crash 事件目录，不输出其中任何现场信息。 */
        val events = File(noBackupFilesDir, "performance-crash-reporter/events")
        return events.listFiles().orEmpty().any { file ->
            runCatching {
                val event = JSONObject(file.readText()).optJSONObject("event")
                val native = event?.optJSONObject("crash")?.optJSONObject("nativeCrash")
                native?.optJSONObject("signal")?.optInt("number") == 11
            }.getOrDefault(false)
        }
    }

    /** 只记录失败类型，避免把报告正文写入日志。 */
    private fun fail(error: Throwable) {
        Log.e(TAG, "FULL_JOINT_FAILED: ${error.javaClass.simpleName}: ${error.message}")
        handler.post { finish() }
    }

    /** 联合烟测日志标签。 */
    private companion object {
        const val TAG = "FullJointSmoke"
    }
}
