package com.shanshui.apmserver.crash.api;

import java.util.HashSet;
import java.util.List;

/** Native 事件同一状态版本的 Java 与 Native 辅助线程栈。 */
public record NativeCurrentAuxiliaryStacks(
        /** 每个 Java ID 对应独立栈和 mapping 来源。 */ List<JavaThread> javaThreads,
        /** 每个系统 TID 对应独立 Native 回溯和帧来源。 */ List<NativeThread> nativeThreads) {

    /** 验证线程列表和独立身份，避免同名线程被合并。 */
    public NativeCurrentAuxiliaryStacks {
        if (javaThreads == null || nativeThreads == null) throw new IllegalArgumentException("Native 辅助栈缺失");
        var javaIds = new HashSet<String>();
        for (var thread : javaThreads) {
            if (thread == null || !javaIds.add(thread.javaId())) throw new IllegalArgumentException("Java 线程身份重复");
        }
        var nativeIds = new HashSet<Integer>();
        for (var thread : nativeThreads) {
            if (thread == null || !nativeIds.add(thread.systemTid())) throw new IllegalArgumentException("Native 线程身份重复");
        }
        javaThreads = List.copyOf(javaThreads);
        nativeThreads = List.copyOf(nativeThreads);
    }

    /** Java 线程身份与独立处理栈。 */
    public record JavaThread(
            /** 原 Java 线程 ID。 */ String javaId,
            /** 栈及 mapping 处理结果。 */ NativeStack.JavaStack stack) {
        /** 不按线程名称推断身份。 */
        public JavaThread {
            if (javaId == null || !javaId.matches("[1-9][0-9]{0,18}") || stack == null) {
                throw new IllegalArgumentException("Java 线程栈身份无效");
            }
        }
    }

    /** Native 线程身份与原始物理回溯。 */
    public record NativeThread(
            /** 原系统线程编号。 */ Integer systemTid,
            /** 当前 Native 回溯。 */ NativeStack.Backtrace backtrace) {
        /** 不从线程名称或 Java ID 猜测系统线程编号。 */
        public NativeThread {
            if (systemTid == null || systemTid <= 0 || backtrace == null) throw new IllegalArgumentException("Native 线程栈身份无效");
        }
    }
}
