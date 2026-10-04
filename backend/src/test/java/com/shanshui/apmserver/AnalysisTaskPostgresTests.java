package com.shanshui.apmserver;

import com.shanshui.apmserver.agent.internal.AnalysisEvidence;
import com.shanshui.apmserver.agent.internal.AnalysisMaintenance;
import org.springframework.transaction.PlatformTransactionManager;
import com.shanshui.apmserver.agent.internal.AnalysisResult;
import com.shanshui.apmserver.agent.internal.AnalysisTaskView;
import tools.jackson.databind.ObjectMapper;
import com.shanshui.apmserver.agent.internal.AnalysisRunService;
import com.shanshui.apmserver.agent.internal.WorkerCredentialService;
import com.shanshui.apmserver.agent.internal.AnalysisTaskService;
import com.shanshui.apmserver.crash.api.CrashAnalysisSnapshot;
import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashQueries;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.identity.internal.application.AppManagementService;
import com.shanshui.apmserver.identity.internal.domain.AppCreateRequest;
import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.security.AppUserPrincipal;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.symbol.internal.domain.SymbolFileEntity;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL 负责真实授权、幂等及证据发布；只替换耗时 Crash 查询边界。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "apm.agent.analysis.enabled=true"})
@AutoConfigureMockMvc
class AnalysisTaskPostgresTests {
    /** 隔离数据库，执行全部 Flyway 迁移。 */
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** Session 接口真实安全链。 */
    @Autowired private MockMvc mvc;
    /** 可重复调用的后台期限及到期维护。 */
    @Autowired private AnalysisMaintenance maintenance;
    /** 停用测试复用实际事务管理器。 */
    @Autowired private PlatformTransactionManager transactions;
    /** 与生产一致的 JSON 序列化器。 */
    @Autowired private ObjectMapper mapper;
    /** 当前 Run 分配与租约事务。 */
    @Autowired private AnalysisRunService runs;
    /** 独立执行凭据服务。 */
    @Autowired private WorkerCredentialService credentials;
    /** 待验证的任务服务。 */
    @Autowired private AnalysisTaskService tasks;
    /** 应用创建服务。 */
    @Autowired private AppManagementService apps;
    /** 真实成员存储。 */
    @Autowired private AppMemberRepository members;
    /** 真实用户存储。 */
    @Autowired private AppUserRepository users;
    /** 当前符号元数据由真实 JPA 管理，证明发布时版本检查。 */
    @Autowired private SymbolFileRepository symbols;
    /** 检查冻结内容与模拟准备期间的取消竞争。 */
    @Autowired private JdbcTemplate jdbc;
    /** 仅 mock 单事件原始查询与同次还原。 */
    @MockitoBean private CrashQueries crashes;
    /** 每项独立的应用。 */
    private UUID appId;
    /** 测试用户标识。 */
    private UUID userId;
    /** 经过已有身份域验证的 principal。 */
    private Authentication owner;

    /** 注入专用数据库。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 每次只操作本测试创建的应用。 */
    @BeforeEach
    void setup() {
        // 使用系统已引导用户，不通过测试角色绕过业务权限。
        var principal = AppUserPrincipal.from(users.findByEmailNormalized("test@example.com").orElseThrow());
        userId = principal.getUserId();
        owner = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        appId = apps.create(userId, new AppCreateRequest("com.example.task.p" + UUID.randomUUID().toString().replace("-", ""))).appId();
        when(crashes.rawEvent(eq(appId), anyString())).thenAnswer(call -> detail(call.getArgument(1), "token=sample-key user@example.com", "jvm", true));
        when(crashes.analysisSnapshot(eq(appId), anyString())).thenAnswer(call -> new CrashAnalysisSnapshot(
                detail(call.getArgument(1), "token=sample-key user@example.com", "jvm", true), null));
    }

    /** 未登记、无 mapping 同样直接创建；幂等只查询选中事件。 */
    @Test
    void createsWithoutRegistrationOrMappingAndKeepsIdempotency() {
        // 单事件请求稳定输入。
        UUID key = UUID.randomUUID();
        var ready = tasks.create(appId, "event-one", key, owner);
        assertEquals("READY", ready.state());
        assertEquals(ready.taskId(), tasks.create(appId, "event-one", key, owner).taskId());
        assertEquals("ANALYSIS_IDEMPOTENCY_CONFLICT", assertThrows(QueryValidationException.class,
                () -> tasks.create(appId, "event-two", key, owner)).getCode());
        verify(crashes, times(1)).rawEvent(appId, "event-one");
        verify(crashes, times(1)).analysisSnapshot(appId, "event-one");
        verifyNoMoreInteractions(crashes);
        assertThrows(QueryValidationException.class, () -> tasks.recheck(appId, ready.taskId(), owner));
        // 新证据仅保留原始 buildId 事实，不存在源码登记绑定。
        var evidence = mapper.readTree(jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, ready.taskId()));
        assertEquals(2, evidence.path("schemaVersion").asInt());
        assertFalse(evidence.has("source"));
        assertEquals("probe-build", evidence.path("event").path("buildId").asText());
        assertEquals("mapping_missing", evidence.path("mapping").path("reason").asText());
    }

    /** 未混淆任务不需要 mapping，身份与凭据不进入完整 JSON 字节，摘要可重算。 */
    @Test
    void freezesCompleteRedactedEvidenceAndPreservesItAcrossCorrections() {
        // 当前快照的不可变正文。
        var ready = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        assertEquals("READY", ready.state());
        String evidence = jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, ready.taskId());
        assertEquals(ready.evidenceSha256(), AnalysisEvidence.digest(evidence));
        assertTrue(evidence.contains("[REDACTED]"));
        assertTrue(evidence.contains("[EMAIL_REDACTED]"));
        for (String secret : List.of("sample-key", "user@example.com", "private-session", "private-process", "private-device", "private-basis")) {
            assertFalse(evidence.contains(secret), secret);
        }
        assertEquals(true, jdbc.queryForObject("SELECT evidence=evidence_text::jsonb FROM crash_analysis_task WHERE task_id=?", Boolean.class, ready.taskId()));
        assertEquals(ready.evidenceSha256(), tasks.get(appId, ready.taskId(), owner).evidenceSha256());
        assertEquals(evidence, jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, ready.taskId()));
    }

    /** 缺少或失败的符号化是材料局限，不阻断原始堆栈分析。 */
    @Test
    void acceptsRawEvidenceAndExplicitlyRechecksPreparationFailure() {
        assertEquals("READY", tasks.create(appId, "raw", UUID.randomUUID(), owner).state());
        when(crashes.analysisSnapshot(appId, "failure")).thenThrow(new IllegalStateException("private-error"));
        UUID key = UUID.randomUUID(); // 稳定输入，重试不自动重新准备。
        var blocked = tasks.create(appId, "failure", key, owner);
        assertEquals("PREPARATION_FAILED", blocked.blockReason());
        assertEquals("BLOCKED", tasks.create(appId, "failure", key, owner).state());
        doReturn(new CrashAnalysisSnapshot(detail("failure", "test", "jvm", true), null)).when(crashes).analysisSnapshot(appId, "failure");
        assertEquals("READY", tasks.recheck(appId, blocked.taskId(), owner).state());
    }

    /** 创建只支持 JVM fatal；非法原始类型在数据库插入前拒绝。 */
    @Test
    void rejectsOtherCrashKindsAndNonfatalEvents() {
        when(crashes.rawEvent(appId, "native")).thenReturn(detail("native", "test", "native", true));
        when(crashes.rawEvent(appId, "nonfatal")).thenReturn(detail("nonfatal", "test", "jvm", false));
        for (String event : List.of("native", "nonfatal")) {
            assertEquals("UNSUPPORTED_ANALYSIS_EVENT", assertThrows(QueryValidationException.class,
                    () -> tasks.create(appId, event, UUID.randomUUID(), owner)).getCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=?", Integer.class, appId));
    }

    /** 原文超限不截断，保留明确阻断原因。 */
    @Test
    void blocksOversizedEvidenceAndPreparationFailures() {
        when(crashes.analysisSnapshot(appId, "large")).thenReturn(new CrashAnalysisSnapshot(detail("large", "x".repeat(1048576), "jvm", true), null));
        assertEquals("EVIDENCE_TOO_LARGE", tasks.create(appId, "large", UUID.randomUUID(), owner).blockReason());
        when(crashes.analysisSnapshot(appId, "failure")).thenThrow(new IllegalStateException("sensitive-provider-error"));
        assertEquals("PREPARATION_FAILED", tasks.create(appId, "failure", UUID.randomUUID(), owner).blockReason());
    }

    /** 单事件身份漂移拒绝发布，不能把其他事件当成当前证据。 */
    @Test
    void rejectsPublicationWhenEventIdentityChanges() {
        when(crashes.analysisSnapshot(appId, "event-one")).thenReturn(new CrashAnalysisSnapshot(detail("another-event", "test", "jvm", true), null));
        assertEquals("EVENT_IDENTITY_CHANGED", tasks.create(appId, "event-one", UUID.randomUUID(), owner).blockReason());
    }

    /** 取消状态及代次已提交后，迟到的准备不能写入 READY 或证据。 */
    @Test
    void preventsLatePublicationAfterCancellation() {
        when(crashes.analysisSnapshot(appId, "event-one")).thenAnswer(call -> {
            jdbc.update("UPDATE crash_analysis_task SET state='CANCELLED',block_reason=NULL,preparation_generation=preparation_generation+1 WHERE app_id=?", appId);
            return new CrashAnalysisSnapshot(detail("event-one", "test", "jvm", true), null);
        });
        // 本测试验证 SQL 条件；网页取消入口将在生命周期阶段覆盖。
        var cancelled = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        assertEquals("CANCELLED", cancelled.state());
        assertNull(cancelled.evidenceId());
    }

    /** 还原后 mapping 被替换，旧文本与摘要不能发布为 READY。 */
    @Test
    void checksActualSymbolRevisionBeforePublishingAndPreservesFrozenVersion() {
        // 真实 mapping 元数据，正文读取由本测试的 Crash 边界模拟。
        UUID symbolId = UUID.randomUUID();
        symbols.saveAndFlush(new SymbolFileEntity(symbolId, appId, "probe-build", 1, symbolId + ".map",
                "mapping.txt", 12, "7".repeat(64), userId, Instant.now(), Instant.now()));
        when(crashes.analysisSnapshot(appId, "stable")).thenReturn(new CrashAnalysisSnapshot(symbolDetail("stable", symbolId), "7".repeat(64)));
        // 成功冻结后更换 mapping 不改写原材料。
        var ready = tasks.create(appId, "stable", UUID.randomUUID(), owner);
        assertEquals("READY", ready.state());
        when(crashes.analysisSnapshot(appId, "changed")).thenAnswer(call -> {
            jdbc.update("UPDATE app_symbol_file SET revision=2,sha256=? WHERE symbol_id=?", "8".repeat(64), symbolId);
            return new CrashAnalysisSnapshot(symbolDetail("changed", symbolId), "7".repeat(64));
        });
        assertEquals("SYMBOL_VERSION_CHANGED", tasks.create(appId, "changed", UUID.randomUUID(), owner).blockReason());
        assertEquals(ready.evidenceSha256(), tasks.get(appId, ready.taskId(), owner).evidenceSha256());
        assertTrue(jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, ready.taskId()).contains("7".repeat(64)));
    }

    /** 真实并发插入仍满足幂等；历史查询使用应用事件索引。 */
    @Test
    void serializesDuplicateCreationAndUsesBoundedEventIndex() throws Exception {
        // 屏障使两次原始读取后同时进入数据库插入。
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        when(crashes.rawEvent(appId, "event-one")).thenAnswer(call -> {
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return detail("event-one", "test", "jvm", true);
        });
        UUID key = UUID.randomUUID();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            // 相同键只有一个任务 ID。
            var first = executor.submit(() -> tasks.create(appId, "event-one", key, owner));
            var second = executor.submit(() -> tasks.create(appId, "event-one", key, owner));
            assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS).taskId(), second.get(10, java.util.concurrent.TimeUnit.SECONDS).taskId());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=?", Integer.class, appId));
        // 独立合成终态历史用于证明增长后仍按索引定位事件分页。
        jdbc.update("INSERT INTO crash_analysis_task(task_id,app_id,event_id,idempotency_key,created_by,fingerprint,state) "
                + "SELECT gen_random_uuid(),?, 'history-' || n,gen_random_uuid(),?,'test','CANCELLED' FROM generate_series(1,10000) n", appId, userId);
        jdbc.execute("ANALYZE crash_analysis_task");
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM crash_analysis_task WHERE app_id=? AND event_id=? ORDER BY created_at DESC,task_id DESC LIMIT 20 OFFSET 0", String.class, appId, "event-one"));
        assertTrue(plan.contains("crash_analysis_event_page_idx"), plan);
        assertEquals(1, tasks.list(appId, "event-one", owner, 0, 20).size());
    }

    /** 同次还原的合成详情完整附带当前符号身份。 */
    private CrashEventDetailResponse symbolDetail(String event, UUID symbolId) {
        // 复用原始单事件事实，替换公开的还原字段。
        var raw = detail(event, "test", "jvm", true);
        return new CrashEventDetailResponse(raw.appId(), raw.eventId(), raw.packageName(), raw.occurredAt(), raw.receivedAt(),
                raw.sessionId(), raw.processId(), raw.anonymousDeviceId(), raw.appVersion(), raw.versionCode(), raw.buildId(),
                raw.channel(), raw.environment(), raw.osVersion(), raw.deviceModel(), raw.networkType(), raw.exceptionType(),
                raw.fingerprint(), raw.fingerprintVersion(), "symbolicated", "Example.crash(Example.kt:2)", symbolId, 1, null, raw.rawCrash());
    }

    /** 完整值只在创建返回；到期、撤销、Session、查询 Token 都不能冒充 Worker。 */
    @Test
    void isolatesWorkerCredentialsAndRevalidatesExpiryAndRevocation() throws Exception {
        // 无构建映射的任务可以先由 Worker 查看前提元数据。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        // 唯一一次完整秘密，不写入数据库或日志。
        var created = credentials.create(appId, "local test", owner);
        assertEquals(appId, credentials.authenticate(created.credential()).appId());
        assertEquals(AnalysisEvidence.digest(created.credential()), jdbc.queryForObject("SELECT token_sha256 FROM analysis_worker_credential WHERE credential_id=?", String.class, created.metadata().credentialId()));
        assertTrue(created.metadata().expiresAt().isAfter(created.metadata().createdAt().plusSeconds(29 * 86400)));
        mvc.perform(get("/api/v1/apps/{appId}/analysis-workers", appId).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].credential").doesNotExist()).andExpect(jsonPath("$[0].tokenSha256").doesNotExist());
        // 独立链不能加载测试 Session，也不能将查询凭据作为执行身份。
        String route = "/api/worker/v1/tasks/{taskId}";
        mvc.perform(get(route, task.taskId()).with(authentication(owner))).andExpect(status().isUnauthorized());
        for (String unrelated : List.of("apm_qt_" + "a".repeat(43), "application-key")) {
            mvc.perform(get(route, task.taskId()).header("Authorization", "Bearer " + unrelated)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(route, task.taskId()).header("Authorization", "Bearer " + created.credential()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.taskId").value(task.taskId().toString())).andExpect(jsonPath("$.evidence").doesNotExist());
        // 另一个应用的真实凭据不能读取选中任务。
        UUID other = apps.create(userId, new AppCreateRequest("com.example.other.p" + UUID.randomUUID().toString().replace("-", ""))).appId();
        var otherCredential = credentials.create(other, "other", owner);
        mvc.perform(get(route, task.taskId()).header("Authorization", "Bearer " + otherCredential.credential())).andExpect(status().isNotFound());
        // 管理撤销必须有 CSRF，即使有 Session 也不能绕过。
        mvc.perform(delete("/api/v1/apps/{appId}/analysis-workers/{id}", appId, created.metadata().credentialId()).with(authentication(owner))).andExpect(status().isForbidden());
        credentials.revoke(appId, created.metadata().credentialId(), owner);
        credentials.revoke(appId, created.metadata().credentialId(), owner);
        mvc.perform(get(route, task.taskId()).header("Authorization", "Bearer " + created.credential())).andExpect(status().isUnauthorized());
        // 到期由数据库时间判断，而不是进程缓存；更改测试记录到历史时间。
        var expired = credentials.create(appId, "expired", owner);
        jdbc.update("UPDATE analysis_worker_credential SET created_at=clock_timestamp()-INTERVAL '31 days',expires_at=clock_timestamp()-INTERVAL '1 day' WHERE credential_id=?", expired.metadata().credentialId());
        mvc.perform(get(route, task.taskId()).header("Authorization", "Bearer " + expired.credential())).andExpect(status().isUnauthorized());
        for (AppRole role : List.of(AppRole.DEVELOPER, AppRole.VIEWER)) {
            members.save(new AppMember(new AppMemberId(appId, userId), role, Instant.now()));
            mvc.perform(post("/api/v1/apps/{appId}/analysis-workers", appId).with(authentication(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"local\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    /** 相同请求并发或响应丢失重试都复用 Run；其他 Worker 无法重复领取。 */
    @Test
    void atomicallyClaimsOnceAndReturnsOriginalLeaseOnRetry() throws Exception {
        // 固定已经 READY 的单事件任务。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        // 凭据身份由服务端认证取得。
        var created = credentials.create(appId, "worker-one", owner);
        var identity = credentials.authenticate(created.credential());
        // 同一领取请求丢失响应后不会启动第二个 Run。
        UUID request = UUID.randomUUID();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            // 两次请求同时进入真实 PostgreSQL 事务。
            var first = executor.submit(() -> { start.await(); return runs.claim(identity, task.taskId(), request); });
            var second = executor.submit(() -> { start.await(); return runs.claim(identity, task.taskId(), request); });
            start.countDown();
            var allocated = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var repeated = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(allocated.run().runId(), repeated.run().runId());
            assertEquals(allocated.leaseToken(), repeated.leaseToken());
            // 同 ID 不得变更任务输入。
            assertEquals("ANALYSIS_CLAIM_CONFLICT", assertThrows(QueryValidationException.class,
                    () -> runs.claim(identity, UUID.randomUUID(), request)).getCode());
            assertNull(allocated.run().snapshotId());
            // 冻结字节与任务摘要在有效分配下保持一致。
            var lease = new AnalysisRunService.Lease(allocated.run().leaseGeneration(), allocated.leaseToken());
            assertEquals(task.evidenceSha256(), AnalysisEvidence.digest(runs.evidence(identity, allocated.run().runId(), lease)));
            assertEquals("RUNNING", runs.heartbeat(identity, allocated.run().runId(), lease).state());
            assertEquals("ANALYSIS_LEASE_INVALID", assertThrows(QueryValidationException.class,
                    () -> runs.heartbeat(identity, allocated.run().runId(), new AnalysisRunService.Lease(lease.generation()+1, lease.token()))).getCode());
            // 请求 ID 不同或身份不同都无法重复分配当前任务。
            var otherIdentity = credentials.authenticate(credentials.create(appId, "worker-two", owner).credential());
            assertEquals("ANALYSIS_NOT_READY", assertThrows(QueryValidationException.class,
                    () -> runs.claim(otherIdentity, task.taskId(), UUID.randomUUID())).getCode());
            assertEquals("ANALYSIS_RUN_NOT_FOUND", assertThrows(QueryValidationException.class,
                    () -> runs.status(otherIdentity, allocated.run().runId())).getCode());
            // 同凭据不能领取第二个 READY 任务。
            var another = tasks.create(appId, "event-two", UUID.randomUUID(), owner);
            assertEquals("WORKER_BUSY", assertThrows(QueryValidationException.class,
                    () -> runs.claim(identity, another.taskId(), UUID.randomUUID())).getCode());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE app_id=?", Integer.class, appId));
    }

    /** 过期心跳拒绝后期限状态仍提交，失联不能被标记为停止。 */
    @Test
    void commitsExpiredLeaseAndRejectsOldEvidenceAccess() {
        // 先创建一份有效分配，再模拟数据库租约已到期。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        UUID request = UUID.randomUUID();
        var allocated = runs.claim(identity, task.taskId(), request);
        var lease = new AnalysisRunService.Lease(allocated.run().leaseGeneration(), allocated.leaseToken());
        jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?", allocated.run().runId());
        assertEquals("ANALYSIS_LEASE_INVALID", assertThrows(QueryValidationException.class,
                () -> runs.heartbeat(identity, allocated.run().runId(), lease)).getCode());
        assertEquals("FAILED", tasks.get(appId, task.taskId(), owner).state());
        assertFalse(runs.status(identity, allocated.run().runId()).stopConfirmed());
        assertEquals("LEASE_EXPIRED", runs.status(identity, allocated.run().runId()).errorCode());
        assertEquals("ANALYSIS_LEASE_INVALID", assertThrows(QueryValidationException.class,
                () -> runs.evidence(identity, allocated.run().runId(), lease)).getCode());
        assertNull(runs.claim(identity, task.taskId(), request).leaseToken());
        assertEquals(allocated.run().runId(), runs.claim(identity, task.taskId(), request).run().runId());
    }

    /** 取消等待真实停止，重复回执幂等；旧尝试不能污染显式重试的 Run。 */
    @Test
    void waitsForStopThenAppendsRetryAndRejectsOldLease() {
        // 创建者取消自己的运行任务。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var allocated = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(allocated.run().leaseGeneration(), allocated.leaseToken());
        assertEquals("CANCELLING", tasks.cancel(appId, task.taskId(), owner).state());
        assertEquals("CANCELLING", runs.heartbeat(identity, allocated.run().runId(), lease).taskState());
        assertEquals("ANALYSIS_STOP_REQUIRED", assertThrows(QueryValidationException.class,
                () -> runs.evidence(identity, allocated.run().runId(), lease)).getCode());
        assertEquals("ANALYSIS_STATE_CONFLICT", assertThrows(QueryValidationException.class,
                () -> tasks.retry(appId, task.taskId(), owner)).getCode());
        assertTrue(stopAndConfirm(identity, allocated.run().runId(), lease, "CANCELLED").stopConfirmed());
        assertEquals("CANCELLED", tasks.get(appId, task.taskId(), owner).state());
        assertTrue(stopAndConfirm(identity, allocated.run().runId(), lease, "CANCELLED").stopConfirmed());
        assertEquals("READY", tasks.retry(appId, task.taskId(), owner).state());
        // 新 Run 独立代次，原环境停止回执只作用于旧记录。
        var next = runs.claim(identity, task.taskId(), UUID.randomUUID());
        assertEquals(2, next.run().attempt());
        assertNotEquals(allocated.run().runId(), next.run().runId());
        stopAndConfirm(identity, allocated.run().runId(), lease, "CANCELLED");
        assertEquals("RUNNING", tasks.get(appId, task.taskId(), owner).state());
        assertEquals("ANALYSIS_LEASE_INVALID", assertThrows(QueryValidationException.class,
                () -> runs.heartbeat(identity, allocated.run().runId(), lease)).getCode());
    }

    /** 到期未知停止阻止重试；撤销后仅管理员人工核验可以解除停止门禁。 */
    @Test
    void requiresAdminVerificationAfterWorkerRevocation() {
        // 撤销和过期不等于容器退出。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var created = credentials.create(appId, "worker", owner);
        var identity = credentials.authenticate(created.credential());
        var allocated = runs.claim(identity, task.taskId(), UUID.randomUUID());
        jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?", allocated.run().runId());
        runs.status(identity, allocated.run().runId());
        assertEquals("ANALYSIS_STOP_UNCONFIRMED", assertThrows(QueryValidationException.class,
                () -> tasks.retry(appId, task.taskId(), owner)).getCode());
        credentials.revoke(appId, created.metadata().credentialId(), owner);
        assertEquals("WORKER_CREDENTIAL_INVALID", assertThrows(QueryValidationException.class,
                () -> stopAndConfirm(identity, allocated.run().runId(), new AnalysisRunService.Lease(allocated.run().leaseGeneration(), allocated.leaseToken()), "LEASE_EXPIRED")).getCode());
        members.save(new AppMember(new AppMemberId(appId, userId), AppRole.DEVELOPER, Instant.now()));
        assertThrows(RuntimeException.class, () -> runs.confirmStopped(appId, allocated.run().runId(), owner, "synthetic environment removed"));
        members.save(new AppMember(new AppMemberId(appId, userId), AppRole.ADMIN, Instant.now()));
        runs.confirmStopped(appId, allocated.run().runId(), owner, "synthetic environment removed");
        assertEquals(userId, jdbc.queryForObject("SELECT stopped_by FROM crash_analysis_run WHERE run_id=?", UUID.class, allocated.run().runId()));
        assertEquals("READY", tasks.retry(appId, task.taskId(), owner).state());
    }

    /** 已保存结果相同摘要重传返回原确认；不同内容冲突而不产生新 Run。 */
    @Test
    void completesStructuredResultIdempotentlyWithUnknownCost() {
        // 完整结构化候选，并声明没有执行建议验证步骤。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        String json = result(task, task.evidenceId(), "Example.kt", "raw-crash", "test summary");
        var completion = new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, true);
        assertEquals("SUCCEEDED", runs.complete(identity, claimed.run().runId(), completion).state());
        // 完成后租约已过期仍可确认原结果，不重新运行模型。
        jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 day' WHERE run_id=?", claimed.run().runId());
        assertEquals("SUCCEEDED", runs.complete(identity, claimed.run().runId(), completion).state());
        assertFalse(runs.status(identity, claimed.run().runId()).stopConfirmed());
        assertTrue(runs.status(identity, claimed.run().runId()).localToolsStopped());
        assertEquals("UNKNOWN", runs.status(identity, claimed.run().runId()).hostStopState());
        String different = result(task, task.evidenceId(), "Example.kt", "raw-crash", "different summary");
        assertEquals("ANALYSIS_RESULT_CONFLICT", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, different, AnalysisEvidence.digest(different), false, true))).getCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE app_id=?", Integer.class, appId));
        assertEquals(json, jdbc.queryForObject("SELECT result_text FROM crash_analysis_run WHERE run_id=?", String.class, claimed.run().runId()));
    }

    /** 跨任务证据、错误源码路径和未知片段被拒绝；证据不足可以正常结束。 */
    @Test
    void rejectsInvalidReferencesAndAcceptsInsufficientEvidence() {
        // 多次非法回传都不会改变有效分配的状态。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        for (String invalid : List.of(result(task, UUID.randomUUID(), "Example.kt", "raw-crash", "test"),
                result(task, task.evidenceId(), "../Example.kt", "raw-crash", "test"),
                result(task, task.evidenceId(), "Example.kt", "other-fragment", "test"))) {
            assertEquals("ANALYSIS_REFERENCE_INVALID", assertThrows(QueryValidationException.class,
                    () -> runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, invalid, AnalysisEvidence.digest(invalid), false, true))).getCode());
        }
        assertEquals("INVALID_ANALYSIS_RESULT", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, "null", AnalysisEvidence.digest("null"), false, true))).getCode());
        // 业务结论为证据不足，与执行成功分开。
        String insufficient = mapper.writeValueAsString(new AnalysisResult(4, task.evidenceId(), claimed.run().runId(), "INSUFFICIENT_EVIDENCE",
                "缺少实际触发条件", List.of(), List.of(), List.of("没有用户操作上下文"), List.of(), List.of(), List.of("人工复现"),
                new AnalysisResult.Execution(null, null, "HOST_AGENT", null, null, "0.4.0", "UNKNOWN"), new AnalysisResult.Usage(null, null, null, null, null),
                new AnalysisResult.Repair("NOT_REQUESTED", List.of(), "仅分析", "HOST_REPORTED"),
                new AnalysisResult.Verification("NOT_RUN", "HOST_REPORTED", List.of(), "未执行测试")));
        assertEquals("SUCCEEDED", runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, insufficient, AnalysisEvidence.digest(insufficient), false, true)).state());
    }

    /** 取消和过期禁止首次成功结果，环境已经停止也不能改写业务终态。 */
    @Test
    void rejectsCompletionAfterCancelOrExpiry() {
        // 首个分配在取消后仍必须回传停止事实。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        String json = result(task, task.evidenceId(), "Example.kt", "raw-crash", "test");
        var completion = new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, true);
        tasks.cancel(appId, task.taskId(), owner);
        assertEquals("ANALYSIS_STOP_REQUIRED", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, claimed.run().runId(), completion)).getCode());
        stopAndConfirm(identity, claimed.run().runId(), lease, "CANCELLED");
        tasks.retry(appId, task.taskId(), owner);
        // 第二次分配到期；拒绝成功后 FAILED 状态必须提交。
        var next = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var nextLease = new AnalysisRunService.Lease(next.run().leaseGeneration(), next.leaseToken());
        jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?", next.run().runId());
        assertEquals("ANALYSIS_LEASE_INVALID", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, next.run().runId(), new AnalysisRunService.Completion(nextLease, json, AnalysisEvidence.digest(json), false, true))).getCode());
        assertEquals("FAILED", tasks.get(appId, task.taskId(), owner).state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE app_id=? AND result IS NOT NULL", Integer.class, appId));
    }

    /** 与 Worker 契约一致的无敏感合成结果，源码只作为结构核验材料。 */
    private String result(AnalysisTaskView task, UUID evidenceId, String path, String fragment, String summary) {
        return mapper.writeValueAsString(new AnalysisResult(4, evidenceId, jdbc.queryForObject("SELECT current_run_id FROM crash_analysis_task WHERE task_id=?", UUID.class, task.taskId()), "ROOT_CAUSE_CANDIDATE", summary,
                List.of(new AnalysisResult.Candidate("test", "明确抛出异常", List.of(fragment))),
                List.of(new AnalysisResult.SourceReference(path, 2, 2, "throw IllegalStateException()", AnalysisEvidence.digest("throw IllegalStateException()"), "HOST_REPORTED", "CURRENT_MATCH")),
                List.of("仅合成测试"), List.of(), List.of("人工检查"), List.of("复现验证"),
                new AnalysisResult.Execution(null, null, "HOST_AGENT", null, null, "0.4.0", "UNKNOWN"), new AnalysisResult.Usage(null, null, null, null, null),
                new AnalysisResult.Repair("NOT_REQUESTED", List.of(), "仅分析", "HOST_REPORTED"),
                new AnalysisResult.Verification("NOT_RUN", "HOST_REPORTED", List.of(), "未执行测试")));
    }

    /** 已停止终态内容到期后保留摘要，失联未知环境不清理证据。 */
    @Test
    void expiresOnlyStoppedTerminalContentsAndKeepsUnknownRunEvidence() {
        // 第一任务正常完成并确认已停止环境。
        var task = tasks.create(appId, "completed", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        String json = result(task, task.evidenceId(), "Example.kt", "raw-crash", "test");
        runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, true));
        runs.confirmStopped(appId, claimed.run().runId(), owner, "受控测试确认宿主本次分析已结束");
        jdbc.update("UPDATE crash_analysis_task SET terminal_at=clock_timestamp()-INTERVAL '31 days' WHERE task_id=?", task.taskId());
        // 第二任务失联，没有停止事实，尽管很旧也不能删除证据。
        var unknown = tasks.create(appId, "unknown", UUID.randomUUID(), owner);
        var unknownRun = runs.claim(identity, unknown.taskId(), UUID.randomUUID());
        jdbc.update("UPDATE crash_analysis_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 day' WHERE run_id=?", unknownRun.run().runId());
        maintenance.sweep();
        jdbc.update("UPDATE crash_analysis_task SET terminal_at=clock_timestamp()-INTERVAL '31 days' WHERE task_id=?", unknown.taskId());
        maintenance.sweep();
        assertTrue(tasks.get(appId, task.taskId(), owner).contentExpired());
        assertEquals(task.evidenceSha256(), tasks.get(appId, task.taskId(), owner).evidenceSha256());
        assertNull(jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, task.taskId()));
        assertTrue(runs.history(appId, task.taskId(), owner, 0, 20).getFirst().contentExpired());
        assertNull(runs.history(appId, task.taskId(), owner, 0, 20).getFirst().result());
        assertFalse(tasks.get(appId, unknown.taskId(), owner).contentExpired());
        assertNotNull(jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, unknown.taskId()));
        assertFalse(runs.status(identity, unknownRun.run().runId()).stopConfirmed());
    }

    /** 功能关闭拒绝新任务并给现有运行发出取消意图，不假设已停止。 */
    @Test
    void stopsActiveTasksWhenFeatureIsDisabledAndBoundsWorkerRequests() throws Exception {
        // 一个活动运行与一个尚未执行的任务，模拟部署关闭功能。
        var task = tasks.create(appId, "running", UUID.randomUUID(), owner);
        var pending = tasks.create(appId, "pending", UUID.randomUUID(), owner);
        var created = credentials.create(appId, "worker", owner);
        var identity = credentials.authenticate(created.credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        new AnalysisMaintenance(jdbc, transactions, false).sweep();
        assertEquals("CANCELLING", tasks.get(appId, task.taskId(), owner).state());
        assertEquals("CANCELLED", tasks.get(appId, pending.taskId(), owner).state());
        assertFalse(runs.status(identity, claimed.run().runId()).stopConfirmed());
        // 真实 Security 链在 JSON 解析前限制最多 2 MiB，无须调用模型。
        mvc.perform(post("/api/worker/v1/tasks/runs/{runId}/heartbeat", claimed.run().runId())
                        .header("Authorization", "Bearer " + created.credential()).contentType(MediaType.APPLICATION_JSON).content("x".repeat(2097153)))
                .andExpect(status().isPayloadTooLarge());
        // 后续正常请求仍通过认证并看到停止意图，证明限流拒绝没有破坏认证链。
        mvc.perform(get("/api/worker/v1/tasks/runs/{runId}", claimed.run().runId()).header("Authorization", "Bearer " + created.credential()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskState").value("CANCELLING"));
    }

    /** 两个真实身份并发竞争同任务只有一个成功；HTTP 正常领取与完成契约可运行。 */
    @Test
    void serializesDifferentWorkersAndExercisesHttpCompletionContract() throws Exception {
        // 两个独立身份同时竞争单个 READY 任务。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var first = credentials.authenticate(credentials.create(appId, "one", owner).credential());
        var second = credentials.authenticate(credentials.create(appId, "two", owner).credential());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            // 成功的一方保留有效分配，失败方不得新增记录。
            var firstResult = executor.submit(() -> claimAfter(start, first, task.taskId()));
            var secondResult = executor.submit(() -> claimAfter(start, second, task.taskId()));
            start.countDown();
            assertEquals(1, (firstResult.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)+(secondResult.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run WHERE task_id=?", Integer.class, task.taskId()));
        // 不同任务、独立第三凭据通过真实 HTTP 完成一次单事件分析协议。
        var httpTask = tasks.create(appId, "http", UUID.randomUUID(), owner);
        var credential = credentials.create(appId, "http", owner);
        String response = mvc.perform(post("/api/worker/v1/tasks/{taskId}/claim", httpTask.taskId())
                        .header("Authorization", "Bearer " + credential.credential()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        // 响应只在当前方法内存使用，不写入日志或测试快照。
        var claimed = mapper.readValue(response, AnalysisRunService.Claimed.class);
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        String json = result(httpTask, httpTask.evidenceId(), "Example.kt", "raw-crash", "http test");
        mvc.perform(post("/api/worker/v1/tasks/runs/{runId}/complete", claimed.run().runId())
                        .header("Authorization", "Bearer " + credential.credential()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
        mvc.perform(get("/api/v1/apps/{appId}/analysis-tasks/{taskId}/runs", appId, httpTask.taskId()).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].result.summary").value("http test"))
                .andExpect(jsonPath("$[0].leaseToken").doesNotExist()).andExpect(jsonPath("$[0].run.leaseToken").doesNotExist());
    }

    /** 同时释放后执行实际领取，非获胜方只能得到非 READY 冲突。 */
    private boolean claimAfter(java.util.concurrent.CountDownLatch start, WorkerCredentialService.Identity identity, UUID taskId) throws InterruptedException {
        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            runs.claim(identity, taskId, UUID.randomUUID());
            return true;
        } catch (QueryValidationException failure) {
            assertEquals("ANALYSIS_NOT_READY", failure.getCode());
            return false;
        }
    }

    /** 清理失败报告停止未确认；重复失败幂等，停止后才能显式重试。 */
    @Test
    void preservesUnconfirmedStopOnFailureAndUsesFixedTimeLimit() {
        // 报告执行失败但无法确认环境清理，不释放执行身份。
        var task = tasks.create(appId, "event-one", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "worker", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        var failure = new AnalysisRunService.Failure(lease, "EXECUTOR_FAILED", false, false);
        assertFalse(runs.fail(identity, claimed.run().runId(), failure).stopConfirmed());
        assertEquals("FAILED", runs.fail(identity, claimed.run().runId(), failure).state());
        assertEquals("ANALYSIS_STOP_UNCONFIRMED", assertThrows(QueryValidationException.class,
                () -> tasks.retry(appId, task.taskId(), owner)).getCode());
        stopAndConfirm(identity, claimed.run().runId(), lease, "EXECUTOR_FAILED");
        tasks.retry(appId, task.taskId(), owner);
        // 总运行期限不被心跳无限延期，和租约失联使用不同原因。
        var next = runs.claim(identity, task.taskId(), UUID.randomUUID());
        jdbc.update("UPDATE crash_analysis_run SET deadline_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?", next.run().runId());
        maintenance.sweep();
        assertEquals("TIME_LIMIT", runs.status(identity, next.run().runId()).errorCode());
        assertFalse(runs.status(identity, next.run().runId()).stopConfirmed());
        assertEquals(2, runs.history(appId, task.taskId(), owner, 0, 20).size());
        assertThrows(QueryValidationException.class, () -> runs.history(appId, task.taskId(), owner, 0, 101));
    }

    /** 四种成员角色实时读取，CSRF、匿名及跨应用请求均使用安全链。 */
    @Test
    void enforcesRolesCsrfAndAppOwnership() throws Exception {
        // 所有请求用不同键，避免角色测试互相污染。
        String route = "/api/v1/apps/{appId}/crashes/events/event-one/analyses";
        mvc.perform(post(route, appId).with(authentication(owner)).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(post(route, appId).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        members.save(new AppMember(new AppMemberId(appId, userId), AppRole.VIEWER, Instant.now()));
        mvc.perform(post(route, appId).with(authentication(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(get(route, appId).with(authentication(owner))).andExpect(status().isOk());
        for (AppRole role : List.of(AppRole.OWNER, AppRole.ADMIN, AppRole.DEVELOPER)) {
            members.save(new AppMember(new AppMemberId(appId, userId), role, Instant.now()));
            mvc.perform(post(route, appId).with(authentication(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post(route, UUID.randomUUID()).with(authentication(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
        mvc.perform(get(route, appId).with(authentication(owner)).param("size", "101")).andExpect(status().isBadRequest());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_task WHERE app_id=?", Integer.class, appId));
        mvc.perform(post(route, appId).with(authentication(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isTooManyRequests());
    }

    /** 单事件合成 DTO；所有身份字段必须从冻结材料排除。 */
    private CrashEventDetailResponse detail(String eventId, String message, String kind, boolean fatal) {
        return new CrashEventDetailResponse(appId, eventId, "com.example.test", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:01Z"), "private-session", "private-process", "private-device", "1", 1,
                "probe-build", "test", "debug", "13", "synthetic", "wifi", "java.lang.IllegalStateException", "fingerprint", "1",
                "raw_only", null, null, null, "mapping_missing", new CrashPayload(kind, fatal,
                List.of(new ThrowableNode("java.lang.IllegalStateException", message,
                        List.of(new StackFrame("Example", "crash", "Example.kt", 2, true))))));
    }

    /** 每次创建都使用一个新的稳定输入键。 */
    private String body() { return "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"; }

    /** 测试先报告本地工具关闭，再独立模拟管理员实际核验宿主结束。 */
    private AnalysisRunService.RunView stopAndConfirm(WorkerCredentialService.Identity identity, UUID runId, AnalysisRunService.Lease lease, String code) {
        runs.stopped(identity, runId, lease, code);
        runs.confirmStopped(appId, runId, owner, "受控测试核验本次宿主分析与材料工具均结束");
        return runs.status(identity, runId);
    }

    /** 工具关闭不能让 Worker 伪造宿主停止，门禁需管理员事实。 */
    @Test
    void separatesToolClosureFromHostStopAndRejectsFakeCompletion() {
        var task = tasks.create(appId, "host", UUID.randomUUID(), owner);
        var identity = credentials.authenticate(credentials.create(appId, "host", owner).credential());
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID());
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken());
        String json = result(task, task.evidenceId(), "Example.kt", "raw-crash", "host result");
        assertThrows(QueryValidationException.class, () -> runs.complete(identity, claimed.run().runId(),
                new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), true, true)));
        assertThrows(QueryValidationException.class, () -> runs.complete(identity, claimed.run().runId(),
                new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, false)));
        // 首版没有可信宿主模型标识，不接受把自报模型填成已记录来源。
        String forgedModel = json.replace("\"modelId\":null", "\"modelId\":\"fake-model\"");
        assertNotEquals(json, forgedModel);
        assertEquals("INVALID_ANALYSIS_RESULT", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease, forgedModel, AnalysisEvidence.digest(forgedModel), false, true))).getCode());
        assertEquals("SUCCEEDED", runs.complete(identity, claimed.run().runId(),
                new AnalysisRunService.Completion(lease, json, AnalysisEvidence.digest(json), false, true)).state());
        assertFalse(runs.status(identity, claimed.run().runId()).stopConfirmed());
        // 万条已停止合成历史不应让每次领取扫描整个凭据生命周期。
        jdbc.update("INSERT INTO crash_analysis_run(run_id,task_id,app_id,credential_id,request_id,attempt,state,lease_generation,lease_sha256,lease_expires_at,deadline_at,stop_confirmed) "
                + "SELECT gen_random_uuid(),task_id,app_id,credential_id,gen_random_uuid(),n+100,'SUCCEEDED',1,lease_sha256,lease_expires_at,deadline_at,true "
                + "FROM crash_analysis_run CROSS JOIN generate_series(1,10000) n WHERE run_id=?", claimed.run().runId());
        jdbc.execute("ANALYZE crash_analysis_run");
        // 保留真实规划器选择，未关闭顺序扫描来制造索引通过。
        String stopPlan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT EXISTS(SELECT 1 FROM crash_analysis_run WHERE credential_id=? AND (state='RUNNING' OR stop_confirmed=false))", String.class, identity.credentialId()));
        assertTrue(stopPlan.contains("crash_analysis_run_unconfirmed_worker_idx"), stopPlan);
        var another = tasks.create(appId, "another", UUID.randomUUID(), owner);
        assertEquals("WORKER_BUSY", assertThrows(QueryValidationException.class,
                () -> runs.claim(identity, another.taskId(), UUID.randomUUID())).getCode());
        runs.confirmStopped(appId, claimed.run().runId(), owner, "独立实际核验本次宿主分析已结束");
        assertEquals("CONFIRMED", runs.status(identity, claimed.run().runId()).hostStopState());
        assertEquals("RUNNING", runs.claim(identity, another.taskId(), UUID.randomUUID()).run().state());
    }

    /** 冻结旧输入拒绝领取为宿主，旧历史不静默迁移。 */
    @Test
    void rejectsImplicitExecutorReplacementForFrozenTask() {
        var task = tasks.create(appId, "legacy", UUID.randomUUID(), owner);
        // 仅模拟旧冻结正文；原始字节、JSONB 和摘要始终一致。
        String oldEvidence = jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, task.taskId()).replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        jdbc.update("UPDATE crash_analysis_task SET evidence=?::jsonb,evidence_text=?,evidence_sha256=? WHERE task_id=?", oldEvidence, oldEvidence, AnalysisEvidence.digest(oldEvidence), task.taskId());
        var identity = credentials.authenticate(credentials.create(appId, "host", owner).credential());
        assertEquals("ANALYSIS_CONFIG_MISMATCH", assertThrows(QueryValidationException.class,
                () -> runs.claim(identity, task.taskId(), UUID.randomUUID())).getCode());
    }
    /** 新结果必须固定当前 Run，不接受旧提交字段或伪造验证通过。 */
    @Test
    void rejectsOtherRunsLegacyFieldsAndFalseRepairFacts() {
        var task = tasks.create(appId, "current-result", UUID.randomUUID(), owner); // 当前事件。
        var identity = credentials.authenticate(credentials.create(appId, "current", owner).credential()); // 受信应用身份。
        var claimed = runs.claim(identity, task.taskId(), UUID.randomUUID()); // 当前服务端分配。
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(), claimed.leaseToken()); // 原分配租约。
        String valid = result(task, task.evidenceId(), "Example.kt", "raw-crash", "当前源码分析"); // 受控报告。
        String crossRun = valid.replace(claimed.run().runId().toString(), UUID.randomUUID().toString()); // 其他 Run。
        assertEquals("ANALYSIS_REFERENCE_INVALID", assertThrows(QueryValidationException.class,
                () -> runs.complete(identity, claimed.run().runId(), new AnalysisRunService.Completion(lease,crossRun,AnalysisEvidence.digest(crossRun),false,true))).getCode());
        for (String invalid : List.of(valid.replace("NOT_REQUESTED", "APPLIED"),valid.replace("NOT_RUN", "PASSED"))) {
            assertEquals("ANALYSIS_REFERENCE_INVALID", assertThrows(QueryValidationException.class,
                    () -> runs.complete(identity,claimed.run().runId(),new AnalysisRunService.Completion(lease,invalid,AnalysisEvidence.digest(invalid),false,true))).getCode());
        }
        for (String invalid : List.of(valid.replace("\"schemaVersion\":4", "\"schemaVersion\":3"), valid.replace("CURRENT_MATCH", "TOOL_PROVEN_READ"))) {
            assertEquals("INVALID_ANALYSIS_RESULT", assertThrows(QueryValidationException.class,
                    () -> runs.complete(identity,claimed.run().runId(),new AnalysisRunService.Completion(lease,invalid,AnalysisEvidence.digest(invalid),false,true))).getCode());
        }
        // 即使绕过 Python，也不能在源码引用或修改路径中回传秘密配置。
        for (String unsafe : List.of("config.local.json", ".env.local", "signing.jks", "node_modules/source.js")) {
            String invalid = valid.replace("Example.kt", unsafe); // 不接触实际秘密内容。
            assertEquals("ANALYSIS_REFERENCE_INVALID", assertThrows(QueryValidationException.class,
                    () -> runs.complete(identity,claimed.run().runId(),new AnalysisRunService.Completion(lease,invalid,AnalysisEvidence.digest(invalid),false,true))).getCode());
        }
        String oldField = valid.substring(0,valid.length()-1)+",\"commitSha\":\""+"a".repeat(40)+"\"}"; // 已删除字段。
        assertEquals("INVALID_ANALYSIS_RESULT",assertThrows(QueryValidationException.class,
                () -> runs.complete(identity,claimed.run().runId(),new AnalysisRunService.Completion(lease,oldField,AnalysisEvidence.digest(oldField),false,true))).getCode());
    }

    /** 旧报告保留任意原版本字段，历史读取不使用新记录类丢掉旧来源。 */
    @Test
    void readsLegacyReportJsonWithoutRewritingIt() {
        var task = tasks.create(appId,"legacy-history",UUID.randomUUID(),owner); // 独立测试事件。
        var identity = credentials.authenticate(credentials.create(appId,"history",owner).credential()); // 独立身份。
        var claimed = runs.claim(identity,task.taskId(),UUID.randomUUID()); // 当前分配用于构造旧终态。
        var lease = new AnalysisRunService.Lease(claimed.run().leaseGeneration(),claimed.leaseToken()); // 原租约。
        stopAndConfirm(identity,claimed.run().runId(),lease,"CANCELLED");
        String legacy = "{\"schemaVersion\":1,\"commitSha\":\""+"a".repeat(40)+"\",\"execution\":{\"opencodeVersion\":\"historical\"}}"; // 历史原字节。
        jdbc.update("UPDATE crash_analysis_run SET result=?::jsonb,result_text=?,result_sha256=? WHERE run_id=?",legacy,legacy,AnalysisEvidence.digest(legacy),claimed.run().runId());
        var history = runs.history(appId,task.taskId(),owner,0,20).getFirst().result(); // 新历史只读 JSON。
        assertEquals("a".repeat(40),history.path("commitSha").asText());
        assertEquals("historical",history.path("execution").path("opencodeVersion").asText());
        assertEquals(legacy,jdbc.queryForObject("SELECT result_text FROM crash_analysis_run WHERE run_id=?",String.class,claimed.run().runId()));
    }

}
