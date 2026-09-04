package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.api.PackageNameConflictException;
import com.shanshui.apmserver.identity.api.UnauthenticatedException;
import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.domain.ApmApp;
import com.shanshui.apmserver.identity.internal.domain.AppCreateRequest;
import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppResponse;
import com.shanshui.apmserver.identity.internal.domain.AppIngestCredentialResponse;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.domain.AppUpdateRequest;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AppManagementService {

    private final AppUserRepository userRepository;
    private final ApmAppRepository appRepository;
    private final AppMemberRepository memberRepository;
    private final AppMembershipService membershipService;
    private final AppIngestCredentialService credentialService;

    public AppManagementService(AppUserRepository userRepository,
                                    ApmAppRepository appRepository,
                                    AppMemberRepository memberRepository,
                                    AppMembershipService membershipService,
                                    AppIngestCredentialService credentialService) {
        this.userRepository = userRepository;
        this.appRepository = appRepository;
        this.memberRepository = memberRepository;
        this.membershipService = membershipService;
        this.credentialService = credentialService;
    }

    @Transactional(readOnly = true)
    public List<AppResponse> list(UUID userId, String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() > 100) {
            normalizedQuery = normalizedQuery.substring(0, 100);
        }
        return appRepository.findVisibleApps(userId, normalizedQuery).stream()
                .map(app -> AppResponse.from(app, app.getPackageName(),
                        membershipService.roleFor(app.getAppId(), userId)))
                .toList();
    }

    @Transactional
    public AppResponse create(UUID userId, AppCreateRequest request) {
        AppUser user = userRepository.findById(userId).orElseThrow(UnauthenticatedException::new);
        String packageName = AppInputValidator.normalizePackageName(request.packageName());
        if (appRepository.existsByPackageName(packageName)) {
            throw new PackageNameConflictException();
        }
        java.util.UUID appId = java.util.UUID.randomUUID();
        String name = request.name() == null || request.name().trim().isEmpty()
                ? packageName
                : AppInputValidator.normalizeName(request.name());
        String description = AppInputValidator.normalizeDescription(request.description());
        Instant now = Instant.now();
        ApmApp app = new ApmApp(appId, packageName, name, description, user, now, now);
        try {
            appRepository.saveAndFlush(app);
        } catch (DataIntegrityViolationException ex) {
            throw new PackageNameConflictException();
        }
        memberRepository.save(new AppMember(new AppMemberId(appId, userId), AppRole.OWNER, now));
        credentialService.create(appId, packageName, now);
        return AppResponse.from(app, packageName, AppRole.OWNER);
    }

    @Transactional(readOnly = true)
    public AppResponse get(UUID userId, UUID appId) {
        membershipService.requireView(appId, userId);
        ApmApp app = appRepository.findById(appId).orElseThrow(AppNotFoundException::new);
        return AppResponse.from(app, app.getPackageName(),
                membershipService.roleFor(appId, userId));
    }

    @Transactional
    public AppResponse update(UUID userId, UUID appId, AppUpdateRequest request) {
        membershipService.requireEdit(appId, userId);
        ApmApp app = appRepository.findById(appId).orElseThrow(AppNotFoundException::new);
        String name = AppInputValidator.normalizeName(request.name());
        String description = AppInputValidator.normalizeDescription(request.description());
        app.update(name, description, Instant.now());
        return AppResponse.from(app, app.getPackageName(),
                membershipService.roleFor(appId, userId));
    }

    @Transactional(readOnly = true)
    public AppIngestCredentialResponse getIngestCredential(UUID userId, UUID appId) {
        membershipService.requireCredentialView(appId, userId);
        return credentialService.read(appId);
    }
}
