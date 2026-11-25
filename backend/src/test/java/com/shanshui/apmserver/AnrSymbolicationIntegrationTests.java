package com.shanshui.apmserver;

import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.application.AnrSymbolicationProcessor;
import com.shanshui.apmserver.anr.internal.application.AnrSymbolicationWakeup;
import com.shanshui.apmserver.anr.internal.persistence.AnrSymbolicationPublications;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import com.shanshui.apmserver.identity.internal.application.AppManagementService;
import com.shanshui.apmserver.identity.internal.domain.AppCreateRequest;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 PostgreSQL、ClickHouse、mapping 选择和 R8 验证后台还原。 */
@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "apm.storage.mode=clickhouse", "apm.clickhouse.enabled=true", "apm.anr.background.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class AnrSymbolicationIntegrationTests extends AppIngestApiTestSupport {
    /** 隔离管理库。 */
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 隔离事件库。 */
    @Container private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);
    /** 隔离符号表文件目录。 */
    private static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "anr-symbol-test-" + UUID.randomUUID());
    /** HTTP 协议入口。 */ @Autowired private MockMvc mvc;
    /** 显式执行后台工作。 */ @Autowired private AnrSymbolicationProcessor processor;
    /** 对已恢复事件发起有界唤醒。 */ @Autowired private AnrSymbolicationWakeup wakeup;
    /** 实际派生状态存储。 */ @Autowired private AnrSymbolicationStore states;
    /** 实际管理库发布账本。 */ @Autowired private AnrSymbolicationPublications publications;
    /** 隔离管理库。 */ @Autowired private JdbcTemplate jdbc;
    /** 与服务端一致的 JSON 编解码。 */ @Autowired private ObjectMapper mapper;
    /** 管理员及第二个隔离应用。 */ @Autowired private AppUserRepository users;
    /** 测试创建第二个应用的入口。 */ @Autowired private AppManagementService apps;
    /** 用新实例验证后台重启恢复。 */ @Autowired private org.springframework.context.ApplicationContext context;

    /** 建立隔离数据库和现有领域必需迁移。 */
    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) throws Exception {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setUsername("test");
        properties.setPassword("test-only");
        properties.setDatabase("default");
        var client = new ClickHouseHttpClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "010_crash_diagnostics.sql",
                "011_native_crash_state.sql", "012_native_symbolication_state.sql", "013_anr_events.sql",
                "014_anr_symbolication_state.sql")) {
            String ddl;
            try (var stream = AnrSymbolicationIntegrationTests.class.getResourceAsStream("/db/clickhouse/" + name)) {
                ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("apm.clickhouse.url", properties::getUrl);
        registry.add("apm.clickhouse.database", () -> "apm");
        registry.add("apm.clickhouse.username", () -> "test");
        registry.add("apm.clickhouse.password", () -> "test-only");
        registry.add("apm.symbol.directory", DIRECTORY::toString);
    }

    /** 验证专用优先、默认回退、迟到 mapping 和跨库确认丢失。 */
    @Test
    void resolvesVersionedMappingsAndRecoversFixedPublication() throws Exception {
        resetAppCredential("com.example.app");
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        String fallback = upload(session, "1", null, "Default");
        String specific = upload(session, "1", "specific-build", "Specific");
        ingest("specific", "1", "specific-build");
        ingest("fallback", "1", "other-build");
        mvc.perform(get(detailPath("specific")).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mainThreadJava.status").value("pending"));
        for (int i = 0; i < 6; i++) processor.runOnce();
        JsonNode fixed = detail(session, "specific");
        assertThat(fixed.path("mainThreadJava").path("symbolFileId").asText()).isEqualTo(specific);
        assertThat(fixed.path("mainThreadJava").path("text").asText()).contains("Specific.run");
        assertThat(fixed.path("traceJava").path("text").asText()).contains("Specific.run", "- locked <0x1>");
        assertThat(detail(session, "fallback").path("mainThreadJava").path("symbolFileId").asText()).isEqualTo(fallback);
        replace(session, specific, "Changed");
        for (int i = 0; i < 3; i++) processor.runOnce();
        assertThat(detail(session, "specific").path("mainThreadJava")).isEqualTo(fixed.path("mainThreadJava"));

        // 在没有对应版本 mapping 时先确认原文，后续上传并模拟重试时间到达。
        ingest("late", "3", "late-build");
        wakeup.offer(appId(), "late");
        for (int i = 0; i < 4; i++) processor.runOnce();
        assertThat(detail(session, "late").path("mainThreadJava").path("status").asText()).isEqualTo("pending");
        String lateMapping = upload(session, "3", null, "Late");
        var pending = new AnrJavaView("pending", "mapping_missing", null, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(1));
        states.write(appId(), "late", Instant.now(), "main", System.currentTimeMillis() + 1, pending);
        states.write(appId(), "late", Instant.now(), "trace", System.currentTimeMillis() + 1, pending);
        wakeup.offer(appId(), "late");
        for (int i = 0; i < 5; i++) processor.runOnce();
        assertThat(detail(session, "late").path("mainThreadJava").path("symbolFileId").asText()).isEqualTo(lateMapping);
        assertThat(detail(session, "late").path("mainThreadJava").path("text").asText()).contains("Late.run");

        // 同版本专用文件不可读时报告失败，不使用同版本默认 mapping。
        String brokenMapping = upload(session, "1", "broken-build", "Broken");
        String storageKey = jdbc.queryForObject("SELECT storage_key FROM app_symbol_file WHERE symbol_id=?", String.class,
                UUID.fromString(brokenMapping));
        Files.delete(DIRECTORY.resolve(storageKey));
        ingest("broken", "1", "broken-build");
        wakeup.offer(appId(), "broken");
        for (int i = 0; i < 4; i++) processor.runOnce();
        assertThat(detail(session, "broken").path("mainThreadJava").path("status").asText()).isEqualTo("failed");
        assertThat(detail(session, "broken").path("mainThreadJava").path("reason").asText()).isEqualTo("mapping_unavailable");

        // 其他应用的同版本 mapping 不能用于当前应用。
        UUID user = users.findByEmailNormalized("test@example.com").orElseThrow().getId();
        UUID otherApp = apps.create(user, new AppCreateRequest("com.example.other")).appId();
        upload(session, otherApp, "4", null, "OtherApp");
        ingest("other-version", "4", "other-build");
        wakeup.offer(appId(), "other-version");
        for (int i = 0; i < 5; i++) processor.runOnce();
        assertThat(detail(session, "other-version").path("mainThreadJava").path("status").asText()).isEqualTo("pending");

        // 模拟 ClickHouse 已写入但管理库尚未收到确认，重新创建的发布操作只重放首次字节。
        Instant at = Instant.now();
        UUID originalSource = UUID.fromString(lateMapping);
        var first = new AnrJavaView("symbolicated", null, "first-result", originalSource, 1, "a".repeat(64), at, null);
        var publication = publications.register(appId(), "publication-recovery", at, "main", first).orElseThrow();
        states.write(appId(), "publication-recovery", at, "main", AnrSymbolicationPublications.FINAL_VERSION, first);
        var changed = new AnrJavaView("symbolicated", null, "changed-result", UUID.randomUUID(), 2, "b".repeat(64), at, null);
        assertThat(publications.register(appId(), "publication-recovery", at, "main", changed).orElseThrow().sha256())
                .isEqualTo(publication.sha256());
        var restarted = context.getAutowireCapableBeanFactory().createBean(AnrSymbolicationProcessor.class);
        restarted.runOnce();
        assertThat(states.find(appId(), "publication-recovery", "main").orElseThrow()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT pending_payload IS NULL FROM anr_symbolication_publication WHERE app_id=? AND event_id=? AND section='main'",
                Boolean.class, appId(), "publication-recovery")).isTrue();
        mvc.perform(get("/api/v1/apps/" + appId() + "/anrs/overview").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(5));
    }

    /** 上传一份合成 mapping 并返回实际文件标识。 */
    private String upload(MockHttpSession session, String version, String build, String className) throws Exception {
        return upload(session, appId(), version, build, className);
    }

    /** 向指定应用上传一份合成 mapping。 */
    private String upload(MockHttpSession session, UUID app, String version, String build, String className) throws Exception {
        String mapping = "com.example." + className + " -> a:\n    void run() -> b\n";
        var request = multipart("/api/v1/apps/" + app + "/symbols")
                .file(new MockMultipartFile("appVersion", "", "text/plain", version.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "mapping.txt", "text/plain", mapping.getBytes(StandardCharsets.UTF_8)));
        if (build != null) request.file(new MockMultipartFile("buildId", "", "text/plain", build.getBytes(StandardCharsets.UTF_8)));
        var response = mvc.perform(request.session(session).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsByteArray()).path("symbolId").asText();
    }

    /** 替换已登记 mapping 的修订。 */
    private void replace(MockHttpSession session, String symbolId, String className) throws Exception {
        String mapping = "com.example." + className + " -> a:\n    void run() -> b\n";
        var request = multipart("/api/v1/apps/" + appId() + "/symbols/" + symbolId)
                .file(new MockMultipartFile("expectedRevision", "", "text/plain", "1".getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "mapping.txt", "text/plain", mapping.getBytes(StandardCharsets.UTF_8)))
                .with(value -> { value.setMethod("PUT"); return value; });
        mvc.perform(request.session(session).with(csrf())).andExpect(status().isOk());
    }

    /** 通过真实批次入口提交可还原的合成栈。 */
    private void ingest(String id, String version, String build) throws Exception {
        String body = """
                {"requestId":"anr-symbol","events":[{"schemaVersion":2,"eventId":"%s","eventType":"anr",
                 "occurredAt":%d,"sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"%s","versionCode":1,
                 "buildId":"%s","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic",
                 "anr":{"source":"message_timeout","mainThreadStack":"a.b(SourceFile:1)\\n","mainThreadStackTruncated":false,
                 "trace":{"status":"ok","truncated":false,"source":"art_first_write",
                 "text":"\\\"main\\\" prio=5 tid=1 Runnable\\n  at a.b(SourceFile:1)\\n  - locked <0x1>\\n"}}}]}
                """.formatted(id, System.currentTimeMillis(), version, build);
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
    }

    /** 读取授权事件详情。 */
    private JsonNode detail(MockHttpSession session, String eventId) throws Exception {
        var response = mvc.perform(get(detailPath(eventId)).session(session)).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsByteArray());
    }

    /** 构造应用内事件详情地址。 */
    private String detailPath(String eventId) {
        return "/api/v1/apps/" + appId() + "/anrs/events/" + eventId;
    }
}
