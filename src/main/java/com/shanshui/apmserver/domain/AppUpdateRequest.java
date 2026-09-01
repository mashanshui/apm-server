package com.shanshui.apmserver.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AppUpdateRequest(String name, String description) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("应用更新请求包含不可修改或未知字段: " + field);
    }
}
