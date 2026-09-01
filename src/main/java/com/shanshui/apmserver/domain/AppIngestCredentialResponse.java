package com.shanshui.apmserver.domain;

public record AppIngestCredentialResponse(
        java.util.UUID appId,
        String packageName,
        String appKey) {
}
