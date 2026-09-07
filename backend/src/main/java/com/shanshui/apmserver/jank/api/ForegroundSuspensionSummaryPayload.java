package com.shanshui.apmserver.jank.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 一段前台区间内完整累计的长帧挂起汇总。时间字段使用毫秒。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ForegroundSuspensionSummaryPayload(
        @JsonAlias({"suspensionAlgorithmVersion"}) String algorithmVersion,
        @JsonAlias({"activeDurationMs"}) Long foregroundDurationMs,
        @JsonAlias({"suspensionDurationMs", "totalSuspensionDurationMs"}) Long suspensionDurationMs,
        @JsonAlias({"count"}) Integer suspensionCount,
        @JsonAlias({"suspensionThresholdMs"}) Long thresholdMs) {
}
