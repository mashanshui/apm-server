package com.shanshui.apmserver.identity.api;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/** 为其他业务模块提供统一的应用查看权限检查。 */
public interface AppAccessControl {

    void requireView(UUID appId, Authentication authentication);

    /** 要求当前用户具备应用 Owner 或 Admin 写权限。 */
    void requireEdit(UUID appId, Authentication authentication);

    /** 返回当前已认证用户标识，供跨模块审计记录使用。 */
    UUID requireUserId(Authentication authentication);
}
