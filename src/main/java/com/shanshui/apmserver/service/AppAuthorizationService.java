package com.shanshui.apmserver.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AppAuthorizationService {

    private final CurrentUserService currentUserService;
    private final AppMembershipService membershipService;

    public AppAuthorizationService(CurrentUserService currentUserService,
                                       AppMembershipService membershipService) {
        this.currentUserService = currentUserService;
        this.membershipService = membershipService;
    }

    public void requireView(java.util.UUID appId, Authentication authentication) {
        membershipService.requireView(appId, currentUserService.requireId(authentication));
    }
}
