package com.shanshui.apmserver.jank.api;

import java.time.Instant;

public record JankTrendPoint(Instant bucketStart, Instant bucketEnd, JankStats stats) {
}
