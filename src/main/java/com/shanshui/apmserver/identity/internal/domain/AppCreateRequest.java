package com.shanshui.apmserver.identity.internal.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AppCreateRequest(String name, String description, String packageName) {

    public AppCreateRequest(String packageName) {
        this(null, null, packageName);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("应用创建请求包含未知字段: " + field);
    }
}
