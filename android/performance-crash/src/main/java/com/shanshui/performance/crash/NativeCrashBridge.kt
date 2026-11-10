package com.shanshui.performance.crash

import android.util.Log
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Crash 自有 JNI 的内部探测入口，不会在探测时注册信号处理器。 */
internal object NativeCrashBridge {
    /** 仅记录安全阶段和固定原因，不记录现场或凭据。 */
    private const val TAG = "NativeCrashBridge"

    /** 加载结果只计算一次，失败时 JVM Crash 功能仍可工作。 */
    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("apm_crash")
            Log.i(TAG, "native crash library loaded")
            true
        }.getOrElse {
            Log.w(TAG, "native crash library unavailable")
            false
        }
    }

    /** 当前进程持有的核心文件；不把路径写入日志。 */
    @Volatile private var activeCore: File? = null

    /** 仅预启动的普通 Native 工作者可以调用 Java 快照；信号入口不调用。 */
    @Volatile private var captureCallback: ((Long, Int, Int, Long) -> Boolean)? = null

    /** 备用信号栈只能在安装它的线程中撤销。 */
    private var ownerLooper: Looper? = null

    /** 其他线程关闭时等待拥有线程释放备用栈。 */
    private var pendingRelease: CountDownLatch? = null

    /** 探测 JNI 记录格式版本；不支持或加载失败返回 null。 */
    fun protocolVersion(): Int? {
        if (!loaded) return null
        return runCatching { nativeProtocolVersion() }
            .onFailure { Log.w(TAG, "native crash protocol probe failed") }
            .getOrNull()
    }

    /** 普通上传线程检测致命信号状态，避免采集期间启动新的批次。 */
    fun captureInProgress(): Boolean = activeCore != null && runCatching { nativeCaptureInProgress() }.getOrDefault(true)

    /** 在当前线程准备核心文件并安装进程信号处理器；失败不会影响 JVM 监控。 */
    @Synchronized
    fun install(coreFile: File, dumperFile: File? = null, traceFile: File? = null, diagnosticsFile: File? = null,
                decisionFile: File? = null, callback: ((Long, Int, Int, Long) -> Boolean)? = null): Boolean {
        activeCore?.let { return it == coreFile }
        if ((dumperFile == null) != (traceFile == null) || (dumperFile == null) != (diagnosticsFile == null)) return false
        if ((decisionFile == null) != (callback == null) || (callback != null && dumperFile == null)) return false
        if (protocolVersion() != 1) return false
        val pending = pendingRelease
        if (pending != null) {
            if (Looper.myLooper() == ownerLooper && nativeReleaseAltStack() == 0) pending.countDown()
            val released = try { pending.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!released) {
                Log.w(TAG, "native crash initialization deferred: alt_stack_pending")
                return false
            }
            pendingRelease = null
            ownerLooper = null
        }
        val owner = Looper.myLooper()
        captureCallback = callback
        val result = runCatching { nativeInstall(coreFile.absolutePath, owner != null, dumperFile?.absolutePath,
            traceFile?.absolutePath, diagnosticsFile?.absolutePath, decisionFile?.absolutePath, callback != null) }.getOrDefault(1)
        if (result != 0) {
            captureCallback = null
            Log.w(TAG, "native crash initialization failed: install_error")
            return false
        }
        activeCore = coreFile
        ownerLooper = owner
        Log.i(TAG, "native crash signal handlers installed: alt_stack=${owner != null}")
        return true
    }

    /** 关闭本模块信号处理权，并在原线程释放可能存在的备用栈。 */
    @Synchronized
    fun uninstall() {
        val file = activeCore ?: return
        val result = runCatching { nativeUninstall() }.getOrDefault(3)
        if (result == 3) {
            Log.w(TAG, "native crash shutdown deferred: capture_in_progress")
            return
        }
        captureCallback = null
        activeCore = null
        if (file.isFile && file.length() == 0L) file.delete()
        if (result == 2 || result == 4) {
            val owner = ownerLooper
            val latch = CountDownLatch(1)
            pendingRelease = latch
            val posted = owner != null && Handler(owner).post {
                val released = runCatching { nativeReleaseAltStack() }.getOrDefault(1)
                if (released == 0) {
                    Log.i(TAG, "native crash alternate stack released")
                    latch.countDown()
                } else {
                    Log.w(TAG, "native crash alternate stack release failed")
                }
            }
            if (!posted) Log.w(TAG, "native crash alternate stack release pending")
        } else {
            ownerLooper = null
        }
        Log.i(TAG, "native crash signal handlers released: result=$result")
    }

    /** 由模块自有 JNI 库实现的协议版本探测。 */
    private external fun nativeProtocolVersion(): Int

    /** 返回原生核心是否正在采集，不注册处理器或触发采集。 */
    private external fun nativeCaptureInProgress(): Boolean

    /** 由预启动工作者调用；异常只导致 Java 分区缺失，不影响原生核心。 */
    private fun onNativeCaptureCompleted(deadlineNanos: Long, pid: Int, tid: Int, occurredAt: Long): Boolean =
        runCatching { captureCallback?.invoke(deadlineNanos, pid, tid, occurredAt) ?: false }.getOrDefault(false)

    /** 原生初始化不调用 Java 诊断采集器或网络。 */
    private external fun nativeInstall(corePath: String, useAltStack: Boolean, dumperPath: String?, tracePath: String?,
                                       diagnosticsPath: String?, decisionPath: String?, useCallback: Boolean): Int

    /** 返回备用栈是否需要原线程清理。 */
    private external fun nativeUninstall(): Int

    /** 在原拥有线程恢复备用栈。 */
    private external fun nativeReleaseAltStack(): Int
}
