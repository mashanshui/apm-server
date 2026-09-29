package com.shanshui.apmserver.memory.api;

import java.util.Map;
import java.util.UUID;

/** 网页与 Agent 共用的 SDK 内存异常问题和趋势查询契约。 */
public interface MemoryLeakQueries {
    MemoryLeakIssuesResponse issues(UUID appId, Map<String, String> query);
    MemoryLeakTrendResponse trend(UUID appId, Map<String, String> query);
}
