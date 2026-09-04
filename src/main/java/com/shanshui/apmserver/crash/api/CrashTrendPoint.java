package com.shanshui.apmserver.crash.api;

import java.time.Instant;

public record CrashTrendPoint(Instant bucketStart, Instant bucketEnd, CrashStats stats) {
}
