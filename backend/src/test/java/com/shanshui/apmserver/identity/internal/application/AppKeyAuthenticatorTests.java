package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.identity.api.AppAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.InvalidAppKeyException;
import com.shanshui.apmserver.identity.internal.config.AppKeyProperties;
import com.shanshui.apmserver.identity.internal.persistence.AppCredentialIdentity;
import com.shanshui.apmserver.identity.internal.persistence.AppIngestCredentialRepository;
import com.shanshui.apmserver.TestAppIds;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppKeyAuthenticatorTests {

    private static final String VALID_KEY = "apm_ak_" + "A".repeat(43);

    @Test
    void mapsValidKeyDigestToImmutableAppIdentity() {
        AppIngestCredentialRepository repository = mock(AppIngestCredentialRepository.class);
        AppCredentialIdentity identity = mock(AppCredentialIdentity.class);
        when(identity.getAppId()).thenReturn(TestAppIds.id("app-a"));
        when(identity.getPackageName()).thenReturn("com.example.app");
        when(repository.findIdentityByKeyDigest(any(byte[].class))).thenReturn(Optional.of(identity));

        var authenticated = authenticator(repository).authenticate(VALID_KEY);

        assertEquals(TestAppIds.id("app-a"), authenticated.appId());
        assertEquals("com.example.app", authenticated.packageName());
        verify(repository).findIdentityByKeyDigest(any(byte[].class));
    }

    @Test
    void unifiesMissingMalformedAndUnknownKeysAsUnauthorized() {
        AppIngestCredentialRepository repository = mock(AppIngestCredentialRepository.class);
        AppKeyAuthenticator authenticator = authenticator(repository);

        assertThrows(InvalidAppKeyException.class, () -> authenticator.authenticate(null));
        assertThrows(InvalidAppKeyException.class, () -> authenticator.authenticate(""));
        assertThrows(InvalidAppKeyException.class, () -> authenticator.authenticate("not-a-app-key"));
        verifyNoInteractions(repository);

        when(repository.findIdentityByKeyDigest(any(byte[].class))).thenReturn(Optional.empty());
        assertThrows(InvalidAppKeyException.class, () -> authenticator.authenticate(VALID_KEY));
    }

    @Test
    void mapsCredentialStoreFailureToRetryableAuthenticationFailure() {
        AppIngestCredentialRepository repository = mock(AppIngestCredentialRepository.class);
        when(repository.findIdentityByKeyDigest(any(byte[].class)))
                .thenThrow(new DataAccessResourceFailureException("database detail must not escape"));

        assertThrows(AppAuthenticationUnavailableException.class,
                () -> authenticator(repository).authenticate(VALID_KEY));
    }

    private AppKeyAuthenticator authenticator(AppIngestCredentialRepository repository) {
        IngestConfigurationProperties ingestProperties = new IngestConfigurationProperties();
        AppKeyProperties keyProperties = new AppKeyProperties();
        keyProperties.setEncryptionKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return new AppKeyAuthenticator(ingestProperties, repository, new AppKeyCrypto(keyProperties));
    }
}
