package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.AppIngestCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppIngestCredentialRepository extends JpaRepository<AppIngestCredential, java.util.UUID> {

    boolean existsByKeyDigest(byte[] keyDigest);

    @Query("select c.appId as appId, a.packageName as packageName "
            + "from AppIngestCredential c join ApmApp a on a.appId = c.appId where c.keyDigest = :keyDigest")
    Optional<AppCredentialIdentity> findIdentityByKeyDigest(@Param("keyDigest") byte[] keyDigest);

    @Query("select c.appId as appId, a.packageName as packageName "
            + "from AppIngestCredential c join ApmApp a on a.appId = c.appId where c.appId = :appId")
    Optional<AppCredentialIdentity> findIdentityByAppId(@Param("appId") java.util.UUID appId);
}
