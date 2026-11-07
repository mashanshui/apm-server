package com.shanshui.apmserver.crash.api;

/** 当前单份堆栈：原始异常链对象或完整还原文本，禁止同时持有两份。 */
public record CrashStack(
        /** raw_chain 或 retrace_text。 */ String format,
        /** raw_chain 使用 CrashPayload，retrace_text 使用 String。 */ Object content) {
    /** 仅创建支持的完整载荷，不接受旧字段、未知格式或空文本。 */
    public CrashStack {
        if (!("raw_chain".equals(format) && content instanceof CrashPayload)
                && !("retrace_text".equals(format) && content instanceof String text && !text.isBlank())) {
            throw new IllegalArgumentException("Crash 单份堆栈格式不合法");
        }
    }
}
