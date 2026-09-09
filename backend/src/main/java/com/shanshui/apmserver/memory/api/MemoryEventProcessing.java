package com.shanshui.apmserver.memory.api;

import com.shanshui.apmserver.telemetry.api.SignalIngestResult;

import java.time.Instant;
import java.util.UUID;

/** 内存领域对批次编排暴露的事件处理能力。 */
public interface MemoryEventProcessing {

    SignalIngestResult ingest(UUID appId, MemoryIngestCommand command, Instant receivedAt);
}
