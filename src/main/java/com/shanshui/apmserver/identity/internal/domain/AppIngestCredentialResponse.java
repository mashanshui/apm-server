package com.shanshui.apmserver.identity.internal.domain;

public record AppIngestCredentialResponse(
        java.util.UUID appId,
        String packageName,
        String appKey) {
}
