package com.shanshui.performance.anr

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File

/** 检查安装信号处理器之前必须满足的平台条件。 */
internal object AnrPlatform {
    /** 平台检查阶段日志标签。 */
    private const val TAG = "PerformanceAnr"

    /** 首期支持的 Android API 下限。 */
    internal const val MIN_API = 23

    /** 首期支持的目标 ABI。 */
    internal const val SUPPORTED_ABI = "arm64-v8a"

    /** 根据可控输入检查采集前提；null 表示条件满足，尚未代表采集已启动。 */
    fun check(
        config: AnrConfig,
        apiLevel: Int,
        isMainProcess: Boolean,
        is64BitProcess: Boolean,
        primary64BitAbi: String?,
    ): AnrInitResult? {
        if (!config.enabled) return AnrInitResult(AnrInitStatus.DISABLED)
        if (apiLevel < MIN_API) return AnrInitResult(AnrInitStatus.UNSUPPORTED_API, "api_below_23")
        if (!isMainProcess) return AnrInitResult(AnrInitStatus.NOT_MAIN_PROCESS, "not_main_process")
        if (!is64BitProcess || primary64BitAbi != SUPPORTED_ABI) {
            return AnrInitResult(AnrInitStatus.UNSUPPORTED_ABI, "unsupported_process_abi")
        }
        return null
    }

    /** 从 Android 进程获取平台条件，失败时返回安全原因而不触发 JNI 加载。 */
    fun check(application: Application, config: AnrConfig): AnrInitResult? {
        if (!config.enabled) return AnrInitResult(AnrInitStatus.DISABLED)
        if (Build.VERSION.SDK_INT < MIN_API) {
            return AnrInitResult(AnrInitStatus.UNSUPPORTED_API, "api_below_23")
        }
        return try {
            /** Android P 起使用公开进程名接口，旧版读取本进程的命令行进程名。 */
            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").inputStream().bufferedReader().use { reader ->
                    reader.readText().substringBefore('\u0000').trim()
                }
            }
            /** 首个 64 位 ABI 用于过滤非 arm64 设备；32 位进程由 is64Bit 单独拒绝。 */
            val primary64BitAbi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            /** 各平台字段均在调用受保护的 API 后取得，未知进程名不能按主进程猜测。 */
            val result = check(
                config = config,
                apiLevel = Build.VERSION.SDK_INT,
                isMainProcess = processName == application.packageName,
                is64BitProcess = Process.is64Bit(),
                primary64BitAbi = primary64BitAbi,
            )
            Log.i(TAG, "ANR 平台检查完成：${result?.status ?: "SUPPORTED"}")
            result
        } catch (exception: Exception) {
            Log.w(TAG, "ANR 平台检查失败：${exception.javaClass.simpleName}")
            AnrInitResult(AnrInitStatus.FAILED, "platform_check_failed")
        } catch (error: LinkageError) {
            Log.w(TAG, "ANR 平台检查失败：${error.javaClass.simpleName}")
            AnrInitResult(AnrInitStatus.FAILED, "platform_check_failed")
        }
    }
}
