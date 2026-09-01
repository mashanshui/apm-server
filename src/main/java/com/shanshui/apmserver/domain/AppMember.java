package com.shanshui.apmserver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_member")
public class AppMember {

    @EmbeddedId
    private AppMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppMember() {
    }

    public AppMember(AppMemberId id, AppRole role, Instant createdAt) {
        this.id = id;
        this.role = role;
        this.createdAt = createdAt;
    }

    public AppMemberId getId() {
        return id;
    }

    public AppRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
