package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolFileMetadata;
import com.shanshui.apmserver.symbol.api.SymbolVersionConflictException;
import com.shanshui.apmserver.symbol.internal.domain.SymbolAuditEntity;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolAuditRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 在单个数据库事务内写入当前符号表和替换审计。 */
@Service
public class SymbolRecordWriter {

    /** 当前版本仓库。 */
    private final SymbolFileRepository fileRepository;
    /** 替换审计仓库。 */
    private final SymbolAuditRepository auditRepository;

    /** 与元数据原子登记后台工作，不执行任何遥测查询。 */
    private final com.shanshui.apmserver.crash.api.CrashSymbolicationWork work;

    /** 注入符号表写入依赖。 */
    public SymbolRecordWriter(SymbolFileRepository fileRepository, SymbolAuditRepository auditRepository, com.shanshui.apmserver.crash.api.CrashSymbolicationWork work) {
        this.fileRepository = fileRepository;
        this.auditRepository = auditRepository;
        this.work = work;
    }

    /** 原子创建当前版本和首次上传审计。 */
    @Transactional
    public SymbolFileMetadata create(UUID appId, UUID userId, String buildId, String filename,
                                     SymbolFileStore.StagedFile staged, String storageKey) {
        Instant now = Instant.now();
        SymbolFileEntity entity = new SymbolFileEntity(UUID.randomUUID(), appId, buildId, 1, storageKey,
                filename, staged.sizeBytes(), staged.sha256(), userId, now, now);
        fileRepository.saveAndFlush(entity);
        auditRepository.saveAndFlush(new SymbolAuditEntity(UUID.randomUUID(), entity.getSymbolId(), appId, buildId,
                null, null, entity.getRevision(), entity.getSha256(), userId, now, "UPSERT"));
        work.wake(appId, buildId);
        return entity.metadata();
    }

    /** 按 expectedRevision 原子替换当前版本并记录审计。 */
    @Transactional
    public Replacement replace(UUID appId, UUID symbolId, UUID userId, int expectedRevision,
                               String filename, SymbolFileStore.StagedFile staged, String storageKey) {
        SymbolFileEntity current = fileRepository.findById(symbolId)
                .filter(value -> value.getAppId().equals(appId) && value.getDeletedAt() == null)
                .orElseThrow(() -> new com.shanshui.apmserver.symbol.api.SymbolValidationException(
                        "SYMBOL_NOT_FOUND", "符号表不存在", 404));
        if (current.getRevision() != expectedRevision) {
            throw new SymbolVersionConflictException(current.metadata());
        }
        int oldRevision = current.getRevision();
        String oldSha256 = current.getSha256();
        int changed = fileRepository.replaceIfRevision(symbolId, expectedRevision, expectedRevision + 1,
                storageKey, filename, staged.sizeBytes(), staged.sha256(), userId, Instant.now());
        if (changed != 1) {
            SymbolFileEntity latest = fileRepository.findById(symbolId)
                    .orElseThrow(() -> new com.shanshui.apmserver.symbol.api.SymbolValidationException(
                            "SYMBOL_NOT_FOUND", "符号表不存在", 404));
            throw new SymbolVersionConflictException(latest.metadata());
        }
        SymbolFileEntity replaced = fileRepository.findById(symbolId).orElseThrow();
        auditRepository.saveAndFlush(new SymbolAuditEntity(UUID.randomUUID(), symbolId, appId,
                replaced.getBuildId(), oldRevision, oldSha256, replaced.getRevision(), replaced.getSha256(),
                userId, replaced.getUpdatedAt(), "UPSERT"));
        work.wake(appId, replaced.getBuildId());
        return new Replacement(replaced.metadata(), current.getStorageKey());
    }

    /** 等内容请求也必须在同一事务中确认活动版本并可靠登记工作。 */
    @Transactional
    public SymbolFileMetadata reaffirm(UUID appId, UUID symbolId, int expectedRevision) {
        SymbolFileEntity current = fileRepository.lockActiveId(appId, symbolId)
                .orElseThrow(() -> new com.shanshui.apmserver.symbol.api.SymbolValidationException("SYMBOL_NOT_FOUND", "符号表不存在", 404));
        if (current.getRevision() != expectedRevision) throw new SymbolVersionConflictException(current.metadata());
        work.wake(appId, current.getBuildId());
        return current.metadata();
    }

    /** 软删除及删除审计共同提交，提交后调用方才退休文件。 */
    @Transactional
    public String delete(UUID appId, UUID symbolId, UUID userId, int expectedRevision) {
        SymbolFileEntity current = fileRepository.findById(symbolId)
                .filter(value -> value.getAppId().equals(appId) && value.getDeletedAt() == null)
                .orElseThrow(() -> new com.shanshui.apmserver.symbol.api.SymbolValidationException("SYMBOL_NOT_FOUND", "符号表不存在", 404));
        if (current.getRevision() != expectedRevision) throw new SymbolVersionConflictException(current.metadata());
        String storageKey = current.getStorageKey();
        Instant now = Instant.now();
        if (fileRepository.deleteIfRevision(symbolId, expectedRevision, now) != 1) {
            SymbolFileEntity latest = fileRepository.findById(symbolId).filter(value -> value.getDeletedAt() == null)
                    .orElseThrow(() -> new com.shanshui.apmserver.symbol.api.SymbolValidationException("SYMBOL_NOT_FOUND", "符号表不存在", 404));
            throw new SymbolVersionConflictException(latest.metadata());
        }
        auditRepository.saveAndFlush(new SymbolAuditEntity(UUID.randomUUID(), symbolId, appId, current.getBuildId(),
                current.getRevision(), current.getSha256(), current.getRevision(), current.getSha256(), userId, now, "DELETE"));
        work.wake(appId, current.getBuildId());
        return storageKey;
    }

    /** 当前版本写入结果及被替换的旧文件键。 */
    public record Replacement(SymbolFileMetadata metadata, String oldStorageKey) {
    }
}
