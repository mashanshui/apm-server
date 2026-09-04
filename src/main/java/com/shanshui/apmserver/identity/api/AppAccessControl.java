package com.shanshui.apmserver.identity.api;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/** 为其他业务模块提供统一的应用查看权限检查。 */
public interface AppAccessControl {

    void requireView(UUID appId, Authentication authentication);
}
