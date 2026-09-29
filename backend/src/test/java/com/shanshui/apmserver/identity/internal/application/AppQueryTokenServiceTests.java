package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.InvalidAppInputException;
import com.shanshui.apmserver.identity.api.QueryTokenLimitException;
import com.shanshui.apmserver.identity.internal.config.QueryTokenProperties;
import com.shanshui.apmserver.identity.internal.domain.AppQueryToken;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenCreated;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenMetadata;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 可控时钟下验证凭据有效期、摘要和数量边界。 */
class AppQueryTokenServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private final UUID appId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AppMembershipService membership = mock(AppMembershipService.class);
    private final ApmAppRepository apps = mock(ApmAppRepository.class);
    private final AppQueryTokenRepository tokens = mock(AppQueryTokenRepository.class);
    private final QueryTokenMaterial material = new QueryTokenMaterial();
    private final QueryTokenProperties properties = new QueryTokenProperties();
    private final AppQueryTokenService service = new AppQueryTokenService(membership, apps, tokens,
            material, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 默认期限为九十天且只有创建响应持有完整 Token。 */
    @Test
    void createsNinetyDayDigestOnlyToken() {
        when(apps.lockForQueryTokenCreation(appId)).thenReturn(Optional.of(mock(com.shanshui.apmserver.identity.internal.domain.ApmApp.class)));
        when(tokens.save(any(AppQueryToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        QueryTokenCreated created = service.create(userId, appId, " CI  ", null);
        assertTrue(material.isWellFormed(created.token()));
        assertEquals("CI", created.metadata().name());
        assertEquals(NOW.plusSeconds(90L * 86400), created.metadata().expiresAt());
        assertEquals("ACTIVE", created.metadata().status());
        org.mockito.ArgumentCaptor<AppQueryToken> saved = org.mockito.ArgumentCaptor.forClass(AppQueryToken.class);
        verify(tokens).save(saved.capture());
        assertTrue(Arrays.equals(material.digest(created.token()), saved.getValue().getTokenDigest()));
        assertFalse(Arrays.equals(created.token().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                saved.getValue().getTokenDigest()));
    }

    /** 仅三个期限可用，缺失或非法名称也不可插入。 */
    @Test
    void rejectsInvalidExpiryAndName() {
        for (int days : new int[]{0, 1, 31, 366}) {
            assertThrows(InvalidAppInputException.class, () -> service.create(userId, appId, "CI", days));
        }
        assertThrows(InvalidAppInputException.class, () -> service.create(userId, appId, " ", 30));
        verify(tokens, never()).save(any(AppQueryToken.class));
    }

    /** 已到期的精确时刻不再有效；撤销优先于到期。 */
    @Test
    void derivesExpiryAndRevocationAtBoundary() {
        AppQueryToken token = new AppQueryToken(UUID.randomUUID(), appId, "CI", new byte[32],
                "apm_qt_prefix", userId, NOW, NOW.plusSeconds(30L * 86400));
        assertEquals("ACTIVE", QueryTokenMetadata.from(token, token.getExpiresAt().minusNanos(1)).status());
        assertEquals("EXPIRED", QueryTokenMetadata.from(token, token.getExpiresAt()).status());
        token.revoke(NOW.plusSeconds(1));
        assertEquals("REVOKED", QueryTokenMetadata.from(token, token.getExpiresAt()).status());
    }

    /** 有效凭据达到上限时绝不生成新记录。 */
    @Test
    void rejectsActiveLimit() {
        when(apps.lockForQueryTokenCreation(appId)).thenReturn(Optional.of(mock(com.shanshui.apmserver.identity.internal.domain.ApmApp.class)));
        when(tokens.countByAppIdAndRevokedAtIsNullAndExpiresAtAfter(eq(appId), eq(NOW))).thenReturn(20L);
        assertThrows(QueryTokenLimitException.class, () -> service.create(userId, appId, "CI", 365));
        verify(tokens, never()).save(any(AppQueryToken.class));
    }
}
