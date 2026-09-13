package com.shanshui.apmserver.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemoryLeakIssuesResponse(UUID appId, Instant from, Instant to, long total,
                                       long totalOccurrences, long totalAffectedDevices,
                                       int page, int pageSize, List<MemoryLeakIssueItem> items,
                                       String status, String dataSource) {
}
