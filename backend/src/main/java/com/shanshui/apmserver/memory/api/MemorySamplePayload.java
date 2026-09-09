package com.shanshui.apmserver.memory.api;

/**
 * 一次进程级内存采样。数值统一使用字节，null 表示客户端本次没有采到该指标；
 * scene 表示采样时当前应用的 Activity 名称，未知时可以为空。
 */
public record MemorySamplePayload(
        Long pssBytes,
        Long vssBytes,
        Long javaHeapUsedBytes,
        String processName,
        Boolean foreground,
        String scene) {
}
