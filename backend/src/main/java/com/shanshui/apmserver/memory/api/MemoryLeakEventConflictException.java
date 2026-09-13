package com.shanshui.apmserver.memory.api;

/** 同一应用事件 ID 的报告内容或附件摘要发生变化。 */
public class MemoryLeakEventConflictException extends RuntimeException {
    public MemoryLeakEventConflictException() {
        super("同一 eventId 的报告内容或附件摘要不一致");
    }
}
