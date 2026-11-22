package com.shanshui.modularconsumer.anr

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.util.Log
import com.shanshui.performance.anr.AnrInitStatus
import com.shanshui.performance.anr.AnrHandle
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportType
import com.shanshui.performance.anr.internal.SignalAnrTracer
import com.shanshui.performance.identity.RuntimeIdentityProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 独立消费者的 JNI、混淆与跨进程读取验证入口。 */
class SmokeActivity : Activity() {
    /** 根据测试模式检查降级、触发手动 trace 或读取上一进程报告。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /** 不依赖全量 SDK 的 ANR 句柄。 */
        val anr = (application as AnrConsumerApplication).anr
        /** 仅由独立消费者测试启动时显式指定的验证模式。 */
        val mode = intent.getStringExtra("mode")
        if (anr.initResult.status == AnrInitStatus.UNSUPPORTED_API ||
            anr.initResult.status == AnrInitStatus.UNSUPPORTED_ABI
        ) {
            /** 采集不可用时，本地报告列表仍必须可访问。 */
            val page = (anr.reports.list() as AnrReportResult.Success).value
            if (mode == "verify-32bit-history") {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Process.is64Bit())
                check(anr.initResult.status == AnrInitStatus.UNSUPPORTED_ABI)
                /** 32 位进程不能提前加载只为受支持架构准备的采集库。 */
                val nativeLoaded = File("/proc/self/maps").useLines { lines ->
                    lines.any { it.contains("/libapm_anr.so") || it.contains("/libbytehook.so") }
                }
                check(!nativeLoaded) { "不支持架构时加载了 ANR 采集库" }
                /** 保留 64 位进程预先保存的原报告及其原始运行身份。 */
                val summary = page.reports.first { it.type == AnrReportType.MANUAL_TRACE }
                val report = (anr.reports.read(summary.id) as AnrReportResult.Success).value
                check(!report.traceBase64.isNullOrEmpty())
                check(report.identity.sessionId != RuntimeIdentityProvider.current(application).sessionId)
                check(anr.reports.delete(summary.id) is AnrReportResult.Success)
                Log.i(TAG, "ANR_CONSUMER_32BIT_HISTORY_OK")
            }
            Log.i(TAG, "ANR_CONSUMER_UNSUPPORTED_OK")
            finish()
            return
        }
        check(anr.isAvailable) { "ANR 采集器未启动" }
        /** 独立消费者也应复用满足主进程约定的公共身份。 */
        val identity = RuntimeIdentityProvider.current(application)
        check(identity.sessionId == identity.processId) { "主进程运行身份不一致" }
        /** 单独的模式用于验证报告尚未归档时进程被终止。 */
        if (mode == "prepare-kill") {
            prepareInterruptedCollection(anr)
            finish()
            return
        }
        if (mode == "queue-before-kill") {
            check(anr.printTrace()) { "无法请求待归档 trace" }
            finish()
            return
        }
        if (mode == "verify-kill") {
            verifyInterruptedCollection(anr)
            finish()
            return
        }
        if (mode == "read") {
            /** 上一个进程已保存的手动 trace。 */
            val page = (anr.reports.list() as AnrReportResult.Success).value
            val summary = page.reports.first { it.type == AnrReportType.MANUAL_TRACE }
            val report = (anr.reports.read(summary.id) as AnrReportResult.Success).value
            check(!report.traceBase64.isNullOrEmpty())
            check(report.identity.sessionId != RuntimeIdentityProvider.current(application).sessionId)
            check(anr.reports.delete(summary.id) is AnrReportResult.Success)
            Log.i(TAG, "ANR_CONSUMER_READ_OK")
            anr.close()
            finish()
            return
        }
        anr.setReportListener { summary ->
            if (summary.type == AnrReportType.MANUAL_TRACE) {
                /** 验证回调时报告已提交并可读取。 */
                val report = (anr.reports.read(summary.id) as AnrReportResult.Success).value
                check(!report.traceBase64.isNullOrEmpty())
                Log.i(TAG, "ANR_CONSUMER_CAPTURE_OK")
                runOnUiThread { finish() }
            }
        }
        check(anr.printTrace()) { "无法请求 Matrix 手动 trace" }
    }

    /** 先提交一份报告，再阻塞完成回调，供外部安排下一次采集和进程终止。 */
    private fun prepareInterruptedCollection(anr: AnrHandle) {
        /** 保留 SDK 原有文件回调，仅增加测试阶段日志。 */
        val tracerField = AnrHandle::class.java.getDeclaredField("tracer").apply { isAccessible = true }
        val tracer = tracerField.get(anr) as SignalAnrTracer
        val listenerField = SignalAnrTracer::class.java.getDeclaredField("sTraceFileListener").apply { isAccessible = true }
        val original = listenerField.get(null) as SignalAnrTracer.TraceFileListener
        tracer.setTraceFileListener(object : SignalAnrTracer.TraceFileListener {
            /** 按原样转发外部 SIGQUIT trace。 */
            override fun onAnrTraceFile(path: String) = original.onAnrTraceFile(path)

            /** 在 SDK 接受手动 trace 归档任务后记录测试阶段。 */
            override fun onPrintTraceFile(path: String) {
                original.onPrintTraceFile(path)
                Log.i(TAG, "ANR_KILL_TRACE_QUEUED")
            }
        })
        anr.setReportListener { summary ->
            if (summary.type == AnrReportType.MANUAL_TRACE) {
                getSharedPreferences("anr-kill-probe", MODE_PRIVATE).edit().putString("id", summary.id).commit()
                Log.i(TAG, "ANR_KILL_FIRST_SAVED")
                CountDownLatch(1).await(45, TimeUnit.SECONDS)
            }
        }
        check(anr.printTrace()) { "无法请求首次 trace" }
    }

    /** 新进程只应看到终止前已提交的报告，并保留其原进程身份。 */
    private fun verifyInterruptedCollection(anr: AnrHandle) {
        /** 上一进程已提交的报告 ID。 */
        val preferences = getSharedPreferences("anr-kill-probe", MODE_PRIVATE)
        val id = requireNotNull(preferences.getString("id", null))
        val page = (anr.reports.list() as AnrReportResult.Success).value
        check(page.reports.size == 1 && page.reports.single().id == id) { "出现缺失或未提交报告" }
        val report = (anr.reports.read(id) as AnrReportResult.Success).value
        check(report.identity.sessionId != RuntimeIdentityProvider.current(application).sessionId)
        check(anr.reports.delete(id) is AnrReportResult.Success)
        preferences.edit().clear().commit()
        anr.close()
        Log.i(TAG, "ANR_KILL_RECOVER_OK")
    }

    /** 不输出报告正文的烟测日志标签。 */
    private companion object {
        const val TAG = "AnrConsumerSmoke"
    }
}
