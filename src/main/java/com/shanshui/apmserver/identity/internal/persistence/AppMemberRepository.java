package com.shanshui.apmserver.identity.internal.persistence;

import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppMemberRepository extends JpaRepository<AppMember, AppMemberId> {

    @Query("select m.role from AppMember m where m.id.appId = :appId and m.id.userId = :userId")
    Optional<AppRole> findRole(@Param("appId") UUID appId, @Param("userId") UUID userId);
}
