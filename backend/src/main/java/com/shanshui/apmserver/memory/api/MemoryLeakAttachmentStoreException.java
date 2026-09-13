package com.shanshui.apmserver.memory.api;

/** 可选 HPROF 附件保存失败，调用方可以按 Retry-After 重试。 */
public class MemoryLeakAttachmentStoreException extends RuntimeException {
    public MemoryLeakAttachmentStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
