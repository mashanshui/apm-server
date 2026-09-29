package com.shanshui.apmserver.identity.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** 管理界面可见的 Token 元数据，不含摘要或完整秘密。 */
public record QueryTokenMetadata(UUID id, String name, String displayPrefix, String scope,
                                 UUID createdBy, Instant createdAt, Instant expiresAt,
                                 Instant revokedAt, String status) {

    /** 根据当前时间派生状态，不保存额外状态列。 */
    public static QueryTokenMetadata from(AppQueryToken token, Instant now) {
        String state = token.getRevokedAt() != null ? "REVOKED"
                : !token.getExpiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
        return new QueryTokenMetadata(token.getId(), token.getName(), token.getDisplayPrefix(),
                token.getScope(), token.getCreatedBy(), token.getCreatedAt(), token.getExpiresAt(),
                token.getRevokedAt(), state);
    }
}
