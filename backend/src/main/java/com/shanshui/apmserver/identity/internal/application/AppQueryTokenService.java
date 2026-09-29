package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.api.InvalidAppInputException;
import com.shanshui.apmserver.identity.api.QueryTokenLimitException;
import com.shanshui.apmserver.identity.api.QueryTokenNotFoundException;
import com.shanshui.apmserver.identity.internal.config.QueryTokenProperties;
import com.shanshui.apmserver.identity.internal.domain.AppQueryToken;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenCreated;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenMetadata;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenPage;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Token 生命周期由 identity 统一负责，创建者角色仅在管理操作时校验。 */
@Service
public class AppQueryTokenService {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(30, 90, 365);
    private final AppMembershipService membership;
    private final ApmAppRepository apps;
    private final AppQueryTokenRepository tokens;
    private final QueryTokenMaterial material;
    private final QueryTokenProperties properties;
    private final Clock clock;

    /** 生产构造器以 UTC 时钟处理到期边界。 */
    @Autowired
    public AppQueryTokenService(AppMembershipService membership, ApmAppRepository apps,
                                AppQueryTokenRepository tokens, QueryTokenMaterial material,
                                QueryTokenProperties properties) {
        this(membership, apps, tokens, material, properties, Clock.systemUTC());
    }

    /** 测试可注入固定时钟。 */
    AppQueryTokenService(AppMembershipService membership, ApmAppRepository apps,
                         AppQueryTokenRepository tokens, QueryTokenMaterial material,
                         QueryTokenProperties properties, Clock clock) {
        this.membership = membership;
        this.apps = apps;
        this.tokens = tokens;
        this.material = material;
        this.properties = properties;
        this.clock = clock;
    }

    /** 在应用行锁下计数并创建，防止并发绕过单应用上限。 */
    @Transactional
    public QueryTokenCreated create(UUID userId, UUID appId, String name, Integer expiresInDays) {
        membership.requireEdit(appId, userId);
        String normalizedName = normalizeName(name);
        int days = expiresInDays == null ? 90 : expiresInDays;
        if (!ALLOWED_DAYS.contains(days)) {
            throw new InvalidAppInputException("expiresInDays", "INVALID_QUERY_TOKEN_EXPIRY", "有效期只支持 30、90 或 365 天");
        }
        apps.lockForQueryTokenCreation(appId).orElseThrow(AppNotFoundException::new);
        Instant now = Instant.now(clock);
        if (tokens.countByAppIdAndRevokedAtIsNullAndExpiresAtAfter(appId, now) >= properties.getMaxActive()) {
            throw new QueryTokenLimitException();
        }
        String secret = material.generate();
        AppQueryToken saved = tokens.save(new AppQueryToken(UUID.randomUUID(), appId, normalizedName,
                material.digest(secret), material.displayPrefix(secret), userId, now, now.plusSeconds(days * 86400L)));
        return new QueryTokenCreated(QueryTokenMetadata.from(saved, now), secret);
    }

    /** 只返回管理元数据，按创建时间及 ID 倒序。 */
    @Transactional(readOnly = true)
    public QueryTokenPage list(UUID userId, UUID appId, int page, int size) {
        membership.requireEdit(appId, userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidAppInputException("page", "INVALID_QUERY_TOKEN_PAGE", "分页参数超出允许范围");
        }
        Page<AppQueryToken> found = tokens.findByAppId(appId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Instant now = Instant.now(clock);
        return new QueryTokenPage(found.getContent().stream().map(token -> QueryTokenMetadata.from(token, now)).toList(),
                page, size, found.getTotalElements(), found.getTotalPages());
    }

    /** 同应用内重复撤销保持幂等；跨应用 ID 按不存在处理。 */
    @Transactional
    public void revoke(UUID userId, UUID appId, UUID tokenId) {
        membership.requireEdit(appId, userId);
        AppQueryToken token = tokens.findByIdAndAppId(tokenId, appId).orElseThrow(QueryTokenNotFoundException::new);
        token.revoke(Instant.now(clock));
    }

    /** 名称清理遵循数据库的一百字符边界。 */
    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new InvalidAppInputException("name", "INVALID_QUERY_TOKEN_NAME", "名称长度必须为 1 到 100 字符");
        }
        return normalized;
    }
}
