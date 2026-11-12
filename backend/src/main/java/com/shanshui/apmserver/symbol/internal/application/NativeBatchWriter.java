package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.domain.SymbolAuditEntity;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 单事务确认完整 SO 批次，包含预期不存在的身份竞争。 */
@Service
public class NativeBatchWriter {
    /** 管理库短锁仅保护同应用版本的 SO，不触发 JVM 工作。 */
    private final JdbcTemplate jdbc;
    /** 当前文件身份及审计使用同一事务。 */
    private final SymbolFileRepository files;
    /** 追加式审计。 */
    private final SymbolAuditRepository audits;
    /** 注入共享管理库事务资源。 */
    public NativeBatchWriter(JdbcTemplate jdbc, SymbolFileRepository files, SymbolAuditRepository audits) {
        this.jdbc = jdbc; this.files = files; this.audits = audits;
    }
    /** 所有预期条件校验完成后才写入，任一冲突整体回滚。 */
    @Transactional
    public Commit commit(UUID app, UUID user, String version, List<NativeBatch.Candidate> candidates,
                                     List<NativeBatch.Expected> expected, long deadline) {
        /** 数据库等待使用本次剩余期限，不能在归档预算耗尽后无限等待锁。 */
        checkDeadline(deadline);
        // 同一版本的缺失身份也参与锁定；数据库局部唯一索引是最终兜底。
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, app + "/" + version);
        /** 有序候选对应的全部当前状态。 */
        var current = new ArrayList<SymbolFileEntity>();
        /** 对话快照包含每个不存在项。 */
        var snapshot = new ArrayList<com.shanshui.apmserver.symbol.api.NativeBatchConflictException.Entry>();
        for (var candidate : candidates) {
            checkDeadline(deadline);
            var entity = files.nativeIdentity(app, version, candidate.elf().abi(), candidate.elf().elfBuildId()).orElse(null);
            current.add(entity);
            snapshot.add(new com.shanshui.apmserver.symbol.api.NativeBatchConflictException.Entry(candidate.elf().abi(), candidate.elf().elfBuildId(), candidate.staged().sha256(),
                    entity == null ? null : entity.metadata()));
        }
        /** 确认集合必须恰好等于实际内容集合，不允许漏项或重复。 */
        var confirmations = new HashMap<String, NativeBatch.Expected>();
        if (expected != null) {
            for (var entry : expected) {
                if (entry == null || (entry.symbolId() == null) != (entry.revision() == null)
                        || (entry.revision() != null && entry.revision() < 1)
                        || confirmations.put(entry.abi() + "/" + entry.elfBuildId(), entry) != null) {
                    throw new SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "确认身份或修订无效", 400);
                }
            }
            if (confirmations.size() != candidates.size()) throw new SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "确认必须包含全部候选", 400);
        }
        for (int index = 0; index < candidates.size(); index++) {
            checkDeadline(deadline);
            var candidate = candidates.get(index);
            var entity = current.get(index);
            if (expected == null) {
                if (entity != null && !entity.getSha256().equals(candidate.staged().sha256())) throw new com.shanshui.apmserver.symbol.api.NativeBatchConflictException(snapshot);
            } else {
                var condition = confirmations.get(candidate.elf().abi() + "/" + candidate.elf().elfBuildId());
                if (condition == null) throw new SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "确认身份与归档不一致", 400);
                if (entity == null ? condition.symbolId() != null : !entity.getSymbolId().equals(condition.symbolId())
                        || !Integer.valueOf(entity.getRevision()).equals(condition.revision())) throw new com.shanshui.apmserver.symbol.api.NativeBatchConflictException(snapshot);
            }
        }
        /** 当前结果及三类动作数量。 */
        var items = new ArrayList<com.shanshui.apmserver.symbol.api.SymbolFileMetadata>();
        /** 成功提交后才允许回收旧文件。 */
        var retired = new ArrayList<String>();
        int created = 0, replaced = 0, unchanged = 0;
        for (int index = 0; index < candidates.size(); index++) {
            checkDeadline(deadline);
            var candidate = candidates.get(index);
            var entity = current.get(index);
            if (entity != null && entity.getSha256().equals(candidate.staged().sha256())) {
                unchanged++; items.add(entity.metadata()); continue;
            }
            /** 每项审计固定更新前来源。 */
            Integer oldRevision = entity == null ? null : entity.getRevision();
            String oldSha = entity == null ? null : entity.getSha256();
            Instant now = Instant.now();
            if (entity == null) {
                entity = new SymbolFileEntity(UUID.randomUUID(), app, null, 1, candidate.key(), candidate.name(),
                        candidate.staged().sizeBytes(), candidate.staged().sha256(), user, now, now);
                entity.identify("NATIVE_SO", version, candidate.elf().abi(), candidate.elf().elfBuildId(),
                        candidate.elf().functionSymbols(), candidate.elf().debugInfo(), candidate.elf().debugLine());
                created++;
            } else {
                retired.add(entity.getStorageKey());
                entity.replace(candidate.key(), candidate.name(), candidate.staged().sizeBytes(), candidate.staged().sha256(), user, now);
                entity.identify("NATIVE_SO", version, candidate.elf().abi(), candidate.elf().elfBuildId(),
                        candidate.elf().functionSymbols(), candidate.elf().debugInfo(), candidate.elf().debugLine());
                replaced++;
            }
            files.saveAndFlush(entity);
            var audit = new SymbolAuditEntity(UUID.randomUUID(), entity.getSymbolId(), app, null, oldRevision, oldSha,
                    entity.getRevision(), entity.getSha256(), user, now, "UPSERT");
            audit.identify(entity); audits.saveAndFlush(audit); items.add(entity.metadata());
        }
        return new Commit(new NativeBatch.Result(List.copyOf(items), created, replaced, unchanged), List.copyOf(retired));
    }
    /** 短事务各阶段复核统一期限，超时使整批回滚。 */
    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            throw new com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException("SO 批次处理期限已到");
        }
        /** 每个阶段重新缩短数据库等待时间，不能为最后一条语句重新取得完整期限。 */
        String milliseconds = Long.toString(Math.max(1, (deadline-System.nanoTime())/1_000_000));
        jdbc.queryForMap("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)", milliseconds, milliseconds);
    }

    /** 返回已提交结果与安全退休的旧键。 */
    public record Commit(/** 对外元数据。 */ NativeBatch.Result result, /** 已无引用旧键。 */ List<String> retiredKeys) {}
}
