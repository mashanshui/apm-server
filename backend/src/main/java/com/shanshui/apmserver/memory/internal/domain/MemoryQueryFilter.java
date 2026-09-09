package com.shanshui.apmserver.memory.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** 已完成范围和筛选校验的内存查询条件。 */
public record MemoryQueryFilter(
        UUID appId,
        Instant from,
        Instant to,
        String appVersion,
        String osVersion,
        String deviceModel,
        String processName,
        String scene,
        Boolean foreground,
        int limit,
        long timeoutMs) {
}
