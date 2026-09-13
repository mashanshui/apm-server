package com.shanshui.apmserver.memory.internal.domain;

import java.time.Instant;
import java.util.UUID;

public record MemoryLeakQueryFilter(UUID appId, Instant from, Instant to, String appVersion,
                                    String deviceModel, String processName, String scene,
                                    String manufacturer, Integer sdkInt, String dumpReason,
                                    String anonymousDeviceId, String signature, String keyword) {
}
