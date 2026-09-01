package com.shanshui.apmserver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class AppMemberId implements Serializable {

    @Column(name = "app_id", nullable = false)
    private java.util.UUID appId;

    @Column(name = "user_id")
    private UUID userId;

    protected AppMemberId() {
    }

    public AppMemberId(java.util.UUID appId, UUID userId) {
        this.appId = appId;
        this.userId = userId;
    }

    public UUID getAppId() {
        return appId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppMemberId that)) return false;
        return Objects.equals(appId, that.appId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appId, userId);
    }
}
