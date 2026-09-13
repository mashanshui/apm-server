package com.shanshui.apmserver.memory.api;

import java.time.Instant;

public record MemoryLeakTrendPoint(Instant bucketStart, long occurrenceCount,
                                   long affectedDeviceCount) {
}
