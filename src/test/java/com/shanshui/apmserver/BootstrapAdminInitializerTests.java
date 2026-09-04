package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.config.AuthProperties;
import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.application.BootstrapAdminInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BootstrapAdminInitializerTests {

    @Test
    void createsAnIdempotentBcryptHashWithoutPersistingPlaintext() {
        AuthProperties properties = new AuthProperties();
        properties.setBootstrapAdminEmail(" Admin@Example.com ");
        properties.setBootstrapAdminPassword("secret-password");
        properties.setBootstrapAdminDisplayName("管理员");
        AppUserRepository repository = mock(AppUserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        when(repository.findByEmailNormalized("admin@example.com"))
                .thenReturn(Optional.empty());

        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(properties, repository, encoder);
        initializer.run(null);

        var captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(repository).save(captor.capture());
        AppUser created = captor.getValue();
        assertNotEquals("secret-password", created.getPasswordHash());
        assertTrue(encoder.matches("secret-password", created.getPasswordHash()));

        when(repository.findByEmailNormalized("admin@example.com"))
                .thenReturn(Optional.of(created));
        initializer.run(null);
        verify(repository, times(2)).findByEmailNormalized("admin@example.com");
        verifyNoMoreInteractions(repository);
    }
}
