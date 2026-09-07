package com.shanshui.apmserver.telemetry.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record StackFrame(
        String className,
        String methodName,
        String fileName,
        Integer lineNumber,
        @JsonProperty("applicationFrame") Boolean applicationFrame) {
}
