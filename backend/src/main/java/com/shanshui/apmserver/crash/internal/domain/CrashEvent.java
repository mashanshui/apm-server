package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** Crash 专属持久化领域对象。 */
public record CrashEvent(
        EventMetadata metadata,
        String crashKind,
        boolean fatal,
        String exceptionType,
        String fingerprint,
        String fingerprintVersion,
        String symbolicationStatus,
        CrashPayload payload) implements CrashStoredSignal {
}
