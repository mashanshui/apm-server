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

/** 符号表网页管理、版本切换和注册表查询服务。 */
@Service
public class SymbolManagementService {

    /** 只记录安全的选择阶段，不记录上传内容或文件路径。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SymbolManagementService.class);

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

    /** 全部过滤在数据库执行；游标同时绑定应用与完整筛选。 */
    public SymbolFilePage list(UUID appId, String type, String version, String scope, String build,
                               String abi, String elfId, String cursor, Integer limit) {
        /** 显式类型及其适用字段。 */
        String selectedType = type == null ? "" : type;
        if (!java.util.Set.of("", "MAPPING", "NATIVE_SO").contains(selectedType)) {
            throw new SymbolValidationException("SYMBOL_TYPE_MISMATCH", "符号类型无效", 400);
        }
        if (version != null) validateIdentity(version, "appVersion", 128);
        if (build != null) validateBuildId(build);
        if (scope != null && !java.util.Set.of("default", "build").contains(scope)) {
            throw new SymbolValidationException("INVALID_MAPPING_SCOPE", "mapping 范围无效", 400);
        }
        if (("NATIVE_SO".equals(selectedType) && (build != null || scope != null))
                || ("MAPPING".equals(selectedType) && (abi != null || elfId != null))
                || ("default".equals(scope) && build != null)
                || ((build != null || scope != null) && (abi != null || elfId != null))) {
            throw new SymbolValidationException("SYMBOL_TYPE_MISMATCH", "筛选字段不适用于此类型或范围", 400);
        }
        if (abi != null && !java.util.Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64").contains(abi)) {
            throw new SymbolValidationException("INVALID_ABI", "ABI 无效", 400);
        }
        if (elfId != null && !elfId.matches("([0-9a-f]{2}){1,64}")) {
            throw new SymbolValidationException("INVALID_ELF_BUILD_ID", "完整 ELF Build ID 无效", 400);
        }
        /** 页大小及游标筛选摘要不依赖元数据当前更新时间。 */
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw new SymbolValidationException("INVALID_LIMIT", "分页大小无效", 400);
        String binding = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.util.Arrays.stream(new String[]{appId.toString(), selectedType, version, scope, build, abi, elfId})
                        .map(value -> value == null ? "-" : value.length() + ":" + value)
                        .collect(java.util.stream.Collectors.joining("|")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SymbolFileEntity after = null;
        if (cursor != null) {
            try {
                int separator = cursor.lastIndexOf('.');
                if (separator < 0 || !binding.equals(cursor.substring(0, separator))) throw new IllegalArgumentException();
                after = findById(appId, UUID.fromString(cursor.substring(separator + 1)));
            } catch (RuntimeException invalid) { throw new SymbolValidationException("INVALID_CURSOR", "游标与筛选不一致或已失效", 400); }
        }
        /** 动态条件避免将全部登记加载到应用进程。 */
        final SymbolFileEntity position = after;
        org.springframework.data.jpa.domain.Specification<SymbolFileEntity> filter = (root, query, cb) -> {
            /** 只构造有值的条件，利于版本及模块索引。 */
            var conditions = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            conditions.add(cb.equal(root.get("appId"), appId));
            conditions.add(cb.isNull(root.get("deletedAt")));
            if (!selectedType.isEmpty()) conditions.add(cb.equal(root.get("symbolType"), selectedType));
            if (version != null) conditions.add(cb.equal(root.get("appVersion"), version));
            if (build != null) conditions.add(cb.equal(root.get("buildId"), build));
            if (scope != null) {
                conditions.add(cb.equal(root.get("symbolType"), "MAPPING"));
                conditions.add(cb.isNotNull(root.get("appVersion")));
                conditions.add("default".equals(scope) ? cb.isNull(root.get("buildId")) : cb.isNotNull(root.get("buildId")));
            }
            if (abi != null) conditions.add(cb.equal(root.get("nativeAbi"), abi));
            if (elfId != null) conditions.add(cb.equal(root.get("elfBuildId"), elfId));
            if (position != null) conditions.add(cb.or(cb.lessThan(root.get("updatedAt"), position.getUpdatedAt()),
                    cb.and(cb.equal(root.get("updatedAt"), position.getUpdatedAt()), cb.lessThan(root.get("symbolId"), position.getSymbolId()))));
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        try {
            /** Slice 查询不额外统计全部行。 */
            var rows = fileRepository.findBy(filter, query -> query.sortBy(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("symbolId")))
                    .limit(pageSize + 1).all());
            boolean more = rows.size() > pageSize;
            var page = more ? rows.subList(0, pageSize) : rows;
            return new SymbolFilePage(appId, page.stream().map(SymbolFileEntity::metadata).toList(),
                    more ? binding + "." + page.getLast().getSymbolId() : null);
        } catch (DataAccessException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 首次注册单份 mapping，重复字节按幂等成功处理。 */
    public UploadResult upload(UUID appId, UUID userId, String appVersion, String buildId, MultipartFile file) {
        validateIdentity(appVersion, "appVersion", 128);
        String normalizedBuildId = buildId == null || buildId.codePoints().allMatch(value -> Character.isWhitespace(value) || Character.isSpaceChar(value))
                ? null : validateIdentity(buildId, "buildId", 256);
        SymbolFileStore.StagedFile staged = stageAndValidate(file);
        String storageKey = null;
        try {
            SymbolFileEntity current = findCurrent(appId, appVersion, normalizedBuildId);
            if (current != null) {
                if (current.getSha256().equals(staged.sha256())) {
                    fileStore.discard(staged);
                    return new UploadResult(recordWriter.reaffirm(appId, current.getSymbolId(), current.getRevision()), false);
                }
                throw new SymbolConflictException(current.metadata());
            }
            storageKey = fileStore.publish(staged);
            try {
                SymbolFileMetadata created = recordWriter.create(appId, userId, appVersion, normalizedBuildId, fileName(file),
                        staged, storageKey);
                log.info("版本 mapping 已保存：默认范围={} 文件修订={}", normalizedBuildId == null, created.revision());
                return new UploadResult(created, true);
            } catch (DataIntegrityViolationException ex) {
                fileStore.retire(storageKey);
                SymbolFileEntity concurrent = findCurrent(appId, appVersion, normalizedBuildId);
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
            if (!"MAPPING".equals(current.getSymbolType()) || current.getAppVersion() == null) {
                throw new SymbolValidationException("SYMBOL_TYPE_MISMATCH", "仅版本化 mapping 可通过此接口替换", 422);
            }
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
            log.info("版本 mapping 替换已完成：文件修订={}", replaced.metadata().revision());
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

    /** 有界存在性查询，存储失败不能伪装成没有文件。 */
    public boolean hasVersion(UUID appId, String appVersion) {
        try { return fileRepository.existsByAppIdAndAppVersionAndSymbolTypeAndDeletedAtIsNull(appId, appVersion, "MAPPING"); }
        catch (DataAccessException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 活动行读锁覆盖文件租约登记，删除和替换不能让新读取者取得退休文件。 */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Optional<com.shanshui.apmserver.symbol.api.SymbolFileLease> acquire(UUID appId, String buildId) {
        return fileRepository.lockActiveBuild(appId, validateBuildId(buildId)).map(fileStore::open);
    }

    /** 只匹配同一应用版本的专用或默认 mapping，文件不可读时不回退其他来源。 */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Optional<com.shanshui.apmserver.symbol.api.SymbolFileLease> acquireVersioned(
            UUID appId, String appVersion, String buildId) {
        // 事件必须携带构建；只有 mapping 登记允许省略构建以声明版本默认范围。
        if (buildId == null || buildId.isBlank()) {
            throw new SymbolValidationException("INVALID_BUILD_ID", "事件构建标识不能为空", 400);
        }
        /** 原始身份的格式问题；不能裁剪或转换为另一个登记。 */
        var issues = new java.util.ArrayList<com.shanshui.apmserver.telemetry.api.ValidationIssue>();
        com.shanshui.apmserver.telemetry.api.ApplicationBuildIdentity.validate(appVersion, buildId, issues);
        if (!issues.isEmpty()) throw new SymbolValidationException("INVALID_IDENTITY", "应用版本或构建标识无效", 400);
        try {
            /** 一次数据库选优并锁定实际来源。 */
            var rows = fileRepository.lockPreferredMapping(appId, appVersion, buildId,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            /** 文件租约覆盖本次实际读取生命周期。 */
            var result = rows.stream().findFirst().map(fileStore::open);
            log.info("版本 mapping 选择完成：找到来源={}", result.isPresent());
            return result;
        } catch (DataAccessException failure) {
            log.warn("版本 mapping 选择失败：数据库不可用");
            throw new SymbolStoreUnavailableException(failure);
        } catch (SymbolStoreUnavailableException failure) {
            log.warn("版本 mapping 选择失败：固定来源不可读");
            throw failure;
        }
    }

    /** 先提交软删除，再按已有读取租约退休文件。 */
    public void delete(UUID appId, UUID symbolId, UUID userId, int expectedRevision) {
        if (expectedRevision < 1) throw new SymbolValidationException("INVALID_REVISION", "expectedRevision 必须为正数", 400);
        try {
            fileStore.retire(recordWriter.delete(appId, symbolId, userId, expectedRevision));
            log.info("符号文件条件删除已完成，来源与审计已保留");
        }
        catch (DataAccessException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 进度仅查询当前符号表及对应构建任务，删除身份不再提供轮询。 */
    public com.shanshui.apmserver.crash.api.CrashSymbolicationWork.Progress progress(UUID appId, UUID symbolId,
            com.shanshui.apmserver.crash.api.CrashSymbolicationWork work) {
        SymbolFileEntity current = findById(appId, symbolId);
        if (!"MAPPING".equals(current.getSymbolType()) || current.getAppVersion() == null) {
            throw new SymbolValidationException("SYMBOL_PROGRESS_NOT_APPLICABLE", "此登记不适用 JVM 还原进度", 422);
        }
        return work.progress(appId, current.getAppVersion());
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
    private SymbolFileEntity findCurrent(UUID appId, String appVersion, String buildId) {
        try {
            return fileRepository.versionMapping(appId, appVersion, buildId).orElse(null);
        } catch (DataAccessException ex) {
            throw new SymbolStoreUnavailableException(ex);
        }
    }

    /** 校验原文字面身份，禁止裁剪后匹配另一资源。 */
    public static String validateIdentity(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || Character.isWhitespace(value.codePointAt(0)) || Character.isSpaceChar(value.codePointAt(0))
                || Character.isWhitespace(value.codePointBefore(value.length())) || Character.isSpaceChar(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new SymbolValidationException("appVersion".equals(field) ? "INVALID_APP_VERSION" : "INVALID_BUILD_ID",
                    field + " 格式无效", 400);
        }
        return value;
    }

    /** 非空事件构建或筛选沿用相同原文约束。 */
    private String validateBuildId(String value) { return validateIdentity(value, "buildId", 256); }

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
