package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.identity.api.InvalidQueryTokenException;
import com.shanshui.apmserver.identity.api.QueryAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.QueryTokenAuthentication;
import com.shanshui.apmserver.identity.internal.domain.AppQueryToken;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 从 PostgreSQL 逐次读取 Token 状态，撤销提交后新请求立即失效。 */
@Service
public class QueryTokenAuthenticator implements QueryTokenAuthentication {

    private final AppQueryTokenRepository tokens;
    private final QueryTokenMaterial material;
    private final Clock clock;

    /** 生产入口使用 UTC 时钟。 */
    @Autowired
    public QueryTokenAuthenticator(AppQueryTokenRepository tokens, QueryTokenMaterial material) {
        this(tokens, material, Clock.systemUTC());
    }

    /** 测试入口允许精确验证到期瞬间。 */
    QueryTokenAuthenticator(AppQueryTokenRepository tokens, QueryTokenMaterial material, Clock clock) {
        this.tokens = tokens;
        this.material = material;
        this.clock = clock;
    }

    /** 只有摘要命中、未撤销且当前时间严格早于到期时才授权。 */
    @Override
    @Transactional(readOnly = true)
    public AuthenticatedQueryToken authenticate(String token) {
        if (!material.isWellFormed(token)) throw new InvalidQueryTokenException();
        try {
            AppQueryToken record = tokens.findByTokenDigest(material.digest(token))
                    .orElseThrow(InvalidQueryTokenException::new);
            if (record.getRevokedAt() != null || !record.getExpiresAt().isAfter(Instant.now(clock))) {
                throw new InvalidQueryTokenException();
            }
            return new AuthenticatedQueryToken(record.getId(), record.getAppId(), record.getScope());
        } catch (InvalidQueryTokenException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new QueryAuthenticationUnavailableException(ex);
        }
    }
}
