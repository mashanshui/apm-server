package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.api.CrashSymbolicationWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL 版本任务：独立唤醒代次、受限领取和租约门禁。 */
@Repository
public class CrashSymbolicationJobs implements CrashSymbolicationWork {
    /** 同一管理库的参数化查询。 */
    private final JdbcTemplate jdbc;
    /** 领取和门禁使用短事务。 */
    private final TransactionTemplate transaction;

    /** 注入与符号表共用的管理事务。 */
    public CrashSymbolicationJobs(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 重复通知合并为同一个任务；正在执行时只增加唤醒代次，不抢占旧租约。 */
    @Override
    @Transactional
    public void wake(UUID appId, String appVersion) {
        wakeKind(appId, appVersion, "jvm");
    }

    /** 合并同应用版本的 Native 工作通知。 */
    @Override
    @Transactional
    public void wakeNative(UUID appId, String appVersion) {
        wakeKind(appId, appVersion, "native");
    }

    /** 按 Crash 类型隔离通知和计数，依赖调用者的管理库事务。 */
    private void wakeKind(UUID appId, String appVersion, String kind) {
        jdbc.update("""
                INSERT INTO crash_symbolication_job(app_id,app_version,crash_kind) VALUES (?,?,?)
                ON CONFLICT (app_id,app_version,crash_kind) WHERE app_version IS NOT NULL DO UPDATE SET
                  wake_generation=crash_symbolication_job.wake_generation+1,
                  succeeded_count=CASE WHEN crash_symbolication_job.state IN ('idle','waiting_mapping')
                      AND crash_symbolication_job.lease_owner IS NULL THEN 0 ELSE crash_symbolication_job.succeeded_count END,
                  failed_count=CASE WHEN crash_symbolication_job.state IN ('idle','waiting_mapping')
                      AND crash_symbolication_job.lease_owner IS NULL THEN 0 ELSE crash_symbolication_job.failed_count END,
                  published_count=CASE WHEN crash_symbolication_job.state IN ('idle','waiting_symbols')
                      AND crash_symbolication_job.lease_owner IS NULL THEN 0 ELSE crash_symbolication_job.published_count END,
                  failed_unit_count=CASE WHEN crash_symbolication_job.state IN ('idle','waiting_symbols')
                      AND crash_symbolication_job.lease_owner IS NULL THEN 0 ELSE crash_symbolication_job.failed_unit_count END,
                  state=CASE WHEN crash_symbolication_job.lease_owner IS NOT NULL THEN crash_symbolication_job.state ELSE 'queued' END,
                  error_category=CASE WHEN crash_symbolication_job.lease_owner IS NULL THEN NULL ELSE crash_symbolication_job.error_category END,
                  next_run_at=clock_timestamp(),updated_at=clock_timestamp()
                """, appId, appVersion, kind);
    }

    /** 一个版本最多一条记录，轮询只查询管理库的主键。 */
    @Override
    @Transactional(readOnly = true)
    public Progress progress(UUID appId, String appVersion) {
        // 上传与登记任务是同一事务，活动符号表正常情况下总有对应记录。
        List<Progress> rows = jdbc.query("SELECT state,succeeded_count,failed_count,updated_at,error_category FROM crash_symbolication_job WHERE app_id=? AND app_version=? AND crash_kind='jvm'",
                (row, number) -> new Progress(row.getString("state"), row.getLong("succeeded_count"),
                        row.getLong("failed_count"), row.getTimestamp("updated_at").toInstant(), row.getString("error_category")), appId, appVersion);
        return rows.isEmpty() ? new Progress("idle", 0, 0, null, null) : rows.getFirst();
    }

    /** 返回同一版本 Native 处理的发布次数和失败单元次数。 */
    @Override
    @Transactional(readOnly = true)
    public NativeProgress nativeProgress(UUID appId, String appVersion) {
        var rows = jdbc.query("SELECT state,published_count,failed_unit_count,updated_at,error_category FROM crash_symbolication_job WHERE app_id=? AND app_version=? AND crash_kind='native'",
                (row, number) -> new NativeProgress("native", row.getString("state"), row.getLong("published_count"),
                        row.getLong("failed_unit_count"), row.getTimestamp("updated_at").toInstant(), row.getString("error_category")), appId, appVersion);
        return rows.isEmpty() ? new NativeProgress("native", "idle", 0, 0, null, null) : rows.getFirst();
    }

    /** 一次只领取一个版本，过期执行不能阻塞恢复。 */
    public Optional<Lease> claim(UUID owner, Duration duration) {
        return claimKind(owner, duration, "jvm");
    }

    /** 领取一个 Native 版本任务，仍与 JVM 共用全局单在途门禁。 */
    public Optional<Lease> claimNative(UUID owner, Duration duration) {
        return claimKind(owner, duration, "native");
    }

    /** 串行检查全局在途版本并领取指定类型任务。 */
    private Optional<Lease> claimKind(UUID owner, Duration duration, String kind) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("版本租约必须为五分钟内的正时长");
        }
        return transaction.execute(status -> {
            // 多类型工作共享短事务锁，不能同时领取两个版本。
            jdbc.execute("SELECT pg_advisory_xact_lock(1605315302)");
            Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crash_symbolication_job WHERE lease_owner IS NOT NULL AND lease_expires_at>clock_timestamp())", Boolean.class);
            if (Boolean.TRUE.equals(active)) return Optional.empty();
            // 有效 running 永不重领；SKIP LOCKED 避免一个管理事务拖住全部版本。
            List<Lease> rows = jdbc.query("""
                    WITH candidate AS (
                      SELECT job_id FROM crash_symbolication_job
                      WHERE app_version IS NOT NULL AND crash_kind=? AND ((state IN ('queued','retry_wait') AND next_run_at<=clock_timestamp() AND lease_owner IS NULL)
                         OR (state='running' AND lease_expires_at<=clock_timestamp()))
                      ORDER BY next_run_at,app_id,app_version FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE crash_symbolication_job AS job SET state='running',lease_generation=job.lease_generation+1,
                      claimed_wake_generation=job.wake_generation,lease_owner=?,
                      lease_expires_at=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp()
                    FROM candidate WHERE job.job_id=candidate.job_id
                    RETURNING job.app_id,job.app_version,job.lease_generation,job.claimed_wake_generation,job.scan_event_time,job.scan_event_id,job.job_id,job.error_category,job.crash_kind
                    """, (row, number) -> new Lease(row.getObject("app_id", UUID.class), row.getString("app_version"),
                    owner, row.getLong("lease_generation"), row.getLong("claimed_wake_generation"),
                    row.getTimestamp("scan_event_time") == null ? null : row.getTimestamp("scan_event_time").toInstant(),
                    row.getString("scan_event_id"), row.getObject("job_id", UUID.class), row.getString("error_category"), row.getString("crash_kind")), kind, owner, duration.toMillis());
            return rows.stream().findFirst();
        });
    }

    /** 续租和所有新工作均以数据库当前时间检查；失去租约不能重新复活。 */
    public boolean renew(Lease lease, Duration duration) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("版本租约时长不合法");
        }
        return jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp() WHERE "
                + owned(), duration.toMillis(), lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()) == 1;
    }

    /** 必须在结果登记的同一个短事务中调用，锁保护版本分配和首次成功决定。 */
    public boolean lockOwned(Lease lease) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("结果登记必须位于管理库事务中");
        }
        return !jdbc.query("SELECT app_id FROM crash_symbolication_job WHERE " + owned() + " FOR UPDATE",
                (row, number) -> row.getObject(1, UUID.class), lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()).isEmpty();
    }

    /** 单条发布/失败确认后增加本轮计数，不接受旧执行者更新。 */
    public boolean count(Lease lease, boolean succeeded) {
        if (!"jvm".equals(lease.crashKind())) throw new IllegalArgumentException("JVM 计数不能用于 Native 任务");
        return jdbc.update("UPDATE crash_symbolication_job SET succeeded_count=succeeded_count+?,failed_count=failed_count+?,updated_at=clock_timestamp() WHERE "
                + owned(), succeeded ? 1 : 0, succeeded ? 0 : 1, lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()) == 1;
    }

    /** 在确认事务内增加 Native 发布次数及新增失败单元次数。 */
    public boolean countNative(Lease lease, long published, long failedUnits) {
        if (!"native".equals(lease.crashKind()) || published < 0 || failedUnits < 0) throw new IllegalArgumentException("Native 计数不合法");
        return jdbc.update("UPDATE crash_symbolication_job SET published_count=published_count+?,failed_unit_count=failed_unit_count+?,updated_at=clock_timestamp() WHERE "
                + owned(), published, failedUnits, lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()) == 1;
    }

    /** 完成时存在更晚通知则重新排队；不能把运行期间的唤醒吞掉。 */
    public boolean release(Lease lease, String state, String error, Duration delay) {
        if (!("native".equals(lease.crashKind()) ? Set.of("queued", "waiting_symbols", "retry_wait", "idle")
                : Set.of("queued", "waiting_mapping", "retry_wait", "idle")).contains(state) || delay.isNegative()) {
            throw new IllegalArgumentException("版本释放状态不合法");
        }
        return jdbc.update("""
                UPDATE crash_symbolication_job SET
                  state=CASE WHEN wake_generation>claimed_wake_generation THEN 'queued' ELSE ? END,
                  next_run_at=CASE WHEN wake_generation>claimed_wake_generation THEN clock_timestamp()
                      ELSE clock_timestamp()+(? * interval '1 millisecond') END,
                  lease_owner=NULL,lease_expires_at=NULL,error_category=?,updated_at=clock_timestamp()
                WHERE """ + " " + owned(), state, delay.toMillis(), error, lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()) == 1;
    }

    /** 安全完成一页后才推进；空页回到开头，普通唤醒不重置游标。 */
    public boolean advance(Lease lease, Instant time, String eventId) {
        return jdbc.update("UPDATE crash_symbolication_job SET scan_event_time=?,scan_event_id=? WHERE " + owned(),
                time == null ? null : java.sql.Timestamp.from(time), eventId,
                lease.appId(), lease.appVersion(), lease.owner(), lease.generation(), lease.jobId(), lease.crashKind()) == 1;
    }

    /** 所有写回共享的有效执行身份条件。 */
    private String owned() {
        return "app_id=? AND app_version=? AND lease_owner=? AND lease_generation=? AND state='running' AND lease_expires_at>clock_timestamp() AND job_id=? AND crash_kind=?";
    }

    /** 长期无通知且未持有租约/发布载荷的历史任务有界清理，后续 mapping 通知可重建。 */
    public int cleanupExpired() {
        return jdbc.update("""
                WITH expired AS (
                  SELECT j.job_id FROM crash_symbolication_job j
                  WHERE j.app_version IS NOT NULL AND j.updated_at<clock_timestamp()-interval '35 days' AND j.lease_owner IS NULL
                    AND NOT EXISTS (SELECT 1 FROM crash_symbolication_publication p
                        WHERE p.app_id=j.app_id AND p.app_version=j.app_version AND p.crash_kind=j.crash_kind)
                  ORDER BY j.updated_at LIMIT 1000
                ) DELETE FROM crash_symbolication_job j USING expired e
                WHERE j.job_id=e.job_id
                """);
    }

    /** 领取时固定的执行身份，不包含任何文件或堆栈。 */
    public record Lease(
            /** 当前应用。 */ UUID appId,
            /** 当前版本。 */ String appVersion,
            /** 进程执行者。 */ UUID owner,
            /** 领取代次。 */ long generation,
            /** 已消费唤醒代次。 */ long wakeGeneration,
            /** 轻量候选轮转位置。 */ Instant scanTime,
            /** 同毫秒事件位置。 */ String scanEventId,
            /** 不因清理和重建复用的稳定身份。 */ UUID jobId,
            /** 前页安全延期原因，空页不能伪报已经完成。 */ String scanError,
            /** 持久化 Crash 类型。 */ String crashKind) {
        /** JVM 调用点沿用当前工作类型。 */
        public Lease(UUID appId, String appVersion, UUID owner, long generation, long wakeGeneration,
                     Instant scanTime, String scanEventId, UUID jobId, String scanError) {
            this(appId, appVersion, owner, generation, wakeGeneration, scanTime, scanEventId, jobId, scanError, "jvm");
        }
    }
}
