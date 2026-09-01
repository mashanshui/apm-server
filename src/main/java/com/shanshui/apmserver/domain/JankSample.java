package com.shanshui.apmserver.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 一次相对消息开始时间的单调时钟采样。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JankSample(
        @JsonAlias({"sampleOffsetNs", "offsetNanos"}) Long offsetNs,
        String stackId) {
}
