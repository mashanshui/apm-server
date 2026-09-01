package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.AuthProperties;
import com.shanshui.apmserver.domain.AppUser;
import com.shanshui.apmserver.domain.UserStatus;
import com.shanshui.apmserver.repository.AppUserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final AuthProperties properties;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminInitializer(AuthProperties properties,
                                     AppUserRepository userRepository,
                                     PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(properties.getBootstrapAdminEmail()) || isBlank(properties.getBootstrapAdminPassword())) {
            return;
        }
        String email = AppInputValidator.normalizeEmail(properties.getBootstrapAdminEmail());
        if (userRepository.findByEmailNormalized(email).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        String displayName = isBlank(properties.getBootstrapAdminDisplayName())
                ? "平台管理员" : properties.getBootstrapAdminDisplayName().trim();
        userRepository.save(new AppUser(UUID.randomUUID(), email, displayName,
                passwordEncoder.encode(properties.getBootstrapAdminPassword()), UserStatus.ACTIVE, now, now));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
