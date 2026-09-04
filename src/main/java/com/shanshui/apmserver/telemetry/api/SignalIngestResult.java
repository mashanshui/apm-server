package com.shanshui.apmserver.telemetry.api;

/** 单个领域事件完成校验、转换和幂等写入后的结果。 */
public record SignalIngestResult(
        AppendResult appendResult,
        EventMetadata metadata,
        String algorithmVersion) {
}
