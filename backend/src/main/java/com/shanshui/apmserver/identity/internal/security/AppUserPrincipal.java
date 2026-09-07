package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.internal.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class AppUserPrincipal implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final boolean enabled;

    private AppUserPrincipal(UUID userId, String email, String displayName, String passwordHash, boolean enabled) {
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    public static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(user.getId(), user.getEmailNormalized(), user.getDisplayName(),
                user.getPasswordHash(), user.getStatus().name().equals("ACTIVE"));
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
