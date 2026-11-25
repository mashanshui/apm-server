package com.shanshui.apmserver.anr.api;

import com.shanshui.apmserver.telemetry.api.SignalIngestResult;

import java.time.Instant;
import java.util.UUID;

/** 批次接收使用的 ANR 处理边界。 */
public interface AnrEventProcessing {
    /** 校验、脱敏并持久化一个 ANR。 */
    SignalIngestResult ingest(UUID appId, AnrIngestCommand command, Instant receivedAt);
}
