package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolConflictException;
import com.shanshui.apmserver.symbol.api.SymbolFileMetadata;
import com.shanshui.apmserver.symbol.api.SymbolFilePage;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.api.SymbolVersionConflictException;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 符号表网页管理、版本切换和注册表查询服务。 */
@Service
public class SymbolManagementService {

    /** buildId 的安全格式，与卡顿产物注册规则保持一致。 */
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /** 游标页默认大小。 */
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** 游标页最大大小。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 当前版本仓库。 */
    private final SymbolFileRepository fileRepository;
    /** 负责事务内写入当前版本和审计的组件。 */
    private final SymbolRecordWriter recordWriter;
    /** 上传校验和详情解析共享的并发控制。 */
    private final SymbolOperationLimiter operationLimiter;
    /** 受控符号文件卷。 */
    private final SymbolFileStore fileStore;
    /** 官方 R8 校验引擎。 */
    private final R8RetraceEngine retraceEngine;
    /** 符号表资源配置。 */
    private final SymbolProperties properties;

    /** 注入符号表管理依赖。 */
    public SymbolManagementService(SymbolFileRepository fileRepository,
                                   SymbolRecordWriter recordWriter,
                                   SymbolOperationLimiter operationLimiter,
                                   SymbolFileStore fileStore,
                                   R8RetraceEngine retraceEngine,
                                   SymbolProperties properties) {
        this.fileRepository = fileRepository;
        this.recordWriter = recordWriter;
        this.operationLimiter = operationLimiter;
        this.fileStore = fileStore;
        this.retraceEngine = retraceEngine;
        this.properties = properties;
    }

    /** 返回应用下按 buildId 精确筛选的游标页。 */
    public SymbolFilePage list(UUID appId, String buildId, String cursor, Integer limit) {
        String normalizedBuildId = buildId == null ? "" : buildId.trim();
        if (!normalizedBuildId.isEmpty()) {
            validateBuildId(normalizedBuildId);
        }
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new SymbolValidationException("INVALID_LIMIT", "符号表分页大小超出范围", 400);
        }
        try {
            // 游标只定位当前应用/筛选中的活动记录，失效游标不静默回第一页。
            SymbolFileEntity after = null;
            if (cursor != null && !cursor.isBlank()) {
                UUID id;
                try { id = UUID.fromString(cursor); }
                catch (IllegalArgumentException invalid) { throw new SymbolValidationException("INVALID_CURSOR", "符号表游标无效", 400); }
                after = findById(appId, id);
                if (!normalizedBuildId.isEmpty() && !normalizedBuildId.equals(after.getBuildId())) throw new SymbolValidationException("INVALID_CURSOR", "符号表游标不属于当前筛选", 400);
            }
            List<SymbolFileEntity> rows = fileRepository.activePage(appId, normalizedBuildId, after == null,
                    after == null ? java.time.Instant.EPOCH : after.getUpdatedAt(), after == null ? new UUID(0, 0) : after.getSymbolId(),
                    org.springframework.data.domain.PageRequest.of(0, pageSize + 1));
            boolean more = rows.size() > pageSize;
            List<SymbolFileEntity> page = more ? rows.subList(0, pageSize) : rows;
            return new SymbolFilePage(appId, page.stream().map(SymbolFileEntity::metadata).toList(),
                    more ? page.getLast().getSymbolId().toString() : null);
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 首次注册单份 mapping，重复字节按幂等成功处理。 */
    public UploadResult upload(UUID appId, UUID userId, String buildId, MultipartFile file) {
        String normalizedBuildId = validateBuildId(buildId);
        SymbolFileStore.StagedFile staged = stageAndValidate(file);
        String storageKey = null;
        try {
            SymbolFileEntity current = findCurrent(appId, normalizedBuildId);
            if (current != null) {
                if (current.getSha256().equals(staged.sha256())) {
                    fileStore.discard(staged);
                    return new UploadResult(recordWriter.reaffirm(appId, current.getSymbolId(), current.getRevision()), false);
                }
                throw new SymbolConflictException(current.metadata());
            }
            storageKey = fileStore.publish(staged);
            try {
                SymbolFileMetadata created = recordWriter.create(appId, userId, normalizedBuildId, fileName(file),
                        staged, storageKey);
                return new UploadResult(created, true);
            } catch (DataIntegrityViolationException ex) {
                fileStore.retire(storageKey);
                SymbolFileEntity concurrent = findCurrent(appId, normalizedBuildId);
                if (concurrent != null && concurrent.getSha256().equals(staged.sha256())) {
                    return new UploadResult(recordWriter.reaffirm(appId, concurrent.getSymbolId(), concurrent.getRevision()), false);
                }
                if (concurrent != null) {
                    throw new SymbolConflictException(concurrent.metadata());
                }
                throw new SymbolStoreUnavailableException(ex);
            }
        } catch (SymbolConflictException | SymbolVersionConflictException | SymbolValidationException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
            throw ex;
        } catch (RuntimeException ex) {
            // 提交确认未知时，已发布文件可能已被数据库引用，不能按异常推断回滚并删除。
            // 未发布暂存仍可直接清理；已发布文件保留，后续启动按确认后的活动引用清理孤立文件。
            if (storageKey == null) fileStore.discard(staged);
            if (ex instanceof SymbolStoreUnavailableException unavailable) throw unavailable;
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 基于 expectedRevision 替换当前 mapping。 */
    public SymbolFileMetadata replace(UUID appId, UUID symbolId, UUID userId,
                                      int expectedRevision, MultipartFile file) {
        if (expectedRevision < 1) {
            throw new SymbolValidationException("INVALID_REVISION", "expectedRevision 必须为正数", 400);
        }
        SymbolFileStore.StagedFile staged = stageAndValidate(file);
        String storageKey = null;
        try {
            SymbolFileEntity current = findById(appId, symbolId);
            if (current.getRevision() != expectedRevision) {
                throw new SymbolVersionConflictException(current.metadata());
            }
            if (current.getSha256().equals(staged.sha256())) {
                fileStore.discard(staged);
                return recordWriter.reaffirm(appId, current.getSymbolId(), expectedRevision);
            }
            storageKey = fileStore.publish(staged);
            SymbolRecordWriter.Replacement replaced = recordWriter.replace(appId, symbolId, userId,
                    expectedRevision, fileName(file), staged, storageKey);
            fileStore.retire(replaced.oldStorageKey());
            return replaced.metadata();
        } catch (SymbolVersionConflictException | SymbolValidationException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
            throw ex;
        } catch (RuntimeException ex) {
            // 提交确认未知时，已发布文件可能已被数据库引用，不能按异常推断回滚并删除。
            // 未发布暂存仍可直接清理；已发布文件保留，后续启动按确认后的活动引用清理孤立文件。
            if (storageKey == null) fileStore.discard(staged);
            if (ex instanceof SymbolStoreUnavailableException unavailable) throw unavailable;
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 活动行读锁覆盖文件租约登记，删除和替换不能让新读取者取得退休文件。 */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Optional<com.shanshui.apmserver.symbol.api.SymbolFileLease> acquire(UUID appId, String buildId) {
        return fileRepository.lockActiveBuild(appId, validateBuildId(buildId)).map(fileStore::open);
    }

    /** 先提交软删除，再按已有读取租约退休文件。 */
    public void delete(UUID appId, UUID symbolId, UUID userId, int expectedRevision) {
        if (expectedRevision < 1) throw new SymbolValidationException("INVALID_REVISION", "expectedRevision 必须为正数", 400);
        try { fileStore.retire(recordWriter.delete(appId, symbolId, userId, expectedRevision)); }
        catch (DataAccessException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 进度仅查询当前符号表及对应构建任务，删除身份不再提供轮询。 */
    public com.shanshui.apmserver.crash.api.CrashSymbolicationWork.Progress progress(UUID appId, UUID symbolId,
            com.shanshui.apmserver.crash.api.CrashSymbolicationWork work) {
        SymbolFileEntity current = findById(appId, symbolId);
        return work.progress(appId, current.getBuildId());
    }

    /** 逐文件有界检查当前引用，不加载所有历史元数据；数据库不可用时保留文件。 */
    public void cleanupOrphans() {
        try { fileStore.collectOrphans(fileRepository::existsByStorageKeyAndDeletedAtIsNull); }
        catch (DataAccessException | SymbolStoreUnavailableException unavailable) {
            // 引用关系不可确认时禁止删除任何未核验文件。
        }
    }

    /** 暂存上传文件并执行完整 R8 mapping 校验。 */
    private SymbolFileStore.StagedFile stageAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SymbolValidationException("INVALID_MAPPING", "mapping 文件不能为空", 422);
        }
        SymbolFileStore.StagedFile staged;
        try {
            staged = fileStore.stage(file.getInputStream(), properties.getMaxFileBytes());
        } catch (IOException ex) {
            throw new SymbolStoreUnavailableException(ex);
        } catch (SymbolValidationException ex) {
            throw ex;
        } catch (SymbolStoreUnavailableException ex) {
            throw ex;
        }
        try {
            try (SymbolOperationLimiter.Permit ignored = operationLimiter.acquire()) {
                retraceEngine.validate(staged.path());
            }
            return staged;
        } catch (RuntimeException ex) {
            fileStore.discard(staged);
            throw ex;
        }
    }


    /** 读取当前记录并校验所属应用。 */
    private SymbolFileEntity findById(UUID appId, UUID symbolId) {
        try {
            SymbolFileEntity entity = fileRepository.findById(symbolId)
                    .filter(value -> value.getAppId().equals(appId) && value.getDeletedAt() == null)
                    .orElseThrow(() -> new SymbolValidationException("SYMBOL_NOT_FOUND", "符号表不存在", 404));
            return entity;
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 查询应用构建当前记录，数据库异常统一映射为存储不可用。 */
    private SymbolFileEntity findCurrent(UUID appId, String buildId) {
        try {
            return fileRepository.findByAppIdAndBuildIdAndDeletedAtIsNull(appId, buildId).orElse(null);
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 校验并返回规范化 buildId。 */
    private String validateBuildId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !BUILD_ID.matcher(normalized).matches()
                || ".".equals(normalized) || "..".equals(normalized)) {
            throw new SymbolValidationException("INVALID_BUILD_ID", "buildId 格式无效", 400);
        }
        return normalized;
    }

    /** 清理用户文件名中的目录部分和控制字符。 */
    private String fileName(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        String value = original == null || original.isBlank() ? "mapping.txt" : original;
        try {
            value = Path.of(value).getFileName().toString();
        } catch (RuntimeException ex) {
            value = "mapping.txt";
        }
        value = value.replaceAll("[\\p{Cntrl}]", "_").trim();
        if (value.isEmpty()) {
            return "mapping.txt";
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    /** 上传结果，区分首次创建和相同内容幂等。 */
    public record UploadResult(SymbolFileMetadata metadata, boolean created) {
    }
}
