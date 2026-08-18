package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashTrendPoint(Instant bucketStart, Instant bucketEnd, CrashStats stats) {
}
