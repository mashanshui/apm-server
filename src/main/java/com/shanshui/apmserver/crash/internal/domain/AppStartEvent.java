package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 作为 Crash 统计分母的应用启动事件。 */
public record AppStartEvent(EventMetadata metadata) implements CrashStoredSignal {
}
