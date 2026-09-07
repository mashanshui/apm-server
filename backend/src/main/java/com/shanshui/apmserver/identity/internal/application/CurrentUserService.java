package com.shanshui.apmserver.identity.internal.application;

import com.shanshui.apmserver.identity.api.UnauthenticatedException;
import com.shanshui.apmserver.identity.internal.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserService {

    public UUID requireId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new UnauthenticatedException();
        }
        return principal.getUserId();
    }
}
