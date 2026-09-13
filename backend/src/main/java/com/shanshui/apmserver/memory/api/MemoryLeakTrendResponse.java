package com.shanshui.apmserver.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemoryLeakTrendResponse(UUID appId, Instant from, Instant to, String interval,
                                      List<MemoryLeakTrendPoint> points, String status,
                                      String dataSource) {
}
