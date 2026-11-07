package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashResultTooLargeException;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 唯一首次成功登记与跨库发布恢复，完整载荷仅保留到读回确认。 */
@Repository
public class CrashSymbolicationPublications {
    /** 管理数据库参数化访问。 */
    private final JdbcTemplate jdbc;
    /** 租约和登记共享的事务门禁。 */
    private final CrashSymbolicationJobs jobs;
    /** 当前遥测状态边界。 */
    private final CrashStatePort states;
    /** 确定的当前模型编解码器。 */
    private final CrashStateJson codec;
    /** 短事务管理。 */
    private final TransactionTemplate transaction;
    /** 最多未发布结果条数。 */
    private final int maxPending;
    /** 未发布完整载荷总字节上限。 */
    private final long maxPendingBytes;

    /** 注入数据库、状态存储与有限背压参数。 */
    public CrashSymbolicationPublications(JdbcTemplate jdbc, CrashSymbolicationJobs jobs, CrashStatePort states,
            CrashStateJson codec, PlatformTransactionManager manager,
            @Value("${apm.crash.background.max-pending-publications:100}") int maxPending,
            @Value("${apm.crash.background.max-pending-bytes:16777216}") long maxPendingBytes) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.states = states;
        this.codec = codec;
        this.transaction = new TransactionTemplate(manager);
        this.maxPending = maxPending;
        this.maxPendingBytes = maxPendingBytes;
        if (maxPending < 1 || maxPending > 1000 || maxPendingBytes < 1 || maxPendingBytes > 268435456L) {
            throw new IllegalArgumentException("后台在途发布上限不合法");
        }
    }

    /** 在任何重新计算前检查首次成功是否已确定。 */
    public Optional<Publication> existing(UUID appId, String eventId) {
        return jdbc.query("SELECT * FROM crash_symbolication_publication WHERE app_id=? AND event_id=?",
                row(), appId, eventId).stream().findFirst();
    }

    /** 成功决定是一次短事务；之后只能使用这份版本与字节。 */
    public Optional<Publication> register(CrashSymbolicationJobs.Lease lease, CrashState computed) {
        if (!"symbolicated".equals(computed.status()) || !lease.appId().equals(computed.appId())
                || !lease.buildId().equals(computed.buildId())) throw new IllegalArgumentException("成功结果与构建租约不一致");
        return transaction.execute(status -> {
            if (!jobs.lockOwned(lease)) return Optional.empty();
            // 已决定的首次成功优先，mapping 变化和重试不能覆盖。
            Optional<Publication> previous = existing(computed.appId(), computed.eventId());
            if (previous.isPresent()) return previous;
            if (!computed.eventTime().plus(Duration.ofDays(30)).isAfter(Instant.now())) return Optional.empty();
            // 单实例之外，数据库短锁也串行化全局在途预算的检查与登记。
            jdbc.execute("SELECT pg_advisory_xact_lock(1605315301)");
            Long count = jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_publication WHERE published_at IS NULL", Long.class);
            Long bytes = jdbc.queryForObject("SELECT coalesce(sum(octet_length(pending_payload)),0) FROM crash_symbolication_publication WHERE published_at IS NULL", Long.class);
            long version = nextVersion();
            CrashState fixed = versioned(computed, version);
            String payload = codec.encode(fixed);
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > 2097152) throw new CrashResultTooLargeException();
            if (count >= maxPending || bytes + payloadBytes > maxPendingBytes) {
                throw new EventStoreUnavailableException("后台发布积压达到上限");
            }
            String sha256 = digest(payload);
            jdbc.update("""
                    INSERT INTO crash_symbolication_publication(app_id,event_id,build_id,event_time,expires_at,
                        state_version,symbol_id,symbol_revision,symbol_sha256,result_sha256,pending_payload)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, fixed.appId(), fixed.eventId(), fixed.buildId(), java.sql.Timestamp.from(fixed.eventTime()),
                    java.sql.Timestamp.from(fixed.eventTime().plus(Duration.ofDays(30))), version, fixed.symbolId(), fixed.symbolRevision(),
                    fixed.symbolSha256(), sha256, payload);
            return existing(fixed.appId(), fixed.eventId());
        });
    }

    /** 失败版本必须在成功决定之前分配，迟到失败自然低于成功版本。 */
    public Optional<Long> failureVersion(CrashSymbolicationJobs.Lease lease, String eventId) {
        return transaction.execute(status -> {
            if (!jobs.lockOwned(lease) || existing(lease.appId(), eventId).isPresent()) return Optional.empty();
            // 记录已知的处理失败尝试，与是否收到 ClickHouse 写回确认分别解释。
            long version = nextVersion();
            if (!jobs.count(lease, false)) throw new EventStoreUnavailableException("构建失败进度未确认");
            return Optional.of(version);
        });
    }

    /** 当前构建优先恢复有限未确认结果，不重新调用 R8。 */
    public List<Publication> pending(CrashSymbolicationJobs.Lease lease, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("发布恢复批次超限");
        return jdbc.query("SELECT * FROM crash_symbolication_publication WHERE app_id=? AND build_id=? AND published_at IS NULL AND expires_at>clock_timestamp() ORDER BY created_at,event_id LIMIT ?",
                row(), lease.appId(), lease.buildId(), limit);
    }

    /** 写入和确认丢失时重放登记原字节；读回整份内容一致后才移除短期载荷。 */
    public boolean publish(CrashSymbolicationJobs.Lease lease, Publication publication) {
        if (!lease.appId().equals(publication.appId()) || !lease.buildId().equals(publication.buildId())) {
            throw new IllegalArgumentException("发布与构建租约不一致");
        }
        if (publication.payload() == null) return true;
        if (!publication.expiresAt().isAfter(Instant.now()) || !jobs.renew(lease, Duration.ofMinutes(1))) return false;
        if (!digest(publication.payload()).equals(publication.sha256())) throw new IllegalStateException("待发布载荷摘要不一致");
        states.replay(publication.payload());
        // 同一读取返回完整最新状态，不能把新指纹和旧堆栈拼成成功。
        Optional<CrashState> current = states.current(publication.appId(), publication.eventId());
        if (current.isEmpty() || current.get().stateVersion() != publication.version()
                || !digest(codec.encode(current.get())).equals(publication.sha256())) {
            throw new EventStoreUnavailableException("后台发布尚未读回确认");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!jobs.lockOwned(lease)) return false;
            int confirmed = jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=NULL,published_at=clock_timestamp() WHERE app_id=? AND event_id=? AND result_sha256=? AND published_at IS NULL",
                    publication.appId(), publication.eventId(), publication.sha256());
            if (confirmed == 1 && !jobs.count(lease, true)) throw new EventStoreUnavailableException("构建成功进度未确认");
            return confirmed == 1;
        }));
    }

    /** 仅清理已过期并超过最大在途租约宽限的记录，每次上限固定。 */
    public int cleanupExpired() {
        return jdbc.update("""
                WITH expired AS (
                  SELECT app_id,event_id FROM crash_symbolication_publication
                  WHERE expires_at < clock_timestamp()-interval '5 minutes'
                  ORDER BY expires_at LIMIT 1000
                ) DELETE FROM crash_symbolication_publication AS p USING expired AS e
                WHERE p.app_id=e.app_id AND p.event_id=e.event_id
                """);
    }

    /** 版本来自独立序列，事务回滚留空号但绝不倒退。 */
    private long nextVersion() { return jdbc.queryForObject("SELECT nextval('crash_symbolication_state_version')", Long.class); }

    /** 将一次计算结果绑定已分配的逻辑版本。 */
    public static CrashState versioned(CrashState state, long version) {
        return new CrashState(state.appId(), state.eventId(), state.buildId(), state.eventTime(), version,
                state.status(), state.reason(), state.stackFormat(), state.stackPayload(), state.exceptionType(), state.fingerprint(),
                state.fingerprintVersion(), state.groupingKind(), state.symbolId(), state.symbolRevision(), state.symbolSha256(), state.processedAt(), state.retryAt());
    }

    /** 读取发布投影，已确认记录不再包含完整堆栈。 */
    private RowMapper<Publication> row() {
        return (row, number) -> new Publication(row.getObject("app_id", UUID.class), row.getString("event_id"),
                row.getString("build_id"), row.getLong("state_version"), row.getString("result_sha256"),
                row.getString("pending_payload"), row.getTimestamp("expires_at").toInstant());
    }

    /** 对登记原字节计算摘要，不记录任何堆栈内容。 */
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 不可用", failure); }
    }

    /** 发布器只持有有限在途载荷，确认后该字段为空。 */
    public record Publication(
            /** 所属应用。 */ UUID appId,
            /** 原事件身份。 */ String eventId,
            /** 固定构建。 */ String buildId,
            /** 首次成功版本。 */ long version,
            /** 完整状态字节摘要。 */ String sha256,
            /** 已登记 JSONEachRow 原字节。 */ String payload,
            /** 原事件到期时间。 */ Instant expiresAt) {}
}
