package com.shanshui.apmserver.symbol.internal.persistence;

import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 符号表当前版本的数据库访问接口。 */
public interface SymbolFileRepository extends JpaRepository<SymbolFileEntity, UUID> {

    /** 查询应用下按构建标识筛选的当前记录。 */
    @Query("select s from SymbolFileEntity s where s.appId = :appId "
            + "and (:buildId = '' or s.buildId = :buildId) order by s.updatedAt desc, s.symbolId desc")
    Page<SymbolFileEntity> findPage(@Param("appId") UUID appId,
                                    @Param("buildId") String buildId,
                                    Pageable pageable);

    /** 按应用和构建标识查询唯一当前版本。 */
    Optional<SymbolFileEntity> findByAppIdAndBuildId(UUID appId, String buildId);

    /** 分析短事务发布阶段读锁定版本，与条件替换行锁互斥。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select s from SymbolFileEntity s where s.appId=:appId and s.buildId=:buildId")
    Optional<SymbolFileEntity> lockCurrentForAnalysis(@Param("appId") UUID appId, @Param("buildId") String buildId);

    /** 按服务端存储键查找当前引用。 */
    Optional<SymbolFileEntity> findByStorageKey(String storageKey);

    /** 返回应用下按更新时间倒序排列的当前记录。 */
    List<SymbolFileEntity> findAllByAppIdOrderByUpdatedAtDescSymbolIdDesc(UUID appId);

    /**
     * 仅在调用方仍持有当前 revision 时替换文件元数据，避免两个管理员同时覆盖。
     * 该更新使用数据库条件保证只有一个并发请求能够成功。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SymbolFileEntity s set s.revision = :newRevision, "
            + "s.storageKey = :storageKey, s.originalFilename = :originalFilename, "
            + "s.sizeBytes = :sizeBytes, s.sha256 = :sha256, s.uploadedBy = :uploadedBy, "
            + "s.updatedAt = :updatedAt "
            + "where s.symbolId = :symbolId and s.revision = :expectedRevision")
    int replaceIfRevision(@Param("symbolId") UUID symbolId,
                          @Param("expectedRevision") int expectedRevision,
                          @Param("newRevision") int newRevision,
                          @Param("storageKey") String storageKey,
                          @Param("originalFilename") String originalFilename,
                          @Param("sizeBytes") long sizeBytes,
                          @Param("sha256") String sha256,
                          @Param("uploadedBy") UUID uploadedBy,
                          @Param("updatedAt") java.time.Instant updatedAt);
}
