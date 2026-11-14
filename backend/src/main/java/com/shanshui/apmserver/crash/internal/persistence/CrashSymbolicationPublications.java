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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /** 仅记录发布阶段与版本，不输出堆栈或载荷。 */
    private static final Logger LOG = LoggerFactory.getLogger(CrashSymbolicationPublications.class);
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
        if (!"jvm".equals(lease.crashKind()) || !"symbolicated".equals(computed.status()) || !lease.appId().equals(computed.appId())
                || computed.buildId() == null || computed.buildId().isBlank()) throw new IllegalArgumentException("成功结果与构建租约不一致");
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
            if (payloadBytes > 4194304) throw new CrashResultTooLargeException();
            if (count >= maxPending || bytes + payloadBytes > maxPendingBytes) {
                throw new EventStoreUnavailableException("后台发布积压达到上限");
            }
            String sha256 = digest(payload);
            jdbc.update("""
                    INSERT INTO crash_symbolication_publication(app_id,event_id,build_id,app_version,event_time,expires_at,
                        state_version,symbol_id,symbol_revision,symbol_sha256,result_sha256,pending_payload)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, fixed.appId(), fixed.eventId(), fixed.buildId(), lease.appVersion(), java.sql.Timestamp.from(fixed.eventTime()),
                    java.sql.Timestamp.from(fixed.eventTime().plus(Duration.ofDays(30))), version, fixed.symbolId(), fixed.symbolRevision(),
                    fixed.symbolSha256(), sha256, payload);
            return existing(fixed.appId(), fixed.eventId());
        });
    }

    /** 登记 Native 完整增量；未确认快照优先，确认后才允许覆盖账本。 */
    public Optional<Publication> registerNative(CrashSymbolicationJobs.Lease lease, CrashState base,
                                                 CrashState candidate, long failedUnits) {
        if (!"native".equals(lease.crashKind()) || !lease.appId().equals(base.appId())
                || !base.appId().equals(candidate.appId()) || !base.eventId().equals(candidate.eventId())
                || failedUnits < 0) throw new IllegalArgumentException("Native 发布与工作租约不一致");
        // 在事务外读取遥测状态；管理库内再检查账本版本与租约。
        Optional<CrashState> current = states.current(base.appId(), base.eventId());
        return transaction.execute(status -> {
            if (!jobs.lockOwned(lease)) return Optional.empty();
            Optional<Publication> previous = existing(base.appId(), base.eventId());
            if (previous.isPresent() && (!"native".equals(previous.get().crashKind())
                    || !lease.appVersion().equals(previous.get().appVersion()))) return Optional.empty();
            if (previous.isPresent() && previous.get().payload() != null) return previous;
            if (current.isEmpty() || !codec.matches(codec.encode(base), current.get())
                    || (previous.isEmpty() && (base.stateVersion() != 0 || !"raw_native".equals(base.stackFormat())))
                    || (previous.isPresent() && previous.get().version() != base.stateVersion())
                    || !base.eventTime().plus(Duration.ofDays(30)).isAfter(Instant.now())) return Optional.empty();
            codec.validateNativeTransition(base, candidate);
            if (base.equals(candidate)) return Optional.empty();
            jdbc.execute("SELECT pg_advisory_xact_lock(1605315301)");
            Long count = jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_publication WHERE published_at IS NULL", Long.class);
            Long bytes = jdbc.queryForObject("SELECT coalesce(sum(octet_length(pending_payload)),0) FROM crash_symbolication_publication WHERE published_at IS NULL", Long.class);
            long version = nextVersion();
            if (version <= base.stateVersion()) throw new IllegalStateException("Native 状态序列落后于基础版本");
            CrashState fixed = versioned(candidate, version);
            String payload = codec.encode(fixed);
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > 4194304) throw new CrashResultTooLargeException();
            if (count >= maxPending || bytes + payloadBytes > maxPendingBytes) {
                throw new EventStoreUnavailableException("后台发布积压达到上限");
            }
            int registered = jdbc.update("""
                    INSERT INTO crash_symbolication_publication(app_id,event_id,build_id,app_version,event_time,expires_at,
                        state_version,result_sha256,pending_payload,crash_kind,base_state_version,native_failed_unit_count)
                    VALUES (?,?,?,?,?,?,?,?,?,'native',?,?)
                    ON CONFLICT (app_id,event_id) DO UPDATE SET state_version=EXCLUDED.state_version,
                        result_sha256=EXCLUDED.result_sha256,pending_payload=EXCLUDED.pending_payload,
                        base_state_version=EXCLUDED.base_state_version,native_failed_unit_count=EXCLUDED.native_failed_unit_count,
                        published_at=NULL,created_at=clock_timestamp()
                    WHERE crash_symbolication_publication.crash_kind='native'
                      AND crash_symbolication_publication.published_at IS NOT NULL
                      AND crash_symbolication_publication.state_version=EXCLUDED.base_state_version
                    """, fixed.appId(), fixed.eventId(), fixed.buildId(), lease.appVersion(), java.sql.Timestamp.from(fixed.eventTime()),
                    java.sql.Timestamp.from(fixed.eventTime().plus(Duration.ofDays(30))), version, digest(payload), payload,
                    base.stateVersion(), failedUnits);
            if (registered != 1) return Optional.empty();
            LOG.info("Native 快照已登记，基础版本={}，发布版本={}，载荷字节={}", base.stateVersion(), version, payloadBytes);
            return existing(fixed.appId(), fixed.eventId());
        });
    }

    /** 失败版本必须在成功决定之前分配，迟到失败自然低于成功版本。 */
    public Optional<Long> failureVersion(CrashSymbolicationJobs.Lease lease, String eventId) {
        if (!"jvm".equals(lease.crashKind())) throw new IllegalArgumentException("Native 失败必须走完整快照发布");
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
        if (!"jvm".equals(lease.crashKind())) throw new IllegalArgumentException("工作类型不一致");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("发布恢复批次超限");
        return jdbc.query("SELECT * FROM crash_symbolication_publication WHERE app_id=? AND app_version=? AND crash_kind='jvm' AND published_at IS NULL AND expires_at>clock_timestamp() ORDER BY created_at,event_id LIMIT ?",
                row(), lease.appId(), lease.appVersion(), limit);
    }

    /** Native 执行器先恢复同一版本的未确认载荷。 */
    public List<Publication> pendingNative(CrashSymbolicationJobs.Lease lease, int limit) {
        if (!"native".equals(lease.crashKind()) || limit < 1 || limit > 100) throw new IllegalArgumentException("发布恢复条件不合法");
        return jdbc.query("SELECT * FROM crash_symbolication_publication WHERE app_id=? AND app_version=? AND crash_kind='native' AND published_at IS NULL AND expires_at>clock_timestamp() ORDER BY created_at,event_id LIMIT ?",
                row(), lease.appId(), lease.appVersion(), limit);
    }

    /** 写入和确认丢失时重放登记原字节；读回整份内容一致后才移除短期载荷。 */
    public boolean publish(CrashSymbolicationJobs.Lease lease, Publication publication) {
        if (!lease.appId().equals(publication.appId()) || !lease.appVersion().equals(publication.appVersion())
                || !lease.crashKind().equals(publication.crashKind())) {
            throw new IllegalArgumentException("发布与构建租约不一致");
        }
        if (publication.payload() == null) return true;
        if (!publication.expiresAt().isAfter(Instant.now()) || !jobs.renew(lease, Duration.ofMinutes(1))) return false;
        if (!digest(publication.payload()).equals(publication.sha256())) throw new IllegalStateException("待发布载荷摘要不一致");
        states.replay(publication.payload());
        // 同一读取返回完整最新状态，不能把新指纹和旧堆栈拼成成功。
        Optional<CrashState> current = states.current(publication.appId(), publication.eventId());
        if (current.isEmpty() || current.get().stateVersion() != publication.version()
                || !codec.matches(publication.payload(), current.get())) {
            throw new EventStoreUnavailableException("后台发布尚未读回确认");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!jobs.lockOwned(lease)) return false;
            List<Long> confirmed = jdbc.query("UPDATE crash_symbolication_publication SET pending_payload=NULL,published_at=clock_timestamp() WHERE app_id=? AND event_id=? AND state_version=? AND result_sha256=? AND crash_kind=? AND published_at IS NULL RETURNING native_failed_unit_count",
                    (row, number) -> row.getLong(1), publication.appId(), publication.eventId(), publication.version(), publication.sha256(), publication.crashKind());
            if (!confirmed.isEmpty() && !("native".equals(publication.crashKind())
                    ? jobs.countNative(lease, 1, confirmed.getFirst()) : jobs.count(lease, true))) {
                throw new EventStoreUnavailableException("构建成功进度未确认");
            }
            if (!confirmed.isEmpty()) LOG.info("后台快照已读回确认，类型={}，版本={}", publication.crashKind(), publication.version());
            return !confirmed.isEmpty();
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
                state.fingerprintVersion(), state.groupingKind(), state.symbolId(), state.symbolRevision(), state.symbolSha256(), state.processedAt(), state.retryAt(),
                state.captureSha256(), state.threadStacksPayload(), state.nativeWorkRemaining());
    }

    /** 读取发布投影，已确认记录不再包含完整堆栈。 */
    private RowMapper<Publication> row() {
        return (row, number) -> new Publication(row.getObject("app_id", UUID.class), row.getString("event_id"),
                row.getString("build_id"), row.getString("app_version"), row.getLong("state_version"), row.getString("result_sha256"),
                row.getString("pending_payload"), row.getTimestamp("expires_at").toInstant(),
                row.getString("crash_kind"), row.getLong("native_failed_unit_count"));
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
            /** 发布所属应用版本，旧已确认记录为空。 */ String appVersion,
            /** 首次成功版本。 */ long version,
            /** 完整状态字节摘要。 */ String sha256,
            /** 已登记 JSONEachRow 原字节。 */ String payload,
            /** 原事件到期时间。 */ Instant expiresAt,
            /** JVM 或 Native 发布类型。 */ String crashKind,
            /** 本次确认后累计的新增失败单元数。 */ long failedUnits) {
        /** 保持已有 JVM 调用方的构造方式。 */
        public Publication(UUID appId, String eventId, String buildId, String appVersion, long version,
                           String sha256, String payload, Instant expiresAt) {
            this(appId, eventId, buildId, appVersion, version, sha256, payload, expiresAt, "jvm", 0);
        }
    }
}
