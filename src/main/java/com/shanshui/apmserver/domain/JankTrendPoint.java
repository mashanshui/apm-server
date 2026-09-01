package com.shanshui.apmserver.domain;

import java.time.Instant;

public record JankTrendPoint(Instant bucketStart, Instant bucketEnd, JankStats stats) {
}
