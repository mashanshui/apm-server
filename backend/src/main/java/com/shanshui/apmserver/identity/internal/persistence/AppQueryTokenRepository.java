package com.shanshui.apmserver.identity.internal.persistence;

import com.shanshui.apmserver.identity.internal.domain.AppQueryToken;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Token 摘要查询和应用范围内管理持久化。 */
public interface AppQueryTokenRepository extends JpaRepository<AppQueryToken, UUID> {

    /** 摘要查找每次请求的认证记录。 */
    Optional<AppQueryToken> findByTokenDigest(byte[] tokenDigest);

    /** 分页列出单应用凭据元数据。 */
    Page<AppQueryToken> findByAppId(UUID appId, Pageable pageable);

    /** 查找目标应用中的指定凭据。 */
    Optional<AppQueryToken> findByIdAndAppId(UUID id, UUID appId);

    /** 统计尚未撤销且未到期的凭据数量。 */
    long countByAppIdAndRevokedAtIsNullAndExpiresAtAfter(UUID appId, Instant now);
}
