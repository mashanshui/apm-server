package com.shanshui.apmserver.crash.api;

import java.util.List;

/** 与 Native 主栈同一状态版本的 Java 和 Native 辅助栈。 */
public record NativeAuxiliaryStacks(
        /** 按 Java ID 区分的原始 Java 线程栈。 */ List<CrashThreadStack> javaThreads,
        /** 按系统 TID 区分的原始 Native 线程栈。 */ List<NativeThreadStack> nativeThreads) {

    /** 一个 Native 线程的当前回溯。 */
    public record NativeThreadStack(
            /** 系统线程编号。 */ Integer systemTid,
            /** 原始回溯及其采集状态。 */ NativeCrash.Backtrace backtrace) { }
}
