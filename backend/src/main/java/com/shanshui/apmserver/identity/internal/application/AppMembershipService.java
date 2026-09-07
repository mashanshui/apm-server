package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.api.AppRoleDeniedException;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AppMembershipService {

    private final AppMemberRepository memberRepository;

    public AppMembershipService(AppMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public AppRole roleFor(UUID appId, UUID userId) {
        return memberRepository.findRole(appId, userId).orElse(null);
    }

    public void requireView(UUID appId, UUID userId) {
        if (roleFor(appId, userId) == null) {
            throw new AppNotFoundException();
        }
    }

    public void requireEdit(UUID appId, UUID userId) {
        AppRole role = roleFor(appId, userId);
        if (role == null) {
            throw new AppNotFoundException();
        }
        if (!role.canEditApp()) {
            throw new AppRoleDeniedException();
        }
    }

    public void requireCredentialView(UUID appId, UUID userId) {
        requireEdit(appId, userId);
    }
}
