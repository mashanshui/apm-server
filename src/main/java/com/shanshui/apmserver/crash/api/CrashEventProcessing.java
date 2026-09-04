package com.shanshui.apmserver.crash.api;

import com.shanshui.apmserver.telemetry.api.SignalIngestResult;

import java.time.Instant;

/** Crash 模块对批次编排暴露的最小事件处理能力。 */
public interface CrashEventProcessing {

    SignalIngestResult ingest(java.util.UUID appId, CrashIngestCommand command, Instant receivedAt);
}
