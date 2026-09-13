package com.shanshui.apmserver.memory.api;

import java.time.Instant;
import java.util.List;

public record MemoryLeakIssueItem(String signature, String leakClass, String leakReason,
                                  String gcRoot, List<MemoryLeakPathNode> path,
                                  Instant lastOccurredAt, long occurrences,
                                  double occurrenceRatio, long affectedDevices,
                                  double deviceRatio, List<String> versions) {
}
