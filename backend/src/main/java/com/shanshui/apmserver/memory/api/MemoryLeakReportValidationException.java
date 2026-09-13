package com.shanshui.apmserver.memory.api;

/** 内存泄漏报告的公开协议校验异常，由平台层统一映射为 400。 */
public class MemoryLeakReportValidationException extends RuntimeException {
    public MemoryLeakReportValidationException(String message) {
        super(message);
    }

    public MemoryLeakReportValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
