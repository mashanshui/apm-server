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
            List<SymbolFileEntity> all = fileRepository.findAllByAppIdOrderByUpdatedAtDescSymbolIdDesc(appId);
            if (!normalizedBuildId.isEmpty()) {
                all = all.stream().filter(entity -> normalizedBuildId.equals(entity.getBuildId())).toList();
            }
            int start = cursorIndex(all, cursor);
            List<SymbolFileEntity> page = all.subList(Math.min(start, all.size()),
                    Math.min(all.size(), start + pageSize));
            String nextCursor = page.size() == pageSize && !page.isEmpty()
                    ? page.get(page.size() - 1).getSymbolId().toString() : null;
            return new SymbolFilePage(appId, page.stream().map(SymbolFileEntity::metadata).toList(), nextCursor);
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
                    return new UploadResult(current.metadata(), false);
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
                    return new UploadResult(concurrent.metadata(), false);
                }
                if (concurrent != null) {
                    throw new SymbolConflictException(concurrent.metadata());
                }
                throw new SymbolStoreUnavailableException(ex);
            }
        } catch (SymbolConflictException | SymbolStoreUnavailableException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
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
                return current.metadata();
            }
            storageKey = fileStore.publish(staged);
            SymbolRecordWriter.Replacement replaced = recordWriter.replace(appId, symbolId, userId,
                    expectedRevision, fileName(file), staged, storageKey);
            fileStore.retire(replaced.oldStorageKey());
            return replaced.metadata();
        } catch (SymbolVersionConflictException | SymbolValidationException | SymbolStoreUnavailableException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (storageKey != null) {
                fileStore.retire(storageKey);
            } else {
                fileStore.discard(staged);
            }
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 按应用和构建标识取得当前实体，供统一注册表使用。 */
    public SymbolFileEntity current(UUID appId, String buildId) {
        String normalizedBuildId = validateBuildId(buildId);
        try {
            return fileRepository.findByAppIdAndBuildId(appId, normalizedBuildId).orElse(null);
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 启动时删除数据库未引用的受控 mapping 文件。 */
    public void cleanupOrphans() {
        try {
            fileStore.collectOrphans(fileRepository.findAll(Sort.by("storageKey")).stream()
                    .map(SymbolFileEntity::getStorageKey).collect(java.util.stream.Collectors.toSet()));
        } catch (DataAccessException | SymbolStoreUnavailableException ex) {
            // 数据库或文件卷不可用时禁止启动清理，避免误删当前版本文件。
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
                    .filter(value -> value.getAppId().equals(appId))
                    .orElseThrow(() -> new SymbolValidationException("SYMBOL_NOT_FOUND", "符号表不存在", 404));
            return entity;
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 查询应用构建当前记录，数据库异常统一映射为存储不可用。 */
    private SymbolFileEntity findCurrent(UUID appId, String buildId) {
        try {
            return fileRepository.findByAppIdAndBuildId(appId, buildId).orElse(null);
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

    /** 计算游标对应的起始下标。 */
    private int cursorIndex(List<SymbolFileEntity> values, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        for (int index = 0; index < values.size(); index++) {
            if (cursor.equals(values.get(index).getSymbolId().toString())) {
                return index + 1;
            }
        }
        return 0;
    }

    /** 上传结果，区分首次创建和相同内容幂等。 */
    public record UploadResult(SymbolFileMetadata metadata, boolean created) {
    }
}
