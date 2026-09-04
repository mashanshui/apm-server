package com.shanshui.apmserver.jank.internal.domain;

import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 卡顿个例及其分析证据。 */
public record JankEvent(
        EventMetadata metadata,
        String fingerprint,
        String fingerprintVersion,
        String symbolicationStatus,
        JankPayload payload,
        JankAnalysis analysis) implements JankStoredSignal {
}
