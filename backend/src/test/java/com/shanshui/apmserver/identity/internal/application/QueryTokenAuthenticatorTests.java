package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.InvalidQueryTokenException;
import com.shanshui.apmserver.identity.api.QueryAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.internal.domain.AppQueryToken;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 请求级鉴权区分凭据状态和存储不可用。 */
class QueryTokenAuthenticatorTests {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private final AppQueryTokenRepository tokens = mock(AppQueryTokenRepository.class);
    private final QueryTokenMaterial material = new QueryTokenMaterial();
    private final QueryTokenAuthenticator authenticator = new QueryTokenAuthenticator(tokens, material,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final String secret = material.generate();
    private final UUID appId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    /** 创建者权限无须再次查询，只以 Token 绑定应用授权。 */
    @Test
    void authorizesValidAppCredential() {
        AppQueryToken record = record(NOW.plusSeconds(1));
        when(tokens.findByTokenDigest(any(byte[].class))).thenReturn(Optional.of(record));
        assertEquals(appId, authenticator.authenticate(secret).appId());
        assertEquals(tokenId, authenticator.authenticate(secret).tokenId());
        verify(tokens, org.mockito.Mockito.times(2)).findByTokenDigest(any(byte[].class));
    }

    /** 不区分缺失、未知、已撤销和精确到期时刻。 */
    @Test
    void rejectsInvalidRevokedAndExpired() {
        assertThrows(InvalidQueryTokenException.class, () -> authenticator.authenticate(null));
        assertThrows(InvalidQueryTokenException.class, () -> authenticator.authenticate("apm_ak_wrong"));
        when(tokens.findByTokenDigest(any(byte[].class))).thenReturn(Optional.empty());
        assertThrows(InvalidQueryTokenException.class, () -> authenticator.authenticate(secret));
        when(tokens.findByTokenDigest(any(byte[].class))).thenReturn(Optional.of(record(NOW)));
        assertThrows(InvalidQueryTokenException.class, () -> authenticator.authenticate(secret));
        AppQueryToken revoked = record(NOW.plusSeconds(10));
        revoked.revoke(NOW);
        when(tokens.findByTokenDigest(any(byte[].class))).thenReturn(Optional.of(revoked));
        assertThrows(InvalidQueryTokenException.class, () -> authenticator.authenticate(secret));
    }

    /** 数据库异常不能被包装为无效凭据。 */
    @Test
    void distinguishesStorageFailure() {
        when(tokens.findByTokenDigest(any(byte[].class)))
                .thenThrow(new DataAccessResourceFailureException("test storage unavailable"));
        QueryAuthenticationUnavailableException error = assertThrows(QueryAuthenticationUnavailableException.class,
                () -> authenticator.authenticate(secret));
        assertEquals("查询认证暂时不可用", error.getMessage());
    }

    /** 构造单应用、单创建者的不可恢复摘要记录。 */
    private AppQueryToken record(Instant expiry) {
        return new AppQueryToken(tokenId, appId, "test", material.digest(secret),
                material.displayPrefix(secret), UUID.randomUUID(), NOW.minusSeconds(1), expiry);
    }
}
