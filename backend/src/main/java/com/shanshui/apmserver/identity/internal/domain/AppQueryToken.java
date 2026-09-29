package com.shanshui.apmserver.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** 应用只读查询凭据，仅保存不可逆摘要及管理元数据。 */
@Entity
@Table(name = "app_query_token")
public class AppQueryToken {

    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_digest", nullable = false, length = 32)
    private byte[] tokenDigest;

    @Column(name = "display_prefix", nullable = false, length = 24)
    private String displayPrefix;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** JPA 构造器。 */
    protected AppQueryToken() {
    }

    /** 创建尚未撤销的凭据记录，所有属性由服务端确定。 */
    public AppQueryToken(UUID id, UUID appId, String name, byte[] tokenDigest, String displayPrefix,
                         UUID createdBy, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.appId = appId;
        this.name = name;
        this.tokenDigest = Arrays.copyOf(tokenDigest, tokenDigest.length);
        this.displayPrefix = displayPrefix;
        this.scope = "apm:read";
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** 返回 Token 记录 ID。 */
    public UUID getId() { return id; }

    /** 返回所属应用 ID。 */
    public UUID getAppId() { return appId; }

    /** 返回管理名称。 */
    public String getName() { return name; }

    /** 防御性返回 SHA-256 摘要。 */
    public byte[] getTokenDigest() { return Arrays.copyOf(tokenDigest, tokenDigest.length); }

    /** 返回用于识别凭据的非完整前缀。 */
    public String getDisplayPrefix() { return displayPrefix; }

    /** 返回固定只读权限。 */
    public String getScope() { return scope; }

    /** 返回创建者审计 ID。 */
    public UUID getCreatedBy() { return createdBy; }

    /** 返回创建时间。 */
    public Instant getCreatedAt() { return createdAt; }

    /** 返回到期时间。 */
    public Instant getExpiresAt() { return expiresAt; }

    /** 返回撤销时间；未撤销时为 null。 */
    public Instant getRevokedAt() { return revokedAt; }

    /** 幂等记录首次撤销时间。 */
    public void revoke(Instant at) {
        if (revokedAt == null) revokedAt = at;
    }
}
