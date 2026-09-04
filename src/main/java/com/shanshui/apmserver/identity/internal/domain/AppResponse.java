package com.shanshui.apmserver.identity.internal.domain;

import java.time.Instant;

public record AppResponse(
        java.util.UUID appId,
        String name,
        String description,
        String packageName,
        AppRole role,
        Instant createdAt,
        Instant updatedAt) {

    public static AppResponse from(ApmApp app, String packageName, AppRole role) {
        return new AppResponse(app.getAppId(), app.getName(), app.getDescription(), packageName, role,
                app.getCreatedAt(), app.getUpdatedAt());
    }
}
