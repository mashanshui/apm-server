package com.shanshui.performance.anr

import android.util.Log

/** 按需加载 ANR 原生库，避免在不支持的平台触发类加载失败。 */
internal object AnrNativeLibrary {
    /** 原生库加载阶段的日志标签。 */
    private const val TAG = "PerformanceAnr"

    /** 尝试加载采集库，并只记录安全阶段与失败类型。 */
    fun load(): Boolean = try {
        System.loadLibrary("apm_anr")
        Log.i(TAG, "ANR 原生库加载成功")
        true
    } catch (exception: LinkageError) {
        Log.w(TAG, "ANR 原生库加载失败：${exception.javaClass.simpleName}")
        false
    } catch (exception: SecurityException) {
        Log.w(TAG, "ANR 原生库加载失败：${exception.javaClass.simpleName}")
        false
    }
}
