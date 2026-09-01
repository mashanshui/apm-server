package com.shanshui.apmserver.domain;

import com.shanshui.apmserver.security.AppUserPrincipal;

import java.util.UUID;

public record CurrentUserResponse(UUID id, String email, String displayName) {

    public static CurrentUserResponse from(AppUserPrincipal principal) {
        return new CurrentUserResponse(principal.getUserId(), principal.getUsername(), principal.getDisplayName());
    }
}
