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

    /** 单事件分析授权不扩大应用配置或凭据管理权限。 */
    public void requireAnalysis(UUID appId, UUID userId) {
        // 角色从当前数据库关系推导。
        AppRole role = roleFor(appId, userId);
        if (role == null) {
            throw new AppNotFoundException();
        }
        if (role != AppRole.OWNER && role != AppRole.ADMIN && role != AppRole.DEVELOPER) {
            throw new AppRoleDeniedException();
        }
    }
}
