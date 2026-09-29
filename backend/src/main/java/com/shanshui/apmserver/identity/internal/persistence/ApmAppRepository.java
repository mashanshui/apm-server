package com.shanshui.apmserver.identity.internal.persistence;

import com.shanshui.apmserver.identity.internal.domain.AppMember;

import com.shanshui.apmserver.identity.internal.domain.ApmApp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import jakarta.persistence.LockModeType;

public interface ApmAppRepository extends JpaRepository<ApmApp, java.util.UUID> {

    @Query("select p from ApmApp p join AppMember m on m.id.appId = p.appId "
            + "where m.id.userId = :userId and (:query = '' or lower(p.name) like lower(concat('%', :query, '%')) "
            + "or lower(p.packageName) like lower(concat('%', :query, '%')) "
            + "or str(p.appId) like concat('%', :query, '%')) order by p.updatedAt desc")
    List<ApmApp> findVisibleApps(@Param("userId") java.util.UUID userId, @Param("query") String query);

    boolean existsByPackageName(String packageName);

    @Query("select p.packageName from ApmApp p where p.appId = :appId")
    java.util.Optional<String> findPackageNameByAppId(@Param("appId") java.util.UUID appId);

    /** 串行化同一应用的 Token 创建，防止并发突破有效数量上限。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApmApp a where a.appId = :appId")
    java.util.Optional<ApmApp> lockForQueryTokenCreation(@Param("appId") java.util.UUID appId);
}
