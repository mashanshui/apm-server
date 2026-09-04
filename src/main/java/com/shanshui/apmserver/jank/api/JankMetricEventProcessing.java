package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.telemetry.api.SignalIngestResult;

import java.time.Instant;

/** Jank 模块对公共批次编排暴露的指标事件处理能力。 */
public interface JankMetricEventProcessing {

    SignalIngestResult ingest(java.util.UUID appId, JankMetricIngestCommand command, Instant receivedAt);
}
