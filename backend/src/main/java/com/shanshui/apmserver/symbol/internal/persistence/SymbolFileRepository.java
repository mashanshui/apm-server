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
    @Query("select s from SymbolFileEntity s where s.deletedAt IS NULL and s.appId = :appId "
            + "and (:buildId = '' or s.buildId = :buildId) order by s.updatedAt desc, s.symbolId desc")
    Page<SymbolFileEntity> findPage(@Param("appId") UUID appId,
                                    @Param("buildId") String buildId,
                                    Pageable pageable);

    /** 按应用和构建标识查询唯一当前版本。 */
    Optional<SymbolFileEntity> findByAppIdAndBuildIdAndDeletedAtIsNull(UUID appId, String buildId);

    /** 按服务端存储键查找当前引用。 */
    Optional<SymbolFileEntity> findByStorageKey(String storageKey);

    /** 条件删除只退出活动集合，不硬删除来源主记录。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SymbolFileEntity s set s.deletedAt=:time,s.updatedAt=:time where s.symbolId=:symbolId and s.revision=:revision and s.deletedAt is null")
    int deleteIfRevision(@Param("symbolId") UUID symbolId, @Param("revision") int revision, @Param("time") java.time.Instant time);

    /** 文件租约登记与删除/替换在同一行锁下串行。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select s from SymbolFileEntity s where s.appId=:appId and s.buildId=:buildId and s.deletedAt is null")
    Optional<SymbolFileEntity> lockActiveBuild(@Param("appId") UUID appId, @Param("buildId") String buildId);

    /** 幂等确认期间锁住当前身份，避免等内容路径越过并发删除或替换。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select s from SymbolFileEntity s where s.symbolId=:symbolId and s.appId=:appId and s.deletedAt is null")
    Optional<SymbolFileEntity> lockActiveId(@Param("appId") UUID appId, @Param("symbolId") UUID symbolId);

    /** 数据库侧精确过滤和键集分页，不读取应用全部符号表。 */
    @Query("select s from SymbolFileEntity s where s.appId=:appId and s.deletedAt is null and (:buildId='' or s.buildId=:buildId) "
            + "and (:first=true or s.updatedAt<:afterTime or (s.updatedAt=:afterTime and s.symbolId<:afterId)) order by s.updatedAt desc,s.symbolId desc")
    List<SymbolFileEntity> activePage(@Param("appId") UUID appId, @Param("buildId") String buildId,
            @Param("first") boolean first, @Param("afterTime") java.time.Instant afterTime, @Param("afterId") UUID afterId, Pageable page);

    /** 启动文件清理只检查当前引用，不保留已删除文件。 */
    boolean existsByStorageKeyAndDeletedAtIsNull(String storageKey);

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
            + "where s.symbolId = :symbolId and s.revision = :expectedRevision and s.deletedAt is null")
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
