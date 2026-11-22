package com.shanshui.performance.anr.internal;

import android.util.Log;

/** 只记录采集阶段，不输出线程栈、trace 或异常正文。 */
final class MatrixLog {
    /** 日志工具不创建实例。 */
    private MatrixLog() {}

    /** 记录 Matrix 采集进度。 */
    static void i(String tag, String message, Object... args) {
        Log.i(tag, "Matrix ANR 采集阶段已执行");
    }

    /** 记录 Matrix 采集失败。 */
    static void e(String tag, String message, Object... args) {
        Log.w(tag, "Matrix ANR 采集阶段失败");
    }
}
