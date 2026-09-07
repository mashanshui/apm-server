package com.shanshui.apmserver.identity.internal.domain;

import java.util.UUID;

public record CurrentUserResponse(UUID id, String email, String displayName) {

    public static CurrentUserResponse of(UUID id, String email, String displayName) {
        return new CurrentUserResponse(id, email, displayName);
    }
}
