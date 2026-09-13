package com.shanshui.apmserver.memory.api;

import java.util.UUID;

public record MemoryLeakReportResponse(UUID eventId, String status, int issueCount,
                                       String attachmentStatus) {
}
