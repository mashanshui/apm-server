package com.shanshui.apmserver.memory.internal.domain;

/** 内存查询的字符串和筛选参数，避免复用 Jank 的维度语义。 */
public record MemoryQueryCommand(
        String appVersion,
        String osVersion,
        String deviceModel,
        String processName,
        String scene,
        Boolean foreground,
        Integer limit,
        Long timeoutMs) {

    public static MemoryQueryCommand empty() {
        return new MemoryQueryCommand(null, null, null, null, null, null, null, null);
    }
}
