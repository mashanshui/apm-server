package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.domain.UserStatus;
import com.shanshui.apmserver.identity.internal.persistence.AppIngestCredentialRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolAuditRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证符号表网页接口的权限、幂等上传、冲突确认和版本条件更新。 */
@SpringBootTest(properties = {"apm.symbol.max-file-bytes=32", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SymbolApiIntegrationTests extends AppIngestApiTestSupport {
    /** 文件和元数据均为隔离测试资源。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 避免测试清理任何现有开发 mapping。 */
    private static final java.nio.file.Path SYMBOL_DIRECTORY = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "symbols-api-" + UUID.randomUUID());

    /** 使用真实迁移和单独的文件目录。 */
    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("apm.symbol.directory", SYMBOL_DIRECTORY::toString);
    }

    /** 供接口测试使用的最小合法 mapping。 */
    private static final byte[] FIRST_MAPPING = "com.example.App -> a:\n"
            .getBytes(StandardCharsets.UTF_8);
    /** 替换接口使用的第二份合法 mapping。 */
    private static final byte[] SECOND_MAPPING = "com.example.App -> b:\n"
            .getBytes(StandardCharsets.UTF_8);

    /** 网页接口测试客户端。 */
    @Autowired
    private MockMvc mockMvc;
    /** 应用用户仓库。 */
    @Autowired
    private AppUserRepository userRepository;
    /** 应用成员仓库。 */
    @Autowired
    private AppMemberRepository memberRepository;
    /** 应用凭据仓库。 */
    @Autowired
    private AppIngestCredentialRepository credentialRepository;
    /** 应用仓库。 */
    @Autowired
    private ApmAppRepository appRepository;
    /** 用户密码编码器。 */
    @Autowired
    private PasswordEncoder passwordEncoder;
    /** 符号表当前版本仓库。 */
    @Autowired
    private SymbolFileRepository symbolFileRepository;
    /** 符号表审计仓库。 */
    @Autowired
    private SymbolAuditRepository symbolAuditRepository;
    /** 符号文件卷清理器。 */
    @Autowired
    private SymbolFileStore symbolFileStore;
    /** 响应 JSON 读取器。 */
    @Autowired
    private ObjectMapper objectMapper;
    /** 共用文件读取注册表。 */
    @Autowired private com.shanshui.apmserver.symbol.api.SymbolRegistry registry;
    /** 真实后台任务和审计查询。 */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    /** 控制计算延迟，验证上传不依赖后台完成。 */
    @MockitoSpyBean private com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine retrace;
    /** 在真实事务中注入登记失败，验证元数据与审计回滚。 */
    @MockitoSpyBean private com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs jobs;
    /** 控制真实事务提交后回执未知的文件保护测试。 */
    @MockitoSpyBean private com.shanshui.apmserver.symbol.internal.application.SymbolRecordWriter writer;
    /** 新事务用于稳定模拟数据库已经提交、调用方却收到失败的窗口。 */
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    /** 与 Crash 前台共用的当前数据集。 */
    @MockitoSpyBean private com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore states;
    /** 验证管理操作不会使详情重新依赖文件。 */
    @Autowired private com.shanshui.apmserver.crash.api.CrashQueries crashes;

    /** 每个测试使用全新的应用并清理数据库和受控文件。 */
    @BeforeEach
    void resetManagementData() {
        symbolAuditRepository.deleteAll();
        symbolFileRepository.deleteAll();
        memberRepository.deleteAll();
        credentialRepository.deleteAll();
        appRepository.deleteAll();
        symbolFileStore.collectOrphans(Set.of());
        resetAppCredential("com.example.symbol");
    }

    /** 首次上传、相同字节幂等、不同字节冲突和确认替换应保持版本语义。 */
    @Test
    void uploadsIdempotentlyAndReplacesWithExpectedRevision() throws Exception {
        MockHttpSession session = login("test@example.com", "Test-password-123!");

        MvcResult created = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-1", "mapping.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.buildId").value("release-1"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn();
        String symbolId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("symbolId").asText();

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-1", "retry.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbolId").value(symbolId))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(uploadRequest(appId(), SECOND_MAPPING, "release-1", "new.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYMBOL_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].symbolId").value(symbolId))
                .andExpect(jsonPath("$.errors[0].sha256").isString());

        mockMvc.perform(replaceRequest(appId(), symbolId, SECOND_MAPPING, 1, "new.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        mockMvc.perform(replaceRequest(appId(), symbolId, FIRST_MAPPING, 1, "stale.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYMBOL_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].revision").value(2));

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-2", "second.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated());

        MvcResult firstPage = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .param("limit", "1")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String nextCursor = objectMapper.readTree(firstPage.getResponse().getContentAsString())
                .get("nextCursor").asText();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .param("cursor", nextCursor).param("limit", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    /** 普通成员只能查询，App Key 和缺少 CSRF 均不得写入符号表。 */
    @Test
    void enforcesViewOnlyRoleCsrfAndSessionBoundaries() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MockHttpSession viewer = createMemberAndLogin(appId(), AppRole.VIEWER, "symbol-viewer");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .session(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "viewer-build", "mapping.txt")
                        .session(viewer).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "missing-csrf", "mapping.txt")
                        .session(owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "app-key", "mapping.txt")
                        .header("X-App-Key", appKey())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + UUID.randomUUID() + "/symbols")
                        .session(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    /** 非法 buildId、空 mapping 和超限文件在写入数据库前被拒绝。 */
    @Test
    void rejectsInvalidBuildIdEmptyMappingAndOversizedFile() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "../escape", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BUILD_ID"));
        mockMvc.perform(uploadRequest(appId(), new byte[0], "empty", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MAPPING"));
        mockMvc.perform(uploadRequest(appId(), new byte[40], "too-large", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("MAPPING_TOO_LARGE"));
    }

    /** 提交已完成但调用方未获确认时，503 不能导致删除已引用的新文件。 */
    @Test
    void preservesPublishedFileWhenMetadataCommitAcknowledgementIsUnknown() throws Exception {
        var owner = login("test@example.com", "Test-password-123!");
        doAnswer(call -> {
            var committed = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            committed.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            committed.execute(status -> {
                try { return call.callRealMethod(); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            });
            throw new org.springframework.dao.DataAccessResourceFailureException("受控提交确认丢失");
        }).when(writer).create(eq(appId()), any(), eq("commit-unknown"), anyString(), any(), anyString());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "commit-unknown", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SYMBOL_STORE_UNAVAILABLE"));
        var current = symbolFileRepository.findByAppIdAndBuildIdAndDeletedAtIsNull(appId(), "commit-unknown").orElseThrow();
        assertEquals(1, current.getRevision());
        assertEquals("queued", jobs.progress(appId(), "commit-unknown").state());
        try (var lease = registry.acquire(appId(), "commit-unknown").orElseThrow()) {
            assertTrue(java.nio.file.Files.isRegularFile(lease.path()));
            assertEquals(current.getSha256(), lease.sha256());
        }
    }

    /** 轮询仅读构建主键记录；不同状态、未知资源和应用权限均受控。 */
    @Test
    void pollsBoundedProgressWithoutReadingCrashPayloads() throws Exception {
        var owner = login("test@example.com", "Test-password-123!");
        var created = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "progress-build", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated()).andReturn();
        var id = objectMapper.readTree(created.getResponse().getContentAsString()).path("symbolId").asText();
        var route = "/api/v1/apps/" + appId() + "/symbols/" + id + "/crash-symbolication";
        clearInvocations(states);
        mockMvc.perform(MockMvcRequestBuilders.get(route)).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get(route).session(owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andExpect(jsonPath("$.state").value("queued"));
        var lease = jobs.claim(UUID.randomUUID(), java.time.Duration.ofMinutes(1)).orElseThrow();
        mockMvc.perform(MockMvcRequestBuilders.get(route).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("running"));
        assertTrue(jobs.release(lease, "retry_wait", "temporary_failure", java.time.Duration.ofSeconds(30)));
        mockMvc.perform(MockMvcRequestBuilders.get(route).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("retry_wait")).andExpect(jsonPath("$.errorCategory").value("temporary_failure"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + UUID.randomUUID() + "/symbols/" + id + "/crash-symbolication").session(owner)).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols/" + UUID.randomUUID() + "/crash-symbolication").session(owner)).andExpect(status().isNotFound());
        verifyNoInteractions(states);
    }

    /** 创建一个 multipart 首次上传请求。 */
    @Test
    void deletesWithRevisionAndRetiresOnlyAfterTheLastLease() throws Exception {
        // 首次上传只登记排队工作，进度查询不读取遥测。
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MockHttpSession viewer = createMemberAndLogin(appId(), AppRole.VIEWER, "delete-viewer");
        MvcResult created = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "delete-build", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated()).andReturn();
        UUID oldId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("symbolId").asText());
        String route = "/api/v1/apps/" + appId() + "/symbols/" + oldId;
        mockMvc.perform(MockMvcRequestBuilders.get(route + "/crash-symbolication").session(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("queued"))
                .andExpect(jsonPath("$.succeededCount").value(0));
        mockMvc.perform(MockMvcRequestBuilders.delete(route).param("expectedRevision", "1").session(owner)).andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.delete(route).param("expectedRevision", "1").session(viewer)
                .with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.delete(route).param("expectedRevision", "2").session(owner)
                .with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isConflict());
        // 已领取旧文件的一批可以完成，新领取则必须看不到软删除记录。
        var lease = registry.acquire(appId(), "delete-build").orElseThrow();
        var path = lease.path();
        // 构造已完成的同源事件，删除后仍须只读该固定结果。
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var metadata = new com.shanshui.apmserver.telemetry.api.EventMetadata(appId(), "com.example.symbol", "fixed-event", "crash", now.minusSeconds(1), now,
                2, "session", CrashTestSupport.PROCESS_ID, "test-device", "1", 1, "delete-build", "production", "official", "16", "test", "wifi", java.util.Map.of(), java.util.Map.of());
        var pending = new com.shanshui.apmserver.crash.internal.domain.CrashState(appId(), "fixed-event", "delete-build", metadata.occurredAt(), 0, "pending", "mapping_missing",
                "raw_chain", objectMapper.writeValueAsString(CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a")), "java.lang.RuntimeException", "tmp", "tmp-v1", "temporary", null, null, null, null, null);
        states.accept(new com.shanshui.apmserver.crash.internal.domain.CrashFact(metadata, "jvm", true), pending);
        states.publish(new com.shanshui.apmserver.crash.internal.domain.CrashState(appId(), "fixed-event", "delete-build", metadata.occurredAt(), 1, "symbolicated", null,
                "retrace_text", "java.lang.RuntimeException\n\tat com.example.App.run(App.java:1)", "java.lang.RuntimeException", "fixed", "resolved-v1", "resolved", oldId, 1, lease.sha256(), now, null));
        var fixed = crashes.event(appId(), "fixed-event");
        mockMvc.perform(MockMvcRequestBuilders.delete(route).param("expectedRevision", "1").session(owner)
                .with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(path));
        org.junit.jupiter.api.Assertions.assertTrue(registry.acquire(appId(), "delete-build").isEmpty());
        assertEquals(com.shanshui.apmserver.symbol.api.SymbolicationResult.Status.SYMBOLICATED,
                registry.retrace(lease, java.util.List.of("at a.b(SourceFile:1)")).status());
        mockMvc.perform(MockMvcRequestBuilders.get(route + "/crash-symbolication").session(owner)).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        lease.close();
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(path));
        MvcResult uploaded = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "delete-build", "again.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1)).andReturn();
        UUID newId = UUID.fromString(objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("symbolId").asText());
        org.junit.jupiter.api.Assertions.assertNotEquals(oldId, newId);
        assertEquals(fixed, crashes.event(appId(), "fixed-event"));
        org.junit.jupiter.api.Assertions.assertNotNull(symbolFileRepository.findById(oldId).orElseThrow().getDeletedAt());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE symbol_id=? AND action='DELETE'", Integer.class, oldId));
        mockMvc.perform(MockMvcRequestBuilders.delete(route).param("expectedRevision", "1").session(owner)
                .with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/apps/" + UUID.randomUUID() + "/symbols/" + newId)
                .param("expectedRevision", "1").session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNotFound());
    }

    /** 创建一个 multipart 首次上传请求。 */
    @Test
    void uploadDoesNotWaitForRetraceAndRollsBackWhenRegistrationFails() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "slow-build", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated());
        // 一个后台计算已占用共享许可，另一个上传仍能校验并独立提交。
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return com.shanshui.apmserver.symbol.api.SymbolicationResult.failed("output_limit");
        }).when(retrace).retrace(any(), anyList(), anyLong());
        try (var mapping = registry.acquire(appId(), "slow-build").orElseThrow();
             var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var slow = executor.submit(() -> registry.retrace(mapping, java.util.List.of("at a.b(SourceFile:1)")));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                var uploaded = executor.submit(() -> mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "quick-build", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andReturn().getResponse().getStatus());
                assertEquals(201, uploaded.get(3, java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
            slow.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        // 登记失败不能报告上传成功，也不能留下活动元数据或审计。
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("受控登记故障"))
                .when(jobs).wake(appId(), "registration-failure");
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "registration-failure", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=? AND build_id='registration-failure'", Integer.class, appId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=? AND build_id='registration-failure'", Integer.class, appId()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
    }

    /** 同版本删除与替换只有一个成功，旧请求不能操作重新上传后的身份。 */
    @Test
    void serializesConcurrentDeleteAndReplacement() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MvcResult created = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "concurrent", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated()).andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("symbolId").asText();
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/apps/" + appId() + "/symbols/" + id)
                        .param("expectedRevision", "1").session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andReturn().getResponse().getStatus();
            });
            var replacement = executor.submit(() -> {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(replaceRequest(appId(), id, SECOND_MAPPING, 1, "new.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andReturn().getResponse().getStatus();
            });
            int deleted = deletion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int replaced = replacement.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((deleted == 204 && (replaced == 404 || replaced == 409)) || (replaced == 200 && deleted == 409), deleted + "/" + replaced);
        }
    }

    /** 创建一个 multipart 首次上传请求。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadRequest(
            UUID appId, byte[] content, String buildId, String filename) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/{appId}/symbols", appId)
                .file(new MockMultipartFile("buildId", "", MediaType.TEXT_PLAIN_VALUE,
                        buildId.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE, content));
    }

    /** 创建一个 multipart 确认替换请求。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder replaceRequest(
            UUID appId, String symbolId, byte[] content, int expectedRevision, String filename) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/{appId}/symbols/{symbolId}", appId, symbolId)
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                })
                .file(new MockMultipartFile("expectedRevision", "", MediaType.TEXT_PLAIN_VALUE,
                        Integer.toString(expectedRevision).getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE, content));
    }

    /** 使用测试管理员登录网页 Session。 */
    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    /** 创建指定角色的应用成员并登录。 */
    private MockHttpSession createMemberAndLogin(UUID appId, AppRole role, String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        String password = "Member-password-123!";
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        userRepository.save(new AppUser(userId, email, prefix, passwordEncoder.encode(password),
                UserStatus.ACTIVE, now, now));
        memberRepository.save(new AppMember(new AppMemberId(appId, userId), role, now));
        return login(email, password);
    }
}
