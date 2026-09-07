package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.telemetry.api.StackFrame;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Android 主线程卡顿个例及其可独立解码的采样证据。时间字段统一使用纳秒。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JankPayload(
        String scene,
        @JsonAlias({"jankAlgorithmVersion", "samplingAlgorithmVersion"}) String algorithmVersion,
        @JsonAlias({"durationNs", "exactMessageDurationNs"}) Long messageDurationNs,
        @JsonAlias({"jankThresholdNs"}) Long thresholdNs,
        @JsonAlias({"sampleIntervalNs"}) Long samplingIntervalNs,
        List<JankSample> samples,
        @JsonAlias({"stacks"}) Map<String, List<StackFrame>> stackDictionary,
        @JsonAlias({"expectedSamples"}) Integer expectedSampleCount,
        Integer parsedSampleCount,
        Integer missingSampleCount) {
}
