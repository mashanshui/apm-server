package com.shanshui.apmserver.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CrashPayload(String kind, Boolean fatal, List<ThrowableNode> throwableChain) {
}
