package com.shanshui.apmserver.platform.api;

/** 单次分析查询传递给 ClickHouse 和 HTTP 客户端的资源预算。 */
public record QueryBudget(long timeoutMs, long maxRowsToRead, long maxBytesToRead,
                          long maxMemoryUsage, int maxResponseBytes) {
}
