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

    /** 要求当前用户具备应用 Owner 或 Admin 写权限。 */
    @Override
    public void requireEdit(java.util.UUID appId, Authentication authentication) {
        membershipService.requireEdit(appId, currentUserService.requireId(authentication));
    }

    /** 返回当前已认证用户标识。 */
    @Override
    public java.util.UUID requireUserId(Authentication authentication) {
        return currentUserService.requireId(authentication);
    }

    /** 每次写入重新查询应用成员角色，不依赖网页缓存的角色。 */
    @Override
    public void requireAnalysis(java.util.UUID appId, Authentication authentication) {
        membershipService.requireAnalysis(appId, currentUserService.requireId(authentication));
    }
}
