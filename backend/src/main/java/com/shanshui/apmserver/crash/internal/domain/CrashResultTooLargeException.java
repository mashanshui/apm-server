package com.shanshui.apmserver.crash.internal.domain;

/** 完整结果不能装入有限发布载荷，必须保留原始链并等待不同 mapping。 */
public class CrashResultTooLargeException extends RuntimeException {
    /** 安全错误不包含原文。 */
    public CrashResultTooLargeException() { super("完整还原结果超过发布载荷上限"); }
}
