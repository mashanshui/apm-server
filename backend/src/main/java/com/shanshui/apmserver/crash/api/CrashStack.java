package com.shanshui.apmserver.crash.api;

/** 当前单份堆栈：JVM 原始链、Native 原始材料或完整还原文本。 */
public record CrashStack(
        /** raw_chain、raw_native 或 retrace_text。 */ String format,
        /** 原始 JVM/Native 对象或还原文本。 */ Object content) {
    /** 仅创建支持的完整载荷，不接受旧字段、未知格式或空文本。 */
    public CrashStack {
        if (!("raw_chain".equals(format) && content instanceof CrashPayload)
                && !("raw_native".equals(format) && content instanceof NativeCrash)
                && !("retrace_text".equals(format) && content instanceof String text && !text.isBlank())) {
            throw new IllegalArgumentException("Crash 单份堆栈格式不合法");
        }
    }
}
