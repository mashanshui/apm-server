package com.shanshui.apmserver.jank.internal.domain;

import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 场景帧率汇总事件。 */
public record FrameSceneSummary(EventMetadata metadata, FrameSceneSummaryPayload payload) implements JankStoredSignal {
}
