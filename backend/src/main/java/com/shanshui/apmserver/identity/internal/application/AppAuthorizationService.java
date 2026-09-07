package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AppAuthorizationService implements AppAccessControl {

    private final CurrentUserService currentUserService;
    private final AppMembershipService membershipService;

    public AppAuthorizationService(CurrentUserService currentUserService,
                                       AppMembershipService membershipService) {
        this.currentUserService = currentUserService;
        this.membershipService = membershipService;
    }

    @Override
    public void requireView(java.util.UUID appId, Authentication authentication) {
        membershipService.requireView(appId, currentUserService.requireId(authentication));
    }
}
