package com.shanshui.apmserver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "apm_app")
public class ApmApp {

    @Id
    @Column(name = "app_id", nullable = false)
    private java.util.UUID appId;

    @Column(name = "package_name", nullable = false, unique = true, length = 255)
    private String packageName;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApmApp() {
    }

    public ApmApp(java.util.UUID appId, String packageName, String name, String description, AppUser createdBy,
                      Instant createdAt, Instant updatedAt) {
        this.appId = appId;
        this.packageName = packageName;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public java.util.UUID getAppId() {
        return appId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String name, String description, Instant updatedAt) {
        this.name = name;
        this.description = description;
        this.updatedAt = updatedAt;
    }
}
