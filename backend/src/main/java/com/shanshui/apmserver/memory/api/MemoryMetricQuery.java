package com.shanshui.apmserver.memory.api;

/** 内存指标独有的进程、场景和前后台筛选。 */
public record MemoryMetricQuery(String appVersion, String osVersion, String deviceModel,
                                String processName, String scene, Boolean foreground,
                                Integer limit, Long timeoutMs) {
}
