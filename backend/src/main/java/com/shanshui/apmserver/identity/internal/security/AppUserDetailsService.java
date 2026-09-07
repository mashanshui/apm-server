package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.application.AppInputValidator;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public AppUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email;
        try {
            email = AppInputValidator.normalizeEmail(username);
        } catch (RuntimeException ex) {
            throw new UsernameNotFoundException("用户不存在");
        }
        AppUser user = userRepository.findByEmailNormalized(email)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        return AppUserPrincipal.from(user);
    }
}
