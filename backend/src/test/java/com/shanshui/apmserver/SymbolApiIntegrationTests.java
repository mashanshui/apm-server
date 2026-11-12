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
    @MockitoSpyBean
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
    /** 新版本选择器暂供隔离测试，待后台与 Jank 接入。 */
    @Autowired private com.shanshui.apmserver.symbol.internal.application.SymbolRegistryService versionedRegistry;
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

    /** 注入 SO 提交确认丢失，核对可能已引用文件不能被删除。 */
    @MockitoSpyBean private com.shanshui.apmserver.symbol.internal.application.NativeBatchWriter nativeWriter;
    /** 实际 1.0.4 解析器及统一注册表。 */
    @Autowired private com.shanshui.apmserver.jank.internal.artifact.StackArtifactParseService artifacts;
    /** 当前 Jank 固定证据。 */
    @Autowired private com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository janks;

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

    /** HTTP 查询按真实记录返回默认、专用、SO 和旧无版本身份，不伪造构建。 */
    @Test
    void listsVersionedMetadataWithoutInventingIdentity() throws Exception {
        MockHttpSession session = login("test@example.com", "Test-password-123!");
        UUID defaultId = UUID.randomUUID();
        UUID buildId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();
        insertSymbolMetadata(defaultId, "MAPPING", "1.0", null, null, null);
        insertSymbolMetadata(buildId, "MAPPING", "1.0", "release/A", null, null);
        insertSymbolMetadata(nativeId, "NATIVE_SO", "1.0", null, "arm64-v8a", "abcd");
        insertSymbolMetadata(legacyId, "MAPPING", null, "legacy-build", null, null);
        MvcResult response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/{appId}/symbols", appId()).session(session))
                .andExpect(status().isOk()).andReturn();
        var entries = objectMapper.readTree(response.getResponse().getContentAsString()).path("items");
        var byId = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
        entries.forEach(value -> byId.put(value.path("symbolId").asString(), value));
        assertEquals("default", byId.get(defaultId.toString()).path("mappingScope").asString());
        assertTrue(byId.get(defaultId.toString()).path("buildId").isNull());
        assertEquals("build", byId.get(buildId.toString()).path("mappingScope").asString());
        assertEquals("release/A", byId.get(buildId.toString()).path("buildId").asString());
        assertEquals("NATIVE_SO", byId.get(nativeId.toString()).path("type").asString());
        assertTrue(byId.get(nativeId.toString()).path("buildId").isNull());
        assertEquals("abcd", byId.get(nativeId.toString()).path("elfBuildId").asString());
        assertTrue(byId.get(legacyId.toString()).path("appVersion").isNull());
        assertTrue(byId.get(legacyId.toString()).path("mappingScope").isNull());
    }

    /** 在隔离测试库插入不同类型的合成身份，避免将未来上传入口当作已完成。 */
    private void insertSymbolMetadata(UUID id, String type, String version, String build, String abi, String elfId) {
        boolean nativeFile = "NATIVE_SO".equals(type);
        jdbc.update("""
                INSERT INTO app_symbol_file(symbol_id,app_id,build_id,revision,storage_key,original_filename,
                    size_bytes,sha256,uploaded_by,uploaded_at,updated_at,symbol_type,app_version,native_abi,
                    elf_build_id,has_function_symbols,has_debug_info,has_debug_line)
                VALUES (?,?,?,1,?,'test',1,?,(SELECT created_by FROM apm_app WHERE app_id=?),now(),now(),?,?,?,?,?,?,?)
                """, id, appId(), build, id + ".mapping", "a".repeat(64), appId(), type, version, abi, elfId,
                nativeFile ? Boolean.TRUE : null, nativeFile ? Boolean.FALSE : null, nativeFile ? Boolean.FALSE : null);
    }

    /** 真库选优隔离版本和旧记录，并固定所选文件至租约释放。 */
    @Test
    void selectsVersionedMappingAndDoesNotFallbackFromUnreadableSpecificFile() throws Exception {
        UUID fallback = UUID.randomUUID();
        UUID specific = UUID.randomUUID();
        UUID otherVersion = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        insertSymbolMetadata(fallback, "MAPPING", "1.0", null, null, null);
        insertSymbolMetadata(specific, "MAPPING", "1.0", "release/A", null, null);
        insertSymbolMetadata(otherVersion, "MAPPING", "2.0", "release/A", null, null);
        insertSymbolMetadata(legacy, "MAPPING", null, "legacy-build", null, null);
        for (UUID id : java.util.List.of(fallback, specific, otherVersion, legacy)) {
            java.nio.file.Files.write(SYMBOL_DIRECTORY.resolve(id + ".mapping"), FIRST_MAPPING);
        }
        try (var selected = versionedRegistry.acquireVersioned(appId(), "1.0", "release/A").orElseThrow();
             var missing = versionedRegistry.acquireVersioned(appId(), "1.0", "build-without-specific").orElseThrow();
             var different = versionedRegistry.acquireVersioned(appId(), "2.0", "release/A").orElseThrow();
             var unmatched = versionedRegistry.acquireVersioned(appId(), "1.0", "legacy-build").orElseThrow()) {
            assertEquals(specific, selected.symbolId());
            assertEquals(fallback, missing.symbolId());
            assertEquals(otherVersion, different.symbolId());
            assertEquals(fallback, unmatched.symbolId());
            assertTrue(versionedRegistry.acquireVersioned(appId(), "3.0", "legacy-build").isEmpty());
            // 删除登记后，已经取得的租约仍可读，后续读取回退到默认来源。
            jdbc.update("UPDATE app_symbol_file SET deleted_at=now() WHERE symbol_id=?", specific);
            symbolFileStore.retire(specific + ".mapping");
            assertTrue(java.nio.file.Files.isReadable(selected.path()));
            try (var next = versionedRegistry.acquireVersioned(appId(), "1.0", "release/A").orElseThrow()) {
                assertEquals(fallback, next.symbolId());
            }
        }
        assertFalse(java.nio.file.Files.exists(SYMBOL_DIRECTORY.resolve(specific + ".mapping")));
        UUID broken = UUID.randomUUID();
        insertSymbolMetadata(broken, "MAPPING", "1.0", "broken", null, null);
        assertThrows(com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException.class,
                () -> versionedRegistry.acquireVersioned(appId(), "1.0", "broken"));
        assertThrows(com.shanshui.apmserver.symbol.api.SymbolValidationException.class,
                () -> versionedRegistry.acquireVersioned(appId(), " 1.0", "release/A"));
        assertThrows(com.shanshui.apmserver.symbol.api.SymbolValidationException.class,
                () -> versionedRegistry.acquireVersioned(appId(), "1.0", null));
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
                .andExpect(jsonPath("$.type").value("MAPPING"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn();
        var createdMetadata = objectMapper.readTree(created.getResponse().getContentAsString());
        // 新上传必须明确版本，构建专用范围不能误表示为默认。
        org.junit.jupiter.api.Assertions.assertTrue(createdMetadata.has("appVersion") && createdMetadata.get("appVersion").asText().equals("1"));
        org.junit.jupiter.api.Assertions.assertTrue(createdMetadata.has("mappingScope") && createdMetadata.get("mappingScope").asText().equals("build"));
        String symbolId = createdMetadata.get("symbolId").asText();

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

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, " bad", "mapping.txt")
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
        }).when(writer).create(eq(appId()), any(), anyString(), eq("commit-unknown"), anyString(), any(), anyString());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "commit-unknown", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SYMBOL_STORE_UNAVAILABLE"));
        var current = symbolFileRepository.findByAppIdAndBuildIdAndDeletedAtIsNull(appId(), "commit-unknown").orElseThrow();
        assertEquals(1, current.getRevision());
        assertEquals("queued", jobs.progress(appId(), "1").state());
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
                .when(jobs).wake(appId(), "1");
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "registration-failure", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=? AND build_id='registration-failure'", Integer.class, appId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=? AND build_id='registration-failure'", Integer.class, appId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
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

    /** 默认与专用按版本共存，空登记构建不放宽事件构建。 */
    @Test
    void uploadsDefaultAndSpecificMappingWithIndependentRevisions() throws Exception {
        var owner = login("test@example.com", "Test-password-123!");
        var request = MockMvcRequestBuilders.multipart("/api/v1/apps/" + appId() + "/symbols")
                .file(new MockMultipartFile("appVersion", "", "text/plain", "2.0".getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "mapping.txt", "text/plain", FIRST_MAPPING));
        var created = mockMvc.perform(request.session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.buildId").isEmpty())
                .andExpect(jsonPath("$.mappingScope").value("default")).andReturn();
        var defaultId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("symbolId").asText());
        var other = mockMvc.perform(uploadRequest(appId(), SECOND_MAPPING, "b", "mapping.txt")
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated()).andReturn();
        try (var lease = registry.acquireVersioned(appId(), "2.0", "b").orElseThrow()) { assertEquals(defaultId, lease.symbolId()); }
        assertTrue(registry.acquireVersioned(appId(), "3.0", "b").isEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols").param("appVersion", "2.0")
                .param("mappingScope", "default").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols").param("type", "NATIVE_SO")
                .param("buildId", "b").session(owner)).andExpect(status().isBadRequest());
        assertNotEquals(defaultId.toString(), objectMapper.readTree(other.getResponse().getContentAsString()).path("symbolId").asText());
    }

    /** 四 ABI 文件来自实际 NDK，SO 不生成 JVM 工作，重复字节不增加审计。 */
    @Test
    void savesNativeBatchAtomicallyAndRequiresEveryExpectedIdentity() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var owner = login("test@example.com", "Test-password-123!");
        byte[] zip = nativeZip(java.nio.file.Path.of(fixture), false);
        var viewer = createMemberAndLogin(appId(), AppRole.VIEWER, "native-viewer");
        mockMvc.perform(nativeRequest(zip, null).session(viewer).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isForbidden());
        mockMvc.perform(nativeRequest(zip, null).session(owner)).andExpect(status().isForbidden());
        var created = mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.createdCount").value(4)).andReturn();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
        var items = objectMapper.readTree(created.getResponse().getContentAsString()).path("items");
        assertEquals(4, items.size());
        var expected = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (var item : items) {
            assertTrue(item.path("buildId").isNull());
            expected.add(java.util.Map.of("abi", item.path("abi").asText(), "elfBuildId", item.path("elfBuildId").asText(),
                    "symbolId", item.path("symbolId").asText(), "revision", 1));
        }
        mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unchangedCount").value(4));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=?", Integer.class, appId()));
        var changedZip = nativeZipWithNewIdentity(java.nio.file.Path.of(fixture));
        mockMvc.perform(nativeRequest(changedZip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.length()").value(5));
        var addedElf = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(java.nio.file.Path.of(fixture).resolve("arm64-v8a/libapm_long_id.so"));
        var absent = new java.util.HashMap<String,Object>();
        absent.put("abi", addedElf.abi()); absent.put("elfBuildId", addedElf.elfBuildId()); absent.put("symbolId", null); absent.put("revision", null);
        expected.add(absent);
        mockMvc.perform(nativeRequest(changedZip, objectMapper.writeValueAsString(expected.subList(0,3)))
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isBadRequest());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=?", Integer.class, appId()));
        mockMvc.perform(nativeRequest(changedZip, objectMapper.writeValueAsString(expected))
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1)).andExpect(jsonPath("$.replacedCount").value(1)).andExpect(jsonPath("$.unchangedCount").value(3));
        // 过期确认即使内容相同也不能充当覆盖授权；普通重传才用于提交未知对账。
        mockMvc.perform(nativeRequest(changedZip, objectMapper.writeValueAsString(expected))
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isConflict());
        mockMvc.perform(nativeRequest(changedZip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unchangedCount").value(5));
        String id = items.get(0).path("symbolId").asText();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols/" + id + "/crash-symbolication").session(owner))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SYMBOL_PROGRESS_NOT_APPLICABLE"));
        mockMvc.perform(nativeRequest(zip, null).file(new MockMultipartFile("buildId", "", "text/plain", "b".getBytes()))
                .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isBadRequest());
    }

    /** 并发默认登记只创建一条活动身份；游标拒绝任意筛选变化。 */
    @Test
    void coalescesConcurrentDefaultsAndBindsAllCursorFilters() throws Exception {
        var owner = login("test@example.com", "Test-password-123!");
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var operations = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 2; index++) operations.add(executor.submit(() -> {
                gate.await();
                return mockMvc.perform(defaultRequest("2.0", FIRST_MAPPING).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andReturn().getResponse().getStatus();
            }));
            var codes = java.util.List.of(operations.get(0).get(), operations.get(1).get());
            assertTrue(codes.contains(201) && codes.contains(200), codes.toString());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, appId()));
        mockMvc.perform(defaultRequest("3.0", FIRST_MAPPING).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated());
        var first = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols").param("limit", "1").session(owner))
                .andExpect(status().isOk()).andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString()).path("nextCursor").asText();
        for (var filter : java.util.Map.of("type", "MAPPING", "appVersion", "2.0", "mappingScope", "default", "buildId", "b", "abi", "arm64-v8a", "elfBuildId", "aabb").entrySet()) {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols").param("cursor", cursor)
                    .param(filter.getKey(), filter.getValue()).session(owner)).andExpect(status().isBadRequest());
        }
    }

    /** SO 的预期不存在项也参与原子确认；并发者不能凭旧快照覆盖新记录。 */
    @Test
    void rejectsConcurrentAbsentNativeSnapshotAndPreservesCommitUnknownFiles() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var owner = login("test@example.com", "Test-password-123!");
        var root = java.nio.file.Path.of(fixture);
        byte[] zip = nativeZip(root, false);
        var expected = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (String abi : java.util.List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
            var metadata = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(root.resolve(abi).resolve("libapm_fixture.so"));
            var condition = new java.util.HashMap<String,Object>();
            condition.put("abi", abi); condition.put("elfBuildId", metadata.elfBuildId()); condition.put("symbolId", null); condition.put("revision", null);
            expected.add(condition);
        }
        String confirmation = objectMapper.writeValueAsString(expected);
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return mockMvc.perform(nativeRequest(zip, confirmation).session(owner)
                    .with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { gate.await(); return mockMvc.perform(nativeRequest(zip, confirmation).session(owner)
                    .with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andReturn().getResponse().getStatus(); });
            // HTTP 接收许可可以先拒绝并发者；事务本身的缺失条件由后续旧快照检查。
            var codes = java.util.List.of(first.get(), second.get());
            assertTrue(codes.contains(201)); assertTrue(codes.contains(503) || codes.contains(409), codes.toString());
        }
        mockMvc.perform(nativeRequest(zip, confirmation).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isConflict());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=?", Integer.class, appId()));
        byte[] changed = nativeZip(root, true);
        var snapshot = symbolFileRepository.findAll().stream().map(file -> new com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected(
                file.metadata().abi(), file.metadata().elfBuildId(), file.getSymbolId(), file.getRevision())).toList();
        doAnswer(call -> {
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.execute(status -> { try { return call.callRealMethod(); } catch (Throwable failure) { throw new AssertionError(failure); } });
            throw new org.springframework.dao.DataAccessResourceFailureException("受控 SO 提交确认丢失");
        }).when(nativeWriter).commit(eq(appId()), any(), anyString(), anyList(), anyList(), anyLong());
        mockMvc.perform(nativeRequest(changed, objectMapper.writeValueAsString(snapshot)).session(owner)
                .with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isServiceUnavailable());
        org.mockito.Mockito.reset(nativeWriter);
        mockMvc.perform(nativeRequest(changed, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unchangedCount").value(4));
        for (var file : symbolFileRepository.findAll()) assertTrue(java.nio.file.Files.exists(symbolFileStore.root().resolve(file.getStorageKey())));
    }

    /** 绕过单实例 HTTP 许可直接并发真实事务，预期不存在项也必须阻止第二次创建。 */
    @Test
    void serializesAbsentNativeIdentitiesInsidePostgresTransactions() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var root = java.nio.file.Path.of(fixture);
        UUID user = userRepository.findByEmailNormalized("test@example.com").orElseThrow().getId();
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var operations = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 2; index++) operations.add(executor.submit(() -> {
                var candidates = new java.util.ArrayList<com.shanshui.apmserver.symbol.internal.application.NativeBatch.Candidate>();
                var expected = new java.util.ArrayList<com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected>();
                for (String abi : java.util.List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                    var path = root.resolve(abi).resolve("libapm_fixture.so");
                    var elf = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(path);
                    var staged = symbolFileStore.stage(java.nio.file.Files.newInputStream(path), 128L*1024*1024);
                    candidates.add(new com.shanshui.apmserver.symbol.internal.application.NativeBatch.Candidate("fixture.so", elf, staged, symbolFileStore.publish(staged)));
                    expected.add(new com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected(abi, elf.elfBuildId(), null, null));
                }
                gate.await();
                try { nativeWriter.commit(appId(), user, "concurrent-version", candidates, expected, System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos()); return 201; }
                catch (com.shanshui.apmserver.symbol.api.NativeBatchConflictException conflict) { assertEquals(4, conflict.entries().size()); return 409; }
            }));
            var codes = java.util.List.of(operations.get(0).get(), operations.get(1).get());
            assertTrue(codes.contains(201) && codes.contains(409), codes.toString());
        }
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, appId()));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=?", Integer.class, appId()));
        // 模拟重启清理：失败事务的未引用文件回收，已提交的四份不可删除。
        versionedRegistry.cleanOrphansOnStartup();
        try (var files = java.nio.file.Files.list(symbolFileStore.root())) { assertEquals(4, files.count()); }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
    }

    /** 实际 processor 1.0.4 使用默认及专用来源，后续上传不改已保存 Jank。 */
    @Test
    void actualProcessorKeepsJankEvidenceAcrossDefaultAndSpecificChanges() throws Exception {
        resetAppCredential("com.example.app");
        var owner = login("test@example.com", "Test-password-123!");
        mockMvc.perform(defaultRequest("1.0", FIRST_MAPPING).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated());
        byte[] zip = java.util.Base64.getMimeDecoder().decode(getClass().getResourceAsStream("/fixtures/rhea-jank-async-artifact.b64").readAllBytes());
        var authenticated = new com.shanshui.apmserver.identity.api.AuthenticatedApp(appId(), "com.example.app");
        assertEquals("accepted", artifacts.parse(authenticated, new java.io.ByteArrayInputStream(zip)).status());
        var fixed = janks.findJankByEventId(appId(), "synthetic-async-jank").orElseThrow();
        assertEquals("symbolicated", fixed.symbolicationStatus());
        var specific = MockMvcRequestBuilders.multipart("/api/v1/apps/" + appId() + "/symbols")
                .file(new MockMultipartFile("appVersion", "", "text/plain", "1.0".getBytes()))
                .file(new MockMultipartFile("buildId", "", "text/plain", "async-test-build".getBytes()))
                .file(new MockMultipartFile("file", "mapping.txt", "text/plain", SECOND_MAPPING));
        mockMvc.perform(specific.session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isCreated());
        assertEquals("duplicate", artifacts.parse(authenticated, new java.io.ByteArrayInputStream(zip)).status());
        assertEquals(fixed, janks.findJankByEventId(appId(), "synthetic-async-jank").orElseThrow());
        // 同一 1.0.4 产物仅换事件身份，新事件使用已上传的构建专用文件。
        var output = new java.io.ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));
             var archive = new java.util.zip.ZipOutputStream(output)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals("manifest.json")) {
                    var manifest = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(bytes);
                    manifest.put("eventId", "synthetic-specific-jank"); bytes = objectMapper.writeValueAsBytes(manifest);
                }
                archive.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); archive.write(bytes); archive.closeEntry();
            }
        }
        assertEquals("accepted", artifacts.parse(authenticated, new java.io.ByteArrayInputStream(output.toByteArray())).status());
        assertEquals("symbolicated", janks.findJankByEventId(appId(), "synthetic-specific-jank").orElseThrow().symbolicationStatus());
        // 查询故障不能被 processor 当成无 mapping 后保存 raw_only。
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("受控注册表故障"))
                .when(symbolFileRepository).lockPreferredMapping(eq(appId()), eq("1.0"), eq("async-test-build"), any());
        assertThrows(com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException.class,
                () -> artifacts.parse(authenticated, new java.io.ByteArrayInputStream(zip)));
    }

    /** 版本默认请求不发送 buildId part。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder defaultRequest(String version, byte[] mapping) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/" + appId() + "/symbols")
                .file(new MockMultipartFile("appVersion", "", "text/plain", version.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "mapping.txt", "text/plain", mapping));
    }

    /** 去重按模块身份计数；同身份不同摘要必须整批失败并清理暂存。 */
    @Test
    void countsUniqueNativeIdentitiesAndRejectsConflictingDuplicateBytes() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var owner = login("test@example.com", "Test-password-123!");
        byte[] elf = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(fixture).resolve("arm64-v8a/libapm_fixture.so"));
        var duplicated = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(duplicated)) {
            for (int index = 0; index < 129; index++) {
                zip.putNextEntry(new java.util.zip.ZipEntry("module-" + index + ".so")); zip.write(elf); zip.closeEntry();
            }
        }
        mockMvc.perform(nativeRequest(duplicated.toByteArray(), null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.createdCount").value(1));
        var conflict = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(conflict)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("first.so")); zip.write(elf); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("second.so")); zip.write(elf); zip.write(1); zip.closeEntry();
        }
        mockMvc.perform(nativeRequest(conflict.toByteArray(), null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SO_ARCHIVE_IDENTITY_CONFLICT"));
        // 只修改合法 note 的完整 ID，构造 129 个真实不同身份，必须在任何元数据写入前拒绝。
        String buildId = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(java.nio.file.Path.of(fixture).resolve("arm64-v8a/libapm_fixture.so")).elfBuildId();
        byte[] note = java.util.HexFormat.of().parseHex(buildId);
        int position = -1;
        for (int offset = 0; offset <= elf.length-note.length; offset++) {
            if (java.util.Arrays.equals(java.util.Arrays.copyOfRange(elf, offset, offset+note.length), note)) { position = offset; break; }
        }
        assertTrue(position >= 0);
        var excessive = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(excessive)) {
            for (int index = 0; index < 129; index++) {
                byte[] modified = elf.clone(); modified[position + note.length-1] = (byte) index;
                zip.putNextEntry(new java.util.zip.ZipEntry("unique-" + index + ".so")); zip.write(modified); zip.closeEntry();
            }
        }
        mockMvc.perform(nativeRequest(excessive.toByteArray(), null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_SYMBOL_ARCHIVE"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, appId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE app_id=?", Integer.class, appId()));
        try (var paths = java.nio.file.Files.list(symbolFileStore.root())) { assertEquals(1, paths.count()); }
    }

    /** 接收后的真实归档期限、ELF 中断及磁盘故障返回可重试错误，失败整批无副作用。 */
    @Test
    void retriesPostReceiveDeadlineInterruptAndStorageFailures() throws Exception {
        /** 使用真实四 ABI ZIP，内容有效性不依赖注入的异常。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        /** 授权会话及同一不可变上传材料。 */
        var owner = login("test@example.com", "Test-password-123!");
        byte[] zip = nativeZip(java.nio.file.Path.of(fixture), false);
        /** 真实解析器在正文接收后的第一步检查已过期的统一期限。 */
        var reader = new com.shanshui.apmserver.symbol.internal.archive.NativeArchiveReader();
        try (var construction = mockConstruction(com.shanshui.apmserver.symbol.internal.archive.NativeArchiveReader.class,
                (mock, context) -> when(mock.read(any(), any(), anyLong())).thenAnswer(call ->
                        reader.read(call.getArgument(0), call.getArgument(1), System.nanoTime() - 1)))) {
            assertTemporaryNativeFailure(zip, owner);
        }
        /** 已实际解压后，ELF 检查中的中断和读盘错误仍不能被报告成非法文件。 */
        for (java.io.IOException failure : java.util.List.of(new java.io.InterruptedIOException("fault-marker"), new java.io.EOFException("fault-marker"),
                new java.io.IOException("/private/fault-marker"))) {
            try (var construction = mockConstruction(com.shanshui.apmserver.symbol.internal.elf.ElfInspector.class,
                    (mock, context) -> when(mock.inspect(any(), anyLong())).thenThrow(failure))) {
                assertTemporaryNativeFailure(zip, owner);
            }
        }
        /** ELF 已成功检查后模拟暂存 SO 消失，实际摘要读取必须作为存储故障返回。 */
        var inspector = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector();
        try (var construction = mockConstruction(com.shanshui.apmserver.symbol.internal.elf.ElfInspector.class,
                (mock, context) -> when(mock.inspect(any(), anyLong())).thenAnswer(call -> {
                    /** 此处只删除当前测试刚解压的文件，不涉及应用已有符号。 */
                    java.nio.file.Path path = call.getArgument(0);
                    var metadata = inspector.inspect(path, call.getArgument(1));
                    java.nio.file.Files.delete(path);
                    return metadata;
                }))) {
            assertTemporaryNativeFailure(zip, owner);
        }
        // 同一 ZIP 普通重试成功，证明错误没有泄漏上传许可或留下部分身份。
        mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.createdCount").value(4));
        assertEquals(4, symbolFileRepository.count());
        assertEquals(4, symbolAuditRepository.count());
    }

    /** 检查统一安全响应、管理库无写入、暂存为空，避免只验证异常类而遗漏 HTTP 分类。 */
    private void assertTemporaryNativeFailure(byte[] zip, org.springframework.mock.web.MockHttpSession owner) throws Exception {
        /** 错误不包含底层异常消息或绝对路径。 */
        String body = mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("SYMBOL_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("fault-marker"));
        assertEquals(0, symbolFileRepository.count());
        assertEquals(0, symbolAuditRepository.count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, appId()));
        try (var paths = java.nio.file.Files.list(symbolFileStore.root())) { assertEquals(0, paths.count()); }
    }

    /** 损坏 ZIP 索引、CRC 和截断 ELF 仍为不可重试内容错误，不能因通用 I/O 修复变为 503。 */
    @Test
    void keepsMalformedArchiveAndTruncatedElfNonRetryable() throws Exception {
        /** 有效 ZIP 作为中央目录和 CRC 故障的共同基线。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var owner = login("test@example.com", "Test-password-123!");
        byte[] original = nativeZip(java.nio.file.Path.of(fixture), false);
        /** 通用 ZIP 库打开时的损坏索引异常，以及本地头与目录 CRC 不一致。 */
        byte[] central = original.clone();
        byte[] crc = original.clone();
        for (int offset = 0; offset < original.length - 4; offset++) {
            if (original[offset] == 'P' && original[offset + 1] == 'K' && original[offset + 2] == 1 && original[offset + 3] == 2) {
                central[offset] = 0;
                crc[offset + 16] ^= 1;
                break;
            }
        }
        for (byte[] invalid : java.util.List.of("not ZIP".getBytes(StandardCharsets.UTF_8), central, crc)) {
            mockMvc.perform(nativeRequest(invalid, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_SYMBOL_ARCHIVE"))
                    .andExpect(jsonPath("$.retryable").value(false));
        }
        /** 合法 ZIP 包含不足 ELF 文件头长度的 SO。 */
        var truncated = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(truncated)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("truncated.so")); zip.write(new byte[]{0x7f, 'E', 'L', 'F'}); zip.closeEntry();
        }
        mockMvc.perform(nativeRequest(truncated.toByteArray(), null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_SO"))
                .andExpect(jsonPath("$.retryable").value(false));
        assertEquals(0, symbolFileRepository.count());
        assertEquals(0, symbolAuditRepository.count());
        try (var paths = java.nio.file.Files.list(symbolFileStore.root())) { assertEquals(0, paths.count()); }
    }

    /** 元数据已提交后的清理故障返回可重试错误，普通重试对账且已引用文件不能被误删。 */
    @Test
    @SuppressWarnings("unchecked")
    void preservesCommittedNativeFilesWhenArchiveCleanupFails() throws Exception {
        /** 保持实际文件发布和 PostgreSQL 提交，只注入末尾归档目录关闭故障。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        var owner = login("test@example.com", "Test-password-123!");
        byte[] zip = nativeZip(java.nio.file.Path.of(fixture), false);
        try (var construction = mockConstruction(com.shanshui.apmserver.symbol.internal.archive.NativeArchive.class, (mock, context) -> {
            when(mock.entries()).thenReturn(java.util.List.copyOf((java.util.List<com.shanshui.apmserver.symbol.internal.archive.NativeArchive.Entry>) context.arguments().get(1)));
            doThrow(new java.io.IOException("fault-marker")).when(mock).close();
        })) {
            mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SYMBOL_STORE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(true));
        }
        assertEquals(4, symbolFileRepository.count());
        assertEquals(4, symbolAuditRepository.count());
        for (var symbol : symbolFileRepository.findAll()) {
            assertTrue(java.nio.file.Files.isRegularFile(symbolFileStore.root().resolve(symbol.getStorageKey())));
        }
        mockMvc.perform(nativeRequest(zip, null).session(owner).with(SecurityMockMvcRequestPostProcessors.csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unchangedCount").value(4));
        assertEquals(4, symbolAuditRepository.count());
        // 模拟重启后的受控目录清理，只保留管理库仍引用的四份文件。
        symbolFileStore.collectOrphans(symbolFileRepository::existsByStorageKeyAndDeletedAtIsNull);
        try (var paths = java.nio.file.Files.list(symbolFileStore.root())) { assertEquals(4, paths.count()); }
    }

    /** 构造完整候选集合，变更仅追加 ELF 非加载尾部字节，模块身份保持不变。 */
    private byte[] nativeZip(java.nio.file.Path root, boolean changed) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String abi : java.util.List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(abi + "/libminimal.so"));
                zip.write(java.nio.file.Files.readAllBytes(root.resolve(abi).resolve("libapm_fixture.so")));
                if (changed && abi.equals("arm64-v8a")) zip.write(1);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** 一个新增长 Build ID 与已有冲突模块混合，首请求必须整体无副作用。 */
    private byte[] nativeZipWithNewIdentity(java.nio.file.Path root) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(nativeZip(root, true)));
             var zip = new java.util.zip.ZipOutputStream(output)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); input.transferTo(zip); zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("arm64-v8a/libnew.so"));
            zip.write(java.nio.file.Files.readAllBytes(root.resolve("arm64-v8a/libapm_long_id.so"))); zip.closeEntry();
        }
        return output.toByteArray();
    }

    /** SO 请求始终使用原 ZIP 和完整确认 JSON。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder nativeRequest(byte[] zip, String expected) {
        var request = MockMvcRequestBuilders.multipart("/api/v1/apps/" + appId() + "/symbols/native-batches")
                .file(new MockMultipartFile("appVersion", "", "text/plain", "2.0".getBytes()))
                .file(new MockMultipartFile("file", "symbols.zip", "application/zip", zip));
        if (expected != null) request.file(new MockMultipartFile("expectedEntries", "", "text/plain", expected.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    /** 创建一个 multipart 首次上传请求。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadRequest(
            UUID appId, byte[] content, String buildId, String filename) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/{appId}/symbols", appId)
                .file(new MockMultipartFile("appVersion", "", MediaType.TEXT_PLAIN_VALUE, "1".getBytes(StandardCharsets.UTF_8)))
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
