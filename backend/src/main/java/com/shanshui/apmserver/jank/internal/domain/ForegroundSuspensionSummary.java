package com.shanshui.apmserver.jank.internal.domain;

import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 前台挂起时长汇总事件。 */
public record ForegroundSuspensionSummary(
        EventMetadata metadata,
        ForegroundSuspensionSummaryPayload payload) implements JankStoredSignal {
}
