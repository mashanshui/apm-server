package com.shanshui.apmserver.jank.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** 一段有真实 UI 刷新帧的场景 FPS 汇总。时间字段使用毫秒，FPS 使用帧/秒。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FrameSceneSummaryPayload(
        String scene,
        @JsonAlias({"fpsAlgorithmVersion"}) String algorithmVersion,
        @JsonAlias({"activeDurationMs", "durationMs"}) Long activeDurationMs,
        @JsonAlias({"uiFrames", "frameCount"}) Integer uiRefreshFrameCount,
        Double refreshRateHz,
        @JsonAlias({"fps60", "normalizedFps"}) Double normalizedFps60,
        Map<String, Integer> frameDurationHistogram) {
}
