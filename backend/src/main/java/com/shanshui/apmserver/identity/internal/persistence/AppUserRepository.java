package com.shanshui.apmserver.identity.internal.persistence;

import com.shanshui.apmserver.identity.internal.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailNormalized(String emailNormalized);
}
