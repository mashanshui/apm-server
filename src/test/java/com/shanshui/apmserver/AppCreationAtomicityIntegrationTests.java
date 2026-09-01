package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.AppCreateRequest;
import com.shanshui.apmserver.repository.ApmAppRepository;
import com.shanshui.apmserver.repository.AppUserRepository;
import com.shanshui.apmserver.repository.AppIngestCredentialRepository;
import com.shanshui.apmserver.repository.AppMemberRepository;
import com.shanshui.apmserver.service.AppCredentialCreationException;
import com.shanshui.apmserver.service.AppIngestCredentialService;
import com.shanshui.apmserver.service.AppManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class AppCreationAtomicityIntegrationTests {

    @Autowired
    private AppManagementService appManagementService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private ApmAppRepository appRepository;

    @Autowired
    private AppMemberRepository memberRepository;

    @Autowired
    private AppIngestCredentialRepository credentialRepository;

    @MockitoBean
    private AppIngestCredentialService credentialService;

    @BeforeEach
    void clearApps() {
        memberRepository.deleteAll();
        credentialRepository.deleteAll();
        appRepository.deleteAll();
    }

    @Test
    void rollsBackAppAndOwnerWhenCredentialCreationFails() {
        var owner = userRepository.findByEmailNormalized("test@example.com").orElseThrow();
        doThrow(new AppCredentialCreationException(new IllegalStateException("simulated persistence failure")))
                .when(credentialService).create(any(UUID.class), eq("com.example.atomic"), any(Instant.class));

        assertThrows(AppCredentialCreationException.class, () -> appManagementService.create(owner.getId(),
                new AppCreateRequest("com.example.atomic")));

        assertFalse(appRepository.existsByPackageName("com.example.atomic"));
        assertEquals(0, memberRepository.count());
        assertEquals(0, credentialRepository.count());
    }
}
