package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import com.shanshui.apmserver.crash.internal.persistence.CrashStateJson;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore;
import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import tools.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import com.shanshui.apmserver.crash.internal.application.*;
import com.shanshui.apmserver.symbol.api.*;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolBuildIndexRepository;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 验证通知代次、租约重领和旧执行者门禁。 */
@Testcontainers(disabledWithoutDocker = true)
class BackgroundCrashJobPostgresTests {
    /** 独立测试服务，完全隔离开发数据。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 任务不因重复通知无限增长，等待无 mapping 的构建不空转。 */
    @Test
    void coalescesWakeupsAndRecoversExpiredLeasesWithoutLosingNotifications() {
        // 每次调用都在新的 schema 中应用真实迁移。
        String schema = "job_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        CrashSymbolicationJobs jobs = new CrashSymbolicationJobs(jdbc, manager);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        UUID user = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'job@example.invalid','任务测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.job','任务测试',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        jobs.wake(app, "build");
        jobs.wake(app, "build");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job", Integer.class));
        var first = jobs.claim(owner, Duration.ofMinutes(1)).orElseThrow();
        assertEquals(2, first.wakeGeneration());
        assertTrue(jobs.claim(UUID.randomUUID(), Duration.ofMinutes(1)).isEmpty());
        assertEquals(Boolean.TRUE, transaction.execute(status -> jobs.lockOwned(first)));
        assertThrows(IllegalStateException.class, () -> jobs.lockOwned(first));
        // 运行期间新到通知不能被 idle 完成覆盖。
        jobs.wake(app, "build");
        assertTrue(jobs.count(first, true));
        assertTrue(jobs.release(first, "idle", null, Duration.ZERO));
        assertEquals("queued", jobs.progress(app, "build").state());
        var second = jobs.claim(owner, Duration.ofMinutes(1)).orElseThrow();
        assertTrue(second.generation() > first.generation());
        assertFalse(jobs.renew(first, Duration.ofMinutes(1)));
        assertFalse(jobs.release(first, "idle", null, Duration.ZERO));
        assertTrue(jobs.release(second, "waiting_mapping", "mapping_missing", Duration.ZERO));
        assertTrue(jobs.claim(owner, Duration.ofMinutes(1)).isEmpty());
        // 无 mapping 时稳定等待；只有再次通知或对账才排队。
        assertEquals("waiting_mapping", jobs.progress(app, "build").state());
        jobs.wake(app, "build");
        assertEquals(0, jobs.progress(app, "build").succeededCount());
        assertNull(jobs.progress(app, "build").errorCategory());
        var expired = jobs.claim(owner, Duration.ofMinutes(1)).orElseThrow();
        jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()-interval '1 second'");
        assertFalse(jobs.renew(expired, Duration.ofMinutes(1)));
        assertEquals(Boolean.FALSE, transaction.execute(status -> jobs.lockOwned(expired)));
        var recovered = jobs.claim(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        assertTrue(recovered.generation() > expired.generation());
        assertFalse(jobs.count(expired, false));
        assertTrue(jobs.renew(recovered, Duration.ofMinutes(1)));
        assertTrue(jobs.release(recovered, "retry_wait", "store_unavailable", Duration.ofMinutes(5)));
        assertTrue(jobs.claim(owner, Duration.ofMinutes(1)).isEmpty());
        assertEquals("retry_wait", jobs.progress(app, "build").state());
        // 登记和外层业务事务共同回滚，不留下假的成功唤醒。
        transaction.executeWithoutResult(status -> {
            jobs.wake(app, "rolled-back");
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE build_id='rolled-back'", Integer.class));
        // 对账每页最多百个活动构建；检查点保存在数据库中，重建服务后继续下一页。
        for (int index = 1; index <= 105; index++) {
            UUID id = new UUID(0, index);
            jdbc.update("INSERT INTO app_symbol_file(symbol_id,app_id,build_id,revision,storage_key,original_filename,size_bytes,sha256,uploaded_by,uploaded_at,updated_at) VALUES (?,?,?,1,?,'mapping.txt',10,?,?,clock_timestamp(),clock_timestamp())",
                    id, app, "mapped-" + index, id + ".mapping", "a".repeat(64), user);
        }
        CrashSymbolicationReconciler reconciler = new CrashSymbolicationReconciler(new SymbolBuildIndexRepository(jdbc), jobs, jdbc, manager);
        reconciler.reconcilePage();
        assertEquals(101, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job", Integer.class));
        assertEquals(new UUID(0, 100), jdbc.queryForObject("SELECT after_symbol_id FROM crash_symbolication_reconcile", UUID.class));
        new CrashSymbolicationReconciler(new SymbolBuildIndexRepository(jdbc), jobs, jdbc, manager).reconcilePage();
        assertEquals(106, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job", Integer.class));
        assertNull(jdbc.queryForObject("SELECT after_symbol_id FROM crash_symbolication_reconcile", UUID.class));
        // 映射删除后的任务可到期；未确认发布、有效租约和保留期内工作均不提前清理。
        jdbc.update("UPDATE crash_symbolication_job SET updated_at=clock_timestamp()-interval '36 days' WHERE build_id='mapped-105'");
        assertEquals(1, jobs.cleanupExpired());
    }

    /** 发布登记后退出、确认丢失和 mapping 变化只能恢复同一首次成功。 */
    @Test
    void replaysFixedBytesAndNeverLetsLateFailureOverrideSuccess() {
        // 独立 schema 复用同一隔离服务；不会读取其他测试或开发数据。
        String schema = "publication_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        CrashSymbolicationJobs jobs = new CrashSymbolicationJobs(jdbc, manager);
        ObjectMapper mapper = new ObjectMapper();
        CrashStateJson codec = new CrashStateJson(mapper);
        InMemoryCrashStateStore memory = new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper);
        CrashStatePort fault = mock(CrashStatePort.class, delegatesTo(memory));
        CrashSymbolicationPublications publications = new CrashSymbolicationPublications(jdbc, jobs, fault, codec, manager, 1, 16777216);
        UUID user = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO apm_user VALUES (?,'publication@example.invalid','发布测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.publication','发布测试',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        jdbc.update("INSERT INTO app_symbol_file(symbol_id,app_id,build_id,revision,storage_key,original_filename,size_bytes,sha256,uploaded_by,uploaded_at,updated_at) VALUES (?,?,'build',1,'test.mapping','mapping.txt',10,?,?,clock_timestamp(),clock_timestamp())", symbol, app, "a".repeat(64), user);
        EventMetadata metadata = new EventMetadata(app, "com.example.publication", "event", "crash", now.minusSeconds(1), now,
                2, "session", "11111111-1111-4111-8111-111111111111", "device", "1", 1, "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
        CrashState pending = new CrashState(app, "event", "build", metadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain", "{}", "Exception", "tmp", "tmp-v1", "temporary", null, null, null, null, null);
        memory.accept(new CrashFact(metadata, "jvm", true), pending);
        jobs.wake(app, "build");
        var lease = jobs.claim(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        long oldFailure = publications.failureVersion(lease, "event").orElseThrow();
        CrashState computed = new CrashState(app, "event", "build", metadata.occurredAt(), 0, "symbolicated", null, "retrace_text", "fixed完整结果", "Exception", "resolved", "resolved-v1", "resolved", symbol, 1, "a".repeat(64), now, null);
        var fixed = publications.register(lease, computed).orElseThrow();
        assertTrue(fixed.version() > oldFailure);
        assertEquals(pending, memory.current(app, "event").orElseThrow());
        // 只有一个未发布名额；背压不能丢弃已登记的完整载荷。
        CrashState another = new CrashState(app, "another", "build", metadata.occurredAt(), 0, "symbolicated", null,
                "retrace_text", "第二份结果", "Exception", "resolved", "resolved-v1", "resolved", symbol, 1, "a".repeat(64), now, null);
        assertThrows(EventStoreUnavailableException.class, () -> publications.register(lease, another));
        assertEquals(fixed.payload(), publications.pending(lease, 100).getFirst().payload());
        // 登记后进程退出；新执行者不能获得新的失败版本，也不能用新 mapping 重算覆盖。
        jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()-interval '1 second'");
        assertTrue(publications.failureVersion(lease, "event").isEmpty());
        var recoveryLease = jobs.claim(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        CrashState changed = new CrashState(app, "event", "build", metadata.occurredAt(), 0, "symbolicated", null, "retrace_text", "不同结果", "Exception", "other", "resolved-v1", "resolved", symbol, 2, "b".repeat(64), now.plusSeconds(1), null);
        assertEquals(fixed, publications.register(recoveryLease, changed).orElseThrow());
        assertTrue(publications.failureVersion(recoveryLease, "event").isEmpty());
        // ClickHouse 已写但客户端确认丢失，登记载荷必须保留以便同字节重放。
        doAnswer(call -> { memory.replay(call.getArgument(0)); throw new EventStoreUnavailableException(); }).when(fault).replay(fixed.payload());
        assertThrows(EventStoreUnavailableException.class, () -> publications.publish(recoveryLease, fixed));
        assertEquals(fixed.payload(), publications.existing(app, "event").orElseThrow().payload());
        doAnswer(call -> { memory.replay(call.getArgument(0)); return null; }).when(fault).replay(fixed.payload());
        assertTrue(publications.publish(recoveryLease, fixed));
        verify(fault, times(2)).replay(fixed.payload());
        assertNull(publications.existing(app, "event").orElseThrow().payload());
        assertTrue(publications.pending(recoveryLease, 100).isEmpty());
        // 迟到失败低于固定版本，即使落库也不能回退成功。
        memory.publish(CrashSymbolicationPublications.versioned(pending, oldFailure));
        assertEquals("fixed完整结果", memory.current(app, "event").orElseThrow().stackPayload());
        assertEquals(symbol, memory.current(app, "event").orElseThrow().symbolId());
        assertEquals(1, memory.current(app, "event").orElseThrow().symbolRevision());
        assertEquals(0, publications.cleanupExpired());
        // 事件到期后不可通过恢复再写回遥测；轻量保护还需等待最大在途宽限。
        var expired = new CrashSymbolicationPublications.Publication(app, "event", "build", fixed.version(), fixed.sha256(), fixed.payload(), now.minusSeconds(1));
        assertFalse(publications.publish(recoveryLease, expired));
        jdbc.update("UPDATE crash_symbolication_publication SET expires_at=clock_timestamp()-interval '6 minutes',event_time=clock_timestamp()-interval '31 days'");
        assertEquals(1, publications.cleanupExpired());
        assertTrue(publications.existing(app, "event").isEmpty());
        // 事件已在 ClickHouse 确认但 PostgreSQL 唤醒失败；迟到时间早于上一轮也能被对账恢复。
        EventMetadata lateMetadata = new EventMetadata(app, metadata.packageName(), "late", "crash", now.minusSeconds(2 * 86400), now,
                2, "late-session", metadata.processId(), "device", "1", 1, "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
        CrashState late = new CrashState(app, "late", "build", lateMetadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain",
                mapper.writeValueAsString(CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a")), "java.lang.RuntimeException", "late-tmp", "tmp-v1", "temporary", null, null, null, null, null);
        memory.accept(new CrashFact(lateMetadata, "jvm", true), late);
        assertTrue(jobs.release(recoveryLease, "idle", null, Duration.ZERO));
        assertTrue(jobs.claim(UUID.randomUUID(), Duration.ofMinutes(1)).isEmpty());
        new CrashSymbolicationReconciler(new SymbolBuildIndexRepository(jdbc), jobs, jdbc, manager).reconcilePage();
        SymbolRegistry registry = mock(SymbolRegistry.class);
        SymbolFileLease mapping = mock(SymbolFileLease.class);
        when(registry.acquire(app, "build")).thenReturn(Optional.of(mapping));
        when(mapping.symbolId()).thenReturn(symbol);
        when(mapping.revision()).thenReturn(1);
        when(mapping.sha256()).thenReturn("a".repeat(64));
        when(registry.retrace(eq(mapping), anyList())).thenReturn(new SymbolicationResult("java.lang.RuntimeException\n\tat com.example.publication.Main.run(Main.java:1)", SymbolicationResult.Status.SYMBOLICATED, null));
        new CrashBackgroundProcessor(jobs, memory, publications, registry, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100).runOnce();
        assertEquals("symbolicated", memory.current(app, "late").orElseThrow().status());
        assertEquals(lateMetadata.occurredAt(), memory.current(app, "late").orElseThrow().eventTime());
        assertEquals(1, jobs.progress(app, "build").succeededCount());
        // mapping 事务登记与更早时间事件确认并发交错，通知代次或轮转均不能遗漏新事件。
        var gate = new java.util.concurrent.CountDownLatch(1);
        EventMetadata racingMetadata = new EventMetadata(app, metadata.packageName(), "racing-late", "crash", now.minusSeconds(3 * 86400), now,
                2, "racing-session", metadata.processId(), "device", "1", 1, "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
        CrashState racing = new CrashState(app, "racing-late", "build", racingMetadata.occurredAt(), 0, "pending", "awaiting_processing", "raw_chain",
                late.stackPayload(), "java.lang.RuntimeException", "racing-tmp", "tmp-v1", "temporary", null, null, null, null, null);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var mappingRegistration = executor.submit(() -> {
                gate.await();
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE app_symbol_file SET updated_at=clock_timestamp() WHERE symbol_id=?", symbol);
                    jobs.wake(app, "build");
                });
                return true;
            });
            var acceptance = executor.submit(() -> { gate.await(); return memory.accept(new CrashFact(racingMetadata, "jvm", true), racing); });
            gate.countDown();
            assertTrue(mappingRegistration.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(acceptance.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception failure) { throw new AssertionError(failure); }
        new CrashSymbolicationReconciler(new SymbolBuildIndexRepository(jdbc), jobs, jdbc, manager).reconcilePage();
        new CrashBackgroundProcessor(jobs, memory, publications, registry, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100).runOnce();
        assertEquals("symbolicated", memory.current(app, "racing-late").orElseThrow().status());
        assertEquals(racingMetadata.occurredAt(), memory.current(app, "racing-late").orElseThrow().eventTime());
    }
}
