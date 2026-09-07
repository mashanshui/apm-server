package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shanshui.apmserver.telemetry.api.StackFrame;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ThrowableNode(String type, String message, List<StackFrame> frames) {
}
