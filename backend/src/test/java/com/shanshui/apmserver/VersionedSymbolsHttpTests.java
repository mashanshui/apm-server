package com.shanshui.apmserver;

import com.shanshui.apmserver.platform.api.*;
import com.shanshui.apmserver.crash.internal.application.CrashBackgroundProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 PostgreSQL/ClickHouse 和真实 Servlet，核验符号管理与已有遥测查询。 */
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.autoconfigure.exclude=",
        "spring.session.jdbc.initialize-schema=never", "apm.storage.mode=clickhouse", "apm.clickhouse.enabled=true",
        "apm.agent.query.enabled=true", "server.address=127.0.0.1",
        "spring.servlet.multipart.max-file-size=256MB", "spring.servlet.multipart.max-request-size=260MB"})
class VersionedSymbolsHttpTests {
    /** 每次测试独立管理库。 */
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 每次测试独立事实和状态库。 */
    @Container private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);
    /** 隔离临时文件卷。 */
    private static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "versioned-symbol-http-" + UUID.randomUUID());
    /** 本次运行首阶段成功后才可供重启与慢上传测试使用的证据。 */
    private static JsonNode currentRunProof;
    /** 已真实替换 SO 且尚未确认的跨重启测试证据。 */
    private static JsonNode nativeRecoveryProof;
    /** 同一事件经历工具超时与确认丢失后的跨重启证据。 */
    private static JsonNode jointFailureProof;
    /** 当前随机端口。 */
    @LocalServerPort private int port;
    /** 使用正式 JSON 模型。 */
    @Autowired private ObjectMapper mapper;
    /** 合成只读成员使用正式密码编码。 */
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwords;
    /** 主动执行有限后台工作，不等待定时器。 */
    @Autowired private CrashBackgroundProcessor background;
    /** 显式执行隔离 Native 后台轮次。 */
    @Autowired private com.shanshui.apmserver.crash.internal.application.NativeBackgroundProcessor nativeBackground;
    /** 核对 SO 不产生 JVM 工作和审计。 */
    @Autowired private JdbcTemplate jdbc;
    /** 隔离遥测库中的当前 Native 快照。 */
    @Autowired private com.shanshui.apmserver.crash.internal.port.CrashStatePort crashStates;
    /** 测试期间可切换隔离工具启动器，结束后恢复原值。 */
    @Autowired private com.shanshui.apmserver.symbol.internal.config.SymbolProperties symbolProperties;
    /** 使用真实符号选择与 R8/LLVM 组件执行联合故障测试。 */
    @Autowired private com.shanshui.apmserver.symbol.api.SymbolRegistry symbolRegistry;
    /** 真实 Java 栈脱敏组件。 */
    @Autowired private com.shanshui.apmserver.crash.internal.application.CrashSanitizer crashSanitizer;
    /** 本测试浏览器会话。 */
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    /** 所有网页调用通过真实网络。 */
    private final HttpClient web = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();

    /** 应用启动前准备实际表，不复用开发环境或真实设备数据。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setUsername("test"); properties.setPassword("test-only"); properties.setDatabase("default");
        var client = new ClickHouseHttpClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "006_memory_leak_reports.sql", "010_crash_diagnostics.sql", "011_native_crash_state.sql", "012_native_symbolication_state.sql")) {
            String ddl = Files.readString(Path.of("src/main/resources/db/clickhouse", name));
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        // 共享内存报告表还需沿用正式进程身份增量；其他未使用领域不在此测试初始化。
        String processDdl = Files.readString(Path.of("src/main/resources/db/clickhouse/007_event_process_identity.sql"));
        for (String statement : processDdl.split(";")) if (statement.contains("apm.apm_memory_report")) client.execute(statement);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("apm.clickhouse.url", properties::getUrl);
        registry.add("apm.clickhouse.database", () -> "apm");
        registry.add("apm.clickhouse.username", () -> "test");
        registry.add("apm.clickhouse.password", () -> "test-only");
        registry.add("apm.symbol.directory", DIRECTORY::toString);
        if (System.getenv("APM_SYMBOLIZER_IMAGE") != null) {
            Files.createDirectories(DIRECTORY);
            Path launcher = DIRECTORY.resolve("docker-launcher");
            Files.writeString(launcher, "#!/bin/sh\nexec docker run --rm -i -v \"$APM_SYMBOL_DIRECTORY:$APM_SYMBOL_DIRECTORY:ro\" -e APM_SYMBOL_DIRECTORY --entrypoint /usr/local/bin/apm-native-symbolizer \"$APM_SYMBOLIZER_IMAGE\" \"$@\"\n");
            if (!launcher.toFile().setExecutable(true)) throw new IllegalStateException("测试启动器不可执行");
            registry.add("apm.symbol.native-launcher-path", launcher::toString);
        }
        registry.add("apm.memory-leak-report.artifact-directory", () -> DIRECTORY.resolve("memory-report").toString());
    }

    /** 有构建上报按版本选择默认/专用，SO 生命周期不改变 Native 原始现场。 */
    @Test
    @org.junit.jupiter.api.Order(1)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void validatesVersionSelectionNativeUploadAndEarlyAdmissionOverHttp() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES");
        Assumptions.assumeTrue(fixture != null, "需先生成 NDK 四 ABI 夹具");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var created = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.app\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String app = mapper.readTree(created.body()).path("appId").asText();
        String route = "/api/v1/apps/" + app + "/symbols";
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        var event = (ObjectNode) mapper.valueToTree(CrashTestSupport.event("http-default", "crash", "session", "device", "3.2.0",
                Instant.now().minusSeconds(5).toEpochMilli(), CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a")));
        ingest(event, key);
        var mapping = multipart(route, Map.of("appVersion", "3.2.0"), "mapping.txt", "com.example.App -> a:\n".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(201, mapping.statusCode(), mapping.body());
        String defaultId = mapper.readTree(mapping.body()).path("symbolId").asText();
        for (int round = 0; round < 3; round++) background.runOnce();
        var fixed = detail(app, "http-default");
        assertEquals("symbolicated", fixed.path("symbolicationStatus").asText(), fixed.toString());
        assertEquals(defaultId, fixed.path("symbolFileId").asText());
        var specific = multipart(route, Map.of("appVersion", "3.2.0", "buildId", "build-320"), "mapping.txt", "com.example.Other -> a:\n".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(201, specific.statusCode(), specific.body());
        String specificId = mapper.readTree(specific.body()).path("symbolId").asText();
        event.put("eventId", "http-specific"); ingest(event, key);
        for (int round = 0; round < 3; round++) background.runOnce();
        assertEquals(specificId, detail(app, "http-specific").path("symbolFileId").asText());
        assertEquals(fixed, detail(app, "http-default"));
        event.put("eventId", "http-other-version").put("appVersion", "4.0"); ingest(event, key);
        for (int round = 0; round < 2; round++) background.runOnce();
        assertEquals("pending", detail(app, "http-other-version").path("symbolicationStatus").asText());
        var nativeEvent = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")).path("events").get(0).deepCopy();
        nativeEvent.put("eventId", "http-native").put("occurredAt", Instant.now().minusSeconds(3).toEpochMilli());
        ingest(nativeEvent, key);
        var nativeBefore = detail(app, "http-native");
        byte[] zip = nativeZip(Path.of(fixture));
        verifyPermissionBeforeMultipart(app, route);
        assertEquals(403, multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, false).statusCode());
        var saved = multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, true);
        assertEquals(201, saved.statusCode(), saved.body());
        assertEquals(4, mapper.readTree(saved.body()).path("createdCount").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=? AND crash_kind='jvm'", Integer.class, UUID.fromString(app)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=? AND crash_kind='native'", Integer.class, UUID.fromString(app)));
        assertEquals(200, multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, true).statusCode());
        assertEquals(422, multipart(route + "/native-batches", Map.of("appVersion", "3.2.0", "targetAbi", "arm64-v8a", "targetElfBuildId", "deadbeef"), "symbols.zip", zip, true).statusCode());
        verifyRealFileLimits(route, Path.of(fixture));
        verifySharedMemoryMultipart(key);
        // 已取得接收许可的半包阻止第二份 ZIP，断开后许可及暂存必须恢复。
        verifyInterruptedReceiveReleasesPermit(route, zip);
        for (int round = 0; round < 3; round++) background.runOnce();
        assertEquals(nativeBefore, detail(app, "http-native"));
        assertEquals(204, json("DELETE", route + "/" + specificId + "?expectedRevision=1", null, true).statusCode());
        event.put("eventId", "http-fallback").put("appVersion", "3.2.0"); ingest(event, key);
        for (int round = 0; round < 4; round++) background.runOnce();
        assertEquals(defaultId, detail(app, "http-fallback").path("symbolFileId").asText());
        var items = mapper.readTree(saved.body()).path("items");
        String soId = items.get(0).path("symbolId").asText();
        var nativeProgress = json("GET", route + "/" + soId + "/crash-symbolication", null, true);
        assertEquals(200, nativeProgress.statusCode());
        assertEquals("native", mapper.readTree(nativeProgress.body()).path("crashKind").asText());
        assertEquals(204, json("DELETE", route + "/" + soId + "?expectedRevision=1", null, true).statusCode());
        assertEquals(nativeBefore, detail(app, "http-native"));
        var tokenResponse = json("POST", "/api/v1/apps/" + app + "/query-tokens", "{\"name\":\"symbols-validation\",\"expiresInDays\":30}", true);
        assertEquals(201, tokenResponse.statusCode(), tokenResponse.body());
        String token = mapper.readTree(tokenResponse.body()).path("token").asText();
        var agent = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/api/agent/v1/crashes/events/http-default"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, agent.statusCode(), agent.body());
        assertEquals(defaultId, mapper.readTree(agent.body()).path("symbolFileId").asText());
        // 合成验收材料只存放被 Git 忽略的 build，便于同一运行的网页/MCP 核验。
        Files.createDirectories(Path.of("build/validation"));
        /** 当前进程的隔离数据库和文件卷所对应的证据。 */
        var proof = mapper.valueToTree(Map.of(
                "port", port, "appId", app, "token", token, "nativeEvent", "http-native", "defaultId", defaultId,
                "nativeBefore", nativeBefore, "jvmBefore", fixed));
        Files.writeString(Path.of("build/validation/versioned-symbols-http.json"), mapper.writeValueAsString(proof));
        String hold = System.getenv("APM_SYMBOL_HTTP_HOLD");
        if (hold != null) {
            Path stop = Path.of(hold);
            while (!Files.exists(stop)) java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
        }
        currentRunProof = proof;
    }

    /** 完整销毁并重建后端上下文后，文件、查询 Token、原始 Native 与成功来源保持。 */
    @Test
    @org.junit.jupiter.api.Order(2)
    void preservesFilesSourcesAndNativeMaterialAcrossBackendRestart() throws Exception {
        Assumptions.assumeTrue(currentRunProof != null, "本次运行的前置 HTTP 验收未完成");
        var proof = currentRunProof;
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        String app = proof.path("appId").asText();
        assertEquals(proof.path("jvmBefore"), detail(app, "http-default"));
        assertEquals(proof.path("nativeBefore"), detail(app, "http-native"));
        var source = jdbc.queryForMap("SELECT storage_key,symbol_id FROM app_symbol_file WHERE app_id=? AND symbol_type='MAPPING' AND app_version='3.2.0' AND build_id IS NULL AND deleted_at IS NULL", UUID.fromString(app));
        assertEquals(proof.path("defaultId").asText(), source.get("symbol_id").toString());
        assertTrue(Files.isRegularFile(DIRECTORY.resolve(source.get("storage_key").toString())));
        var agent = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/api/agent/v1/crashes/events/http-native"))
                .header("Authorization", "Bearer " + proof.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, agent.statusCode(), agent.body());
        assertEquals("native_stack", mapper.readTree(agent.body()).path("stack").path("format").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=? AND crash_kind='jvm'", Integer.class, UUID.fromString(app)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=? AND crash_kind='native'", Integer.class, UUID.fromString(app)));
    }

    /** 真实 SO 和 LLVM 经 HTTP 及后台处理保留原身份并产生可查询结果。 */
    @Test
    @org.junit.jupiter.api.Order(3)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void resolvesNativeEventWithActualFixtureOverHttp() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES"), ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(currentRunProof != null && fixture != null && ndk != null && System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "真实 Native HTTP 验收需要四 ABI 夹具、NDK 和隔离 LLVM 镜像");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        String app = currentRunProof.path("appId").asText();
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        Path elf = Path.of(fixture, "arm64-v8a", "libapm_fixture.so");
        var info = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(elf);
        String host = System.getProperty("os.name").startsWith("Mac") ? "darwin-x86_64" : "linux-x86_64";
        Path nm = Path.of(ndk, "toolchains", "llvm", "prebuilt", host, "bin", "llvm-nm");
        var process = new ProcessBuilder(nm.toString(), "-n", elf.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String symbols = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue());
        String address = symbols.lines().map(String::trim).filter(line -> line.endsWith(" T apm_fixture_add"))
                .map(line -> "0x" + line.split("\\s+")[0].replaceFirst("^0+(?!$)", "")).findFirst().orElseThrow();
        var event = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")).path("events").get(0).deepCopy();
        event.put("eventId", "native-real-elf").put("occurredAt", Instant.now().minusSeconds(2).toEpochMilli()).put("appVersion", "3.2.0");
        var nativeCrash = (ObjectNode) event.path("crash").path("nativeCrash");
        ((ObjectNode) nativeCrash.path("modules").get(0)).put("elfBuildId", info.elfBuildId());
        ((ObjectNode) nativeCrash.path("backtrace").path("frames").get(0)).put("relativePc", address);
        ingest(event, key);
        var before = detail(app, "native-real-elf");
        var uploaded = multipart("/api/v1/apps/" + app + "/symbols/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", nativeZip(Path.of(fixture)), true);
        assertTrue(uploaded.statusCode() == 200 || uploaded.statusCode() == 201, uploaded.body());
        for (int index = 0; index < 4; index++) nativeBackground.runOnce();
        var after = detail(app, "native-real-elf");
        // 隔离 Docker 冷启动可能触发真实两秒期限；遵循服务记录的退避时间，不重置失败状态。
        if ("native_tool_timeout".equals(after.path("symbolicationReason").asText())) {
            Instant retryAt = Instant.parse(after.path("stack").path("content").path("backtrace")
                    .path("frames").get(0).path("resolution").path("retryAt").asText());
            assertTrue(retryAt.isBefore(Instant.now().plusSeconds(35)), "工具重试时间超过测试边界");
            while (Instant.now().isBefore(retryAt.plusMillis(250))) Thread.sleep(100);
            for (int index = 0; index < 4; index++) nativeBackground.runOnce();
            after = detail(app, "native-real-elf");
        }
        assertEquals("native_stack", after.path("stack").path("format").asText(), after.toString());
        assertEquals("symbolicated", after.path("symbolicationStatus").asText(), after.toString());
        assertEquals("apm_fixture_add", after.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("inline").get(0).path("function").asText());
        assertEquals(before.path("fingerprint"), after.path("fingerprint"));
        String hold = System.getenv("APM_NATIVE_HTTP_HOLD");
        if (hold != null) {
            Files.writeString(Path.of(hold + ".ready"), mapper.writeValueAsString(Map.of("port", port, "appId", app, "eventId", "native-real-elf")));
            while (!Files.exists(Path.of(hold))) java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
        }
        // 有效查询凭据读取的是相同的已保存结果，不应同步重新计算。
        var agent = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/api/agent/v1/crashes/events/native-real-elf"))
                .header("Authorization", "Bearer " + currentRunProof.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, agent.statusCode(), agent.body());
        assertEquals(after.path("stack"), mapper.readTree(agent.body()).path("stack"));
        // 删除活动来源后，首次成功的帧和原指纹仍必须可读。
        var source = after.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("source");
        verifyRecoveryAfterActualNativeReplacement(app, key, event, source, elf, info.elfBuildId(), after);
    }

    /** 在真实双库和文件替换后恢复已写入但丢失确认的原始发布载荷。 */
    private void verifyRecoveryAfterActualNativeReplacement(String app, String key, ObjectNode original, JsonNode source,
                                                             Path elf, String elfBuildId, JsonNode originalDetail) throws Exception {
        // 新事件复用已验证的物理帧，避免对已有成功事件执行重算。
        var retryEvent = original.deepCopy();
        retryEvent.put("eventId", "native-recovery-real-so");
        ingest(retryEvent, key);
        UUID appId = UUID.fromString(app);
        var base = crashStates.current(appId, "native-recovery-real-so").orElseThrow();
        var completed = crashStates.current(appId, "native-real-elf").orElseThrow();
        var main = mapper.readValue(completed.stackPayload(), com.shanshui.apmserver.crash.api.NativeStack.class);
        var auxiliary = new com.shanshui.apmserver.crash.internal.application.NativeSnapshotPreparation(mapper).prepare(base).auxiliary();
        var candidate = new com.shanshui.apmserver.crash.internal.application.NativeSnapshotSummary(mapper)
                .summarize(base, main, auxiliary).state();
        var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var jobs = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs(jdbc, transaction);
        var fault = org.mockito.Mockito.mock(com.shanshui.apmserver.crash.internal.port.CrashStatePort.class,
                org.mockito.AdditionalAnswers.delegatesTo(crashStates));
        var publications = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications(jdbc, jobs, fault,
                new com.shanshui.apmserver.crash.internal.persistence.CrashStateJson(mapper), transaction, 100, 16777216);
        var lease = jobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        assertEquals(appId, lease.appId());
        var registered = publications.registerNative(lease, base, candidate, 0).orElseThrow();
        // 真实 ClickHouse 已写入，但模拟进程在确认前失去连接。
        org.mockito.Mockito.doAnswer(call -> {
            crashStates.replay(call.getArgument(0));
            throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("模拟确认丢失");
        }).when(fault).replay(org.mockito.ArgumentMatchers.anyString());
        assertThrows(com.shanshui.apmserver.platform.api.EventStoreUnavailableException.class,
                () -> publications.publish(lease, registered));
        // 实际通过 HTTP 替换同一 ELF 身份，尾部增加一个字节以形成新摘要。
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("arm64-v8a/libapm_fixture.so"));
            zip.write(Files.readAllBytes(elf));
            zip.write(1);
            zip.closeEntry();
        }
        String expected = mapper.writeValueAsString(List.of(Map.of("abi", "arm64-v8a", "elfBuildId", elfBuildId,
                "symbolId", source.path("symbolFileId").asText(), "revision", source.path("revision").asInt())));
        var replaced = multipart("/api/v1/apps/" + app + "/symbols/native-batches",
                Map.of("appVersion", "3.2.0", "expectedEntries", expected), "replacement.zip", bytes.toByteArray(), true);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, mapper.readTree(replaced.body()).path("replacedCount").asInt());
        var replacement = mapper.readTree(replaced.body()).path("items").get(0);
        assertEquals(source.path("symbolFileId"), replacement.path("symbolId"));
        assertEquals(source.path("revision").asInt() + 1, replacement.path("revision").asInt());
        assertNotEquals(source.path("sha256"), replacement.path("sha256"));
        // 暂存已登记的原字节，下一测试在后端上下文实际重启后继续。
        nativeRecoveryProof = mapper.valueToTree(Map.of("app", app, "eventId", retryEvent.path("eventId").asText(),
                "jobId", lease.jobId().toString(), "payload", registered.payload(), "capture", base.captureSha256(),
                "fingerprint", base.fingerprint(), "sourceId", source.path("symbolFileId").asText(),
                "sourceRevision", source.path("revision").asInt(), "originalStack", originalDetail.path("stack"),
                "originalFingerprint", originalDetail.path("fingerprint")));
    }

    /** 实际重启后按登记字节恢复，SO 替换和删除均不改变成功来源。 */
    @Test
    @org.junit.jupiter.api.Order(4)
    void restoresNativePublicationAfterRestartAndRealReplacement() throws Exception {
        Assumptions.assumeTrue(nativeRecoveryProof != null, "本次运行未完成真实 SO 和确认丢失前置测试");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var proof = nativeRecoveryProof;
        String app = proof.path("app").asText();
        UUID appId = UUID.fromString(app);
        var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        // 新上下文重新领取已过期租约，不能覆盖在途载荷。
        jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?", UUID.fromString(proof.path("jobId").asText()));
        var resumedJobs = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs(jdbc, transaction);
        var resumed = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications(jdbc, resumedJobs, crashStates,
                new com.shanshui.apmserver.crash.internal.persistence.CrashStateJson(mapper), transaction, 100, 16777216);
        var next = resumedJobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        long beforeCount = resumedJobs.nativeProgress(appId, "3.2.0").publishedCount();
        var pending = resumed.pendingNative(next, 10).getFirst();
        assertEquals(proof.path("payload").asText(), pending.payload());
        assertTrue(resumed.publish(next, pending));
        assertFalse(resumed.publish(next, pending));
        assertEquals(beforeCount + 1, resumedJobs.nativeProgress(appId, "3.2.0").publishedCount());
        var restored = crashStates.current(appId, proof.path("eventId").asText()).orElseThrow();
        var restoredStack = mapper.readValue(restored.stackPayload(), com.shanshui.apmserver.crash.api.NativeStack.class);
        assertEquals(proof.path("sourceId").asText(), restoredStack.backtrace().frames().getFirst().resolution().source().symbolFileId().toString());
        assertEquals(proof.path("sourceRevision").asInt(), restoredStack.backtrace().frames().getFirst().resolution().source().revision());
        assertEquals(proof.path("capture").asText(), restored.captureSha256());
        assertEquals(proof.path("fingerprint").asText(), restored.fingerprint());
        assertTrue(resumedJobs.release(next, "waiting_symbols", null, Duration.ZERO));
        assertEquals(204, json("DELETE", "/api/v1/apps/" + app + "/symbols/" + proof.path("sourceId").asText()
                + "?expectedRevision=" + (proof.path("sourceRevision").asInt() + 1), null, true).statusCode());
        var deleted = detail(app, "native-real-elf");
        assertEquals(proof.path("originalStack"), deleted.path("stack"));
        assertEquals(proof.path("originalFingerprint"), deleted.path("fingerprint"));
    }

    /** 真实 HTTP 与双库验证 SO、mapping 和辅助线程分阶段到达。 */
    @Test
    @org.junit.jupiter.api.Order(5)
    void completesNativeAndJavaThreadsAcrossArtifactArrivalOrders() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES"), ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(currentRunProof != null && fixture != null && ndk != null && System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "联合验收需要 NDK、真实 ELF 和隔离 LLVM 镜像");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        String app = currentRunProof.path("appId").asText();
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        Path root = Path.of(fixture, "arm64-v8a");
        Path first = root.resolve("libapm_fixture.so"), second = root.resolve("libapm_inline.so");
        var inspector = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector();
        String firstId = inspector.inspect(first).elfBuildId(), secondId = inspector.inspect(second).elfBuildId();
        assertNotEquals(firstId, secondId);
        String firstPc = fixtureAddress(Path.of(ndk), first, "apm_fixture_add");
        String secondPc = fixtureAddress(Path.of(ndk), second, "apm_fixture_inline");
        String route = "/api/v1/apps/" + app + "/symbols";
        byte[] mapping = "com.example.Original -> a:\n    void original() -> a\n".getBytes(StandardCharsets.UTF_8);

        // 第一版本先登记主模块 SO，事件到达时 mapping 和辅助模块仍缺失。
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.1.0"), "first.zip", singleNativeZip(first), true).statusCode());
        var event = nativeJointEvent("native-joint-so-first", "9.1.0", firstId, firstPc, secondId, secondPc);
        ingest(event, key);
        var before = detail(app, "native-joint-so-first");
        var overview = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats");
        assertAgentSnapshotMatches(before);
        jointCheckpoint("raw", app, before);
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        var withMain = detail(app, "native-joint-so-first");
        assertEquals("symbolicated", withMain.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("status").asText(), withMain.toString());
        assertEquals("pending", withMain.path("diagnostics").path("nativeThreads").path("threads").get(0).path("backtrace").path("frames").get(0).path("resolution").path("status").asText());
        assertEquals("pending", withMain.path("stack").path("content").path("javaStack").path("symbolicationStatus").asText());
        assertEquals(before.path("fingerprint"), withMain.path("fingerprint"));
        assertAgentSnapshotMatches(withMain);
        jointCheckpoint("native-first", app, withMain);
        assertEquals(201, multipart(route, Map.of("appVersion", "9.1.0"), "mapping.txt", mapping, true).statusCode());
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        var withJava = detail(app, "native-joint-so-first");
        assertEquals("symbolicated", withJava.path("stack").path("content").path("javaStack").path("symbolicationStatus").asText(), withJava.toString());
        assertTrue(withJava.path("stack").path("content").path("javaStack").path("stack").path("content").asText().contains("com.example.Original.original"));
        assertEquals("symbolicated", withJava.path("diagnostics").path("otherThreads").path("threads").get(0).path("symbolicationStatus").asText());
        assertEquals("pending", withJava.path("diagnostics").path("nativeThreads").path("threads").get(0).path("backtrace").path("frames").get(0).path("resolution").path("status").asText());
        assertEquals(before.path("fingerprint"), withJava.path("fingerprint"));
        assertAgentSnapshotMatches(withJava);
        jointCheckpoint("java-added", app, withJava);
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.1.0"), "second.zip", singleNativeZip(second), true).statusCode());
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        var complete = detail(app, "native-joint-so-first");
        assertEquals("symbolicated", complete.path("diagnostics").path("nativeThreads").path("threads").get(0).path("backtrace").path("frames").get(0).path("resolution").path("status").asText(), complete.toString());
        assertEquals(withMain.path("stack").path("content").path("backtrace"), complete.path("stack").path("content").path("backtrace"));
        assertEquals(before.path("fingerprint"), complete.path("fingerprint"));
        assertEquals(overview, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        assertAgentSnapshotMatches(complete);
        jointCheckpoint("all-added", app, complete);
        assertNativeDuplicate(event, key);
        assertEquals(complete, detail(app, "native-joint-so-first"));
        // 对同一事件实际替换 SO，再删除当前 SO 和 mapping；已成功来源不得改变。
        var firstSource = complete.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("source");
        var mappingSource = complete.path("stack").path("content").path("javaStack").path("source");
        var replacementBytes = new ByteArrayOutputStream();
        try (var replacementZip = new ZipOutputStream(replacementBytes)) {
            replacementZip.putNextEntry(new ZipEntry("arm64-v8a/libapm_fixture.so"));
            replacementZip.write(Files.readAllBytes(first));
            replacementZip.write(1);
            replacementZip.closeEntry();
        }
        String expected = mapper.writeValueAsString(List.of(Map.of("abi", "arm64-v8a", "elfBuildId", firstId,
                "symbolId", firstSource.path("symbolFileId").asText(), "revision", firstSource.path("revision").asInt())));
        var replace = multipart(route + "/native-batches", Map.of("appVersion", "9.1.0", "expectedEntries", expected),
                "replacement.zip", replacementBytes.toByteArray(), true);
        assertEquals(200, replace.statusCode(), replace.body());
        assertEquals(1, mapper.readTree(replace.body()).path("replacedCount").asInt());
        var afterReplacement = detail(app, "native-joint-so-first");
        assertEquals(complete, afterReplacement);
        assertAgentSnapshotMatches(afterReplacement);
        jointCheckpoint("after-replacement", app, afterReplacement);
        assertEquals(204, json("DELETE", route + "/" + firstSource.path("symbolFileId").asText()
                + "?expectedRevision=" + (firstSource.path("revision").asInt() + 1), null, true).statusCode());
        assertEquals(204, json("DELETE", route + "/" + mappingSource.path("symbolFileId").asText()
                + "?expectedRevision=" + mappingSource.path("revision").asInt(), null, true).statusCode());
        var afterDeletion = detail(app, "native-joint-so-first");
        assertEquals(complete, afterDeletion);
        assertEquals(overview, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        assertAgentSnapshotMatches(afterDeletion);
        jointCheckpoint("after-deletion", app, afterDeletion);

        // 第二版本先登记 mapping，事件到达后 Java 应独立成功，随后补充两个 SO。
        assertEquals(201, multipart(route, Map.of("appVersion", "9.2.0"), "mapping.txt", mapping, true).statusCode());
        var mappingFirst = nativeJointEvent("native-joint-mapping-first", "9.2.0", firstId, firstPc, secondId, secondPc);
        ingest(mappingFirst, key);
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        var javaFirst = detail(app, "native-joint-mapping-first");
        assertEquals("symbolicated", javaFirst.path("stack").path("content").path("javaStack").path("symbolicationStatus").asText(), javaFirst.toString());
        assertTrue(javaFirst.path("stack").path("content").path("javaStack").path("stack").path("content").asText().contains("com.example.Original.original"));
        assertEquals("pending", javaFirst.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("status").asText());
        assertAgentSnapshotMatches(javaFirst);
        jointCheckpoint("mapping-first", app, javaFirst);
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.2.0"), "first.zip", singleNativeZip(first), true).statusCode());
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.2.0"), "second.zip", singleNativeZip(second), true).statusCode());
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        var secondComplete = detail(app, "native-joint-mapping-first");
        assertEquals("symbolicated", secondComplete.path("symbolicationStatus").asText(), secondComplete.toString());
        assertEquals("symbolicated", secondComplete.path("diagnostics").path("nativeThreads").path("threads").get(0).path("backtrace").path("frames").get(0).path("resolution").path("status").asText());
        assertEquals(javaFirst.path("fingerprint"), secondComplete.path("fingerprint"));
        assertAgentSnapshotMatches(secondComplete);
        jointCheckpoint("mapping-complete", app, secondComplete);
        assertNativeDuplicate(mappingFirst, key);
        assertEquals(secondComplete, detail(app, "native-joint-mapping-first"));
    }

    /** 仅显式开启人工联合验收时暂停，允许网页和 MCP 查看同一已确认版本。 */
    private void jointCheckpoint(String phase, String app, JsonNode detail) throws Exception {
        String prefix = System.getenv("APM_NATIVE_JOINT_HOLD");
        if (prefix == null || prefix.isBlank()) return;
        Path ready = Path.of(prefix + "." + phase + ".ready");
        Path proceed = Path.of(prefix + "." + phase + ".continue");
        Files.writeString(ready, mapper.writeValueAsString(Map.of("port", port, "appId", app,
                "eventId", detail.path("eventId").asText(), "phase", phase)));
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!Files.exists(proceed) && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(proceed), "人工联合验收等待超时：" + phase);
    }

    /** Agent HTTP 和网页必须读取同一版本的主栈与辅助现场。 */
    private void assertAgentSnapshotMatches(JsonNode detail) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/api/agent/v1/crashes/events/" + detail.path("eventId").asText()))
                .header("Authorization", "Bearer " + currentRunProof.path("token").asText()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var agent = mapper.readTree(response.body());
        assertEquals(detail.path("stack"), agent.path("stack"));
        assertEquals(detail.path("diagnostics"), agent.path("diagnostics"));
        assertEquals(detail.path("fingerprint"), agent.path("fingerprint"));
    }

    /** 多轮更新后相同原始事件必须仍按采集摘要判定重复。 */
    private void assertNativeDuplicate(ObjectNode event, String key) throws Exception {
        var body = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString());
        body.putArray("events").add(event);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/ingest/v1/batches"))
                .header("Content-Type", "application/json").header("X-App-Key", key).header("X-Schema-Version", "2")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, mapper.readTree(response.body()).path("duplicate").asInt(), response.body());
    }

    /** 生成包含主关联 Java 栈及不同模块辅助线程的合成 Native 现场。 */
    private ObjectNode nativeJointEvent(String id, String version, String firstId, String firstPc, String secondId, String secondPc) throws Exception {
        var event = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")).path("events").get(0).deepCopy();
        event.put("eventId", id).put("appVersion", version).put("occurredAt", Instant.now().minusSeconds(2).toEpochMilli());
        var crash = (ObjectNode) event.path("crash");
        var nativeCrash = (ObjectNode) crash.path("nativeCrash");
        ((ObjectNode) nativeCrash.path("modules").get(0)).put("elfBuildId", firstId);
        ((tools.jackson.databind.node.ArrayNode) nativeCrash.path("modules")).add(mapper.createObjectNode()
                .put("id", "m1").put("name", "libinline.so").put("abi", "arm64-v8a").put("elfBuildId", secondId));
        ((ObjectNode) nativeCrash.path("backtrace").path("frames").get(0)).put("relativePc", firstPc);
        var javaFrame = mapper.createObjectNode().put("className", "a").put("methodName", "a");
        var javaStack = mapper.createObjectNode().put("status", "ok").put("truncated", false);
        javaStack.putArray("frames").add(javaFrame);
        nativeCrash.set("javaStack", javaStack);
        var diagnostics = mapper.createObjectNode();
        diagnostics.set("crashedThread", mapper.createObjectNode().put("status", "ok").put("truncated", false).put("systemTid", 123).put("javaId", "1"));
        var others = mapper.createObjectNode().put("status", "ok").put("truncated", false);
        var thread = mapper.createObjectNode().put("javaId", "2").put("name", "worker").put("state", "RUNNABLE")
                .put("priority", 5).put("daemon", false).put("mainThread", false).put("truncated", false).put("relationToCrash", "unknown");
        thread.putArray("frames").add(javaFrame.deepCopy());
        others.putArray("threads").add(thread);
        diagnostics.set("otherThreads", others);
        var nativeThreads = mapper.createObjectNode().put("status", "ok").put("truncated", false);
        var backtrace = mapper.createObjectNode().put("status", "ok").put("truncated", false);
        backtrace.putArray("frames").add(mapper.createObjectNode().put("index", 0).put("moduleId", "m1").put("relativePc", secondPc));
        var nativeThread = mapper.createObjectNode().put("systemTid", 124).put("name", "native-worker").put("mainThread", false);
        nativeThread.set("backtrace", backtrace);
        nativeThreads.putArray("threads").add(nativeThread);
        diagnostics.set("nativeThreads", nativeThreads);
        crash.set("diagnostics", diagnostics);
        return event;
    }

    /** 从同一 ELF 的真实符号表取得相对地址。 */
    private String fixtureAddress(Path ndk, Path elf, String function) throws Exception {
        String host = System.getProperty("os.name").startsWith("Mac") ? "darwin-x86_64" : "linux-x86_64";
        var command = new ProcessBuilder(ndk.resolve("toolchains/llvm/prebuilt/" + host + "/bin/llvm-nm").toString(), "-n", elf.toString())
                .redirectErrorStream(true).start();
        assertTrue(command.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, command.exitValue());
        return output.lines().map(String::trim).filter(line -> line.endsWith(" T " + function) || line.endsWith(" t " + function))
                .map(line -> "0x" + line.split("\\s+")[0].replaceFirst("^0+(?!$)", "")).findFirst().orElseThrow();
    }

    /** 只归档指定 ELF，确保每次上传只唤醒对应模块。 */
    private byte[] singleNativeZip(Path elf) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("arm64-v8a/" + elf.getFileName()));
            zip.write(Files.readAllBytes(elf));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** 真实子进程超时不能阻碍 Java，还原退避到期后 Native 可继续完成。 */
    @Test
    @org.junit.jupiter.api.Order(6)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void resumesNativeThreadsAfterActualToolTimeout() throws Exception {
        String fixture = System.getenv("APM_ELF_FIXTURES"), ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(currentRunProof != null && fixture != null && ndk != null && System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "超时恢复验收需要真实 ELF 和隔离 LLVM 镜像");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        String app = currentRunProof.path("appId").asText();
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        String route = "/api/v1/apps/" + app + "/symbols";
        Path first = Path.of(fixture, "arm64-v8a", "libapm_fixture.so"), second = Path.of(fixture, "arm64-v8a", "libapm_inline.so");
        var inspector = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector();
        assertEquals(201, multipart(route, Map.of("appVersion", "9.3.0"), "mapping.txt",
                "com.example.Original -> a:\n    void original() -> a\n".getBytes(StandardCharsets.UTF_8), true).statusCode());
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.3.0"), "first.zip", singleNativeZip(first), true).statusCode());
        assertEquals(201, multipart(route + "/native-batches", Map.of("appVersion", "9.3.0"), "second.zip", singleNativeZip(second), true).statusCode());
        var event = nativeJointEvent("native-joint-timeout", "9.3.0", inspector.inspect(first).elfBuildId(), fixtureAddress(Path.of(ndk), first, "apm_fixture_add"),
                inspector.inspect(second).elfBuildId(), fixtureAddress(Path.of(ndk), second, "apm_fixture_inline"));
        Path slow = DIRECTORY.resolve("timeout-launcher");
        Files.writeString(slow, "#!/bin/sh\nexec /bin/sleep 10\n");
        assertTrue(slow.toFile().setExecutable(true));
        String originalLauncher = symbolProperties.getNativeLauncherPath();
        JsonNode timedOut;
        try {
            symbolProperties.setNativeLauncherPath(slow.toString());
            ingest(event, key);
            for (int round = 0; round < 4; round++) nativeBackground.runOnce();
            timedOut = detail(app, "native-joint-timeout");
            assertEquals("native_tool_timeout", timedOut.path("stack").path("content").path("backtrace").path("frames").get(0)
                    .path("resolution").path("reason").asText(), timedOut.toString());
            assertEquals("symbolicated", timedOut.path("stack").path("content").path("javaStack").path("symbolicationStatus").asText());
        } finally {
            symbolProperties.setNativeLauncherPath(originalLauncher);
            Files.deleteIfExists(slow);
        }
        // 使用实际退避时间；恢复轮次写入 ClickHouse 后注入确认丢失。
        var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var jobs = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs(jdbc, transaction);
        var fault = org.mockito.Mockito.mock(com.shanshui.apmserver.crash.internal.port.CrashStatePort.class,
                org.mockito.AdditionalAnswers.delegatesTo(crashStates));
        var injected = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            String payload = call.getArgument(0);
            crashStates.replay(payload);
            if (payload.contains("native-joint-timeout") && injected.compareAndSet(false, true))
                throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("模拟确认丢失");
            return null;
        }).when(fault).replay(org.mockito.ArgumentMatchers.anyString());
        var publications = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications(jdbc, jobs, fault,
                new com.shanshui.apmserver.crash.internal.persistence.CrashStateJson(mapper), transaction, 100, 16777216);
        var stages = new com.shanshui.apmserver.crash.internal.application.NativeBatchStages(
                new com.shanshui.apmserver.crash.internal.application.NativeFrameBatch(symbolRegistry),
                new com.shanshui.apmserver.crash.internal.application.NativeJavaStackBatch(symbolRegistry, mapper, crashSanitizer));
        var round = new com.shanshui.apmserver.crash.internal.application.NativeCrashRound(
                new com.shanshui.apmserver.crash.internal.application.NativeSnapshotPreparation(mapper), stages,
                new com.shanshui.apmserver.crash.internal.application.NativeSnapshotSummary(mapper), publications);
        var worker = new com.shanshui.apmserver.crash.internal.application.NativeBackgroundProcessor(jobs, fault, publications, round, 100);
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && !injected.get()) {
            Thread.sleep(1000);
            worker.runOnce();
        }
        assertTrue(injected.get(), "退避到期后未触发确认丢失");
        UUID appId = UUID.fromString(app);
        String payload = jdbc.queryForObject("SELECT pending_payload FROM crash_symbolication_publication WHERE app_id=? AND event_id=? AND published_at IS NULL",
                String.class, appId, "native-joint-timeout");
        assertNotNull(payload);
        var attempted = timedOut.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("attemptedSource");
        assertFalse(attempted.isMissingNode());
        var replacement = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(replacement)) {
            zip.putNextEntry(new ZipEntry("arm64-v8a/libapm_fixture.so")); zip.write(Files.readAllBytes(first)); zip.write(1); zip.closeEntry();
        }
        String expected = mapper.writeValueAsString(List.of(Map.of("abi", "arm64-v8a", "elfBuildId", inspector.inspect(first).elfBuildId(),
                "symbolId", attempted.path("symbolFileId").asText(), "revision", attempted.path("revision").asInt())));
        var changed = multipart(route + "/native-batches", Map.of("appVersion", "9.3.0", "expectedEntries", expected),
                "replacement.zip", replacement.toByteArray(), true);
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(1, mapper.readTree(changed.body()).path("replacedCount").asInt());
        // 模拟新进程领取后中断：强制该隔离租约过期，待上下文重启后重领。
        jdbc.update("UPDATE crash_symbolication_job SET next_run_at=clock_timestamp() WHERE app_id=? AND app_version='9.3.0' AND crash_kind='native'", appId);
        var stranded = jobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        assertEquals("9.3.0", stranded.appVersion());
        jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?", stranded.jobId());
        var proof = (ObjectNode) mapper.valueToTree(Map.of("app", app, "event", "native-joint-timeout", "payload", payload,
                "javaStack", timedOut.path("stack").path("content").path("javaStack"), "fingerprint", timedOut.path("fingerprint"),
                "sourceId", attempted.path("symbolFileId").asText(), "sourceRevision", attempted.path("revision").asInt()));
        if ("true".equals(System.getenv("APM_NATIVE_PROCESS_RESTART"))) {
            long before = jobs.nativeProgress(appId, "9.3.0").publishedCount();
            verifyOperatingSystemRestart(appId, before);
            proof.put("externalRecovered", true);
        }
        jointFailureProof = proof;
    }

    /** 后端上下文重启后先按原字节确认，再继续处理未成功线程。 */
    @Test
    @org.junit.jupiter.api.Order(7)
    void replaysJointFailureThenCompletesRemainingThreads() throws Exception {
        Assumptions.assumeTrue(jointFailureProof != null, "本次运行未完成联合故障前置测试");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var proof = jointFailureProof;
        UUID appId = UUID.fromString(proof.path("app").asText());
        var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var jobs = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs(jdbc, transaction);
        var publications = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications(jdbc, jobs, crashStates,
                new com.shanshui.apmserver.crash.internal.persistence.CrashStateJson(mapper), transaction, 100, 16777216);
        if (!proof.path("externalRecovered").asBoolean(false)) {
            var lease = jobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
            assertEquals("9.3.0", lease.appVersion());
            var pending = publications.pendingNative(lease, 10).getFirst();
            assertEquals(proof.path("payload").asText(), pending.payload());
            long publishedBefore = jobs.nativeProgress(appId, "9.3.0").publishedCount();
            long failedBefore = jobs.nativeProgress(appId, "9.3.0").failedUnitCount();
            assertTrue(publications.publish(lease, pending));
            assertFalse(publications.publish(lease, pending));
            assertEquals(publishedBefore + 1, jobs.nativeProgress(appId, "9.3.0").publishedCount());
            assertEquals(failedBefore + pending.failedUnits(), jobs.nativeProgress(appId, "9.3.0").failedUnitCount());
            assertTrue(jobs.release(lease, "queued", null, Duration.ZERO));
            for (int attempt = 0; attempt < 6; attempt++) nativeBackground.runOnce();
        }
        var recovered = detail(proof.path("app").asText(), proof.path("event").asText());
        assertEquals("symbolicated", recovered.path("symbolicationStatus").asText(), recovered.toString());
        assertEquals("symbolicated", recovered.path("diagnostics").path("nativeThreads").path("threads").get(0)
                .path("backtrace").path("frames").get(0).path("resolution").path("status").asText());
        assertEquals(proof.path("javaStack"), recovered.path("stack").path("content").path("javaStack"));
        assertEquals(proof.path("fingerprint"), recovered.path("fingerprint"));
        var source = recovered.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution").path("source");
        assertEquals(proof.path("sourceId"), source.path("symbolFileId"));
        assertEquals(proof.path("sourceRevision"), source.path("revision"));
        assertAgentSnapshotMatches(recovered);
    }

    /** 强制终止独立后端 JVM，再启动新 JVM 恢复同一在途发布。 */
    private void verifyOperatingSystemRestart(UUID appId, long publishedBefore) throws Exception {
        Path jar = Path.of("build/libs/apm-server-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "进程级验收需要先执行 bootJar");
        String pendingBefore = jdbc.queryForObject("SELECT pending_payload FROM crash_symbolication_publication WHERE app_id=? AND event_id=? AND published_at IS NULL",
                String.class, appId, "native-joint-timeout");
        Process first = startIsolatedBackend(jar, false);
        try {
            awaitIsolatedBackend(first, false);
            // 禁用调度的首个 JVM 在持有同一数据库时被操作系统强制终止。
            first.destroyForcibly();
            assertTrue(first.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (first.isAlive()) { first.destroyForcibly(); first.waitFor(); }
        }
        assertEquals(pendingBefore, jdbc.queryForObject("SELECT pending_payload FROM crash_symbolication_publication WHERE app_id=? AND event_id=? AND published_at IS NULL",
                String.class, appId, "native-joint-timeout"));
        Process restarted = startIsolatedBackend(jar, true);
        try {
            awaitIsolatedBackend(restarted, true);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                var current = crashStates.current(appId, "native-joint-timeout");
                int pending = jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_publication WHERE app_id=? AND event_id=? AND published_at IS NULL",
                        Integer.class, appId, "native-joint-timeout");
                if (current.isPresent() && "symbolicated".equals(current.get().status()) && !current.get().nativeWorkRemaining() && pending == 0) break;
                Thread.sleep(500);
            }
            var current = crashStates.current(appId, "native-joint-timeout").orElseThrow();
            assertEquals("symbolicated", current.status());
            assertFalse(current.nativeWorkRemaining());
            assertEquals(pendingBefore, new com.shanshui.apmserver.crash.internal.persistence.CrashStateJson(mapper).encode(current),
                    "进程重启后必须重放已登记的原始字节");
            var jobs = new com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs(jdbc,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
            assertTrue(jobs.nativeProgress(appId, "9.3.0").publishedCount() >= publishedBefore + 1);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_publication WHERE app_id=? AND event_id=? AND published_at IS NULL",
                    Integer.class, appId, "native-joint-timeout"));
        } finally {
            restarted.destroyForcibly();
            restarted.waitFor();
        }
    }

    /** 使用本次测试的独立双库和文件卷启动真实后端进程。 */
    private Process startIsolatedBackend(Path jar, boolean enabled) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(),
                "--server.address=127.0.0.1", "--server.port=0", "--apm.crash.background.initial-delay-ms=1000",
                "--apm.crash.background.reconcile-initial-delay-ms=3600000");
        var env = builder.environment();
        env.put("APM_DATABASE_URL", POSTGRES.getJdbcUrl());
        env.put("APM_DATABASE_USERNAME", POSTGRES.getUsername());
        env.put("APM_DATABASE_PASSWORD", POSTGRES.getPassword());
        env.put("CLICKHOUSE_URL", "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        env.put("CLICKHOUSE_DATABASE", "apm"); env.put("CLICKHOUSE_USERNAME", "test"); env.put("CLICKHOUSE_PASSWORD", "test-only");
        env.put("APM_SYMBOL_DIRECTORY", DIRECTORY.toString());
        env.put("APM_SYMBOL_NATIVE_LAUNCHER_PATH", DIRECTORY.resolve("docker-launcher").toString());
        env.put("APM_APP_KEY_ENCRYPTION_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        env.put("APM_CRASH_BACKGROUND_ENABLED", Boolean.toString(enabled));
        env.put("APM_MEMORY_REPORT_ARTIFACT_DIRECTORY", DIRECTORY.resolve("memory-report").toString());
        // 将启动日志保留在受控临时目录，不输出凭据或事件现场。
        builder.redirectErrorStream(true).redirectOutput(DIRECTORY.resolve(enabled ? "backend-restarted.log" : "backend-before-kill.log").toFile());
        return builder.start();
    }

    /** 等待独立 JVM 完成启动，进程退出或超时均明确失败。 */
    private void awaitIsolatedBackend(Process process, boolean restarted) throws Exception {
        Path log = DIRECTORY.resolve(restarted ? "backend-restarted.log" : "backend-before-kill.log");
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("隔离后端进程提前退出");
            if (Files.exists(log) && Files.readString(log).contains("Started ApmServerApplication")) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("隔离后端进程启动超时");
    }

    /** 持续有字节到达也受总 60 秒期限约束，而非只靠空闲读取超时。 */
    @Test
    @org.junit.jupiter.api.Order(8)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_NATIVE_SLOW_HTTP", matches="true")
    void stopsAContinuouslySlowMultipartUploadAtTheSharedDeadline() throws Exception {
        Assumptions.assumeTrue(currentRunProof != null, "本次运行的前置 HTTP 验收未完成");
        var proof = currentRunProof;
        json("GET", "/api/v1/session", null, true);
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        String route = "/api/v1/apps/" + proof.path("appId").asText() + "/symbols/native-batches";
        try (var socket = new Socket("127.0.0.1", port); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            socket.setSoTimeout(70000);
            String cookieHeader = cookies.getCookieStore().getCookies().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
            String header = "POST " + route + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nCookie: " + cookieHeader + "\r\nX-XSRF-TOKEN: " + csrf()
                    + "\r\nContent-Type: multipart/form-data; boundary=slow\r\nContent-Length: 1000000\r\n\r\n"
                    + "--slow\r\nContent-Disposition: form-data; name=\"file\"; filename=\"slow.zip\"\r\nContent-Type: application/zip\r\n\r\nabc";
            long start = System.nanoTime();
            socket.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            var received = executor.submit(() -> readFixedHttpResponse(socket.getInputStream()));
            while (!received.isDone() && System.nanoTime() - start < Duration.ofSeconds(67).toNanos()) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
                try { socket.getOutputStream().write('x'); socket.getOutputStream().flush(); } catch (IOException stopped) { break; }
            }
            String response;
            try { response = received.get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception failure) { socket.close(); received.cancel(true); throw failure; }
            assertTrue(response.startsWith("HTTP/1.1 503"), response);
            long seconds = Duration.ofNanos(System.nanoTime()-start).toSeconds();
            assertTrue(seconds >= 59 && seconds < 68, "实际持续慢流用时=" + seconds);
        }
    }

    /** 有界读取完整 JSON 响应，不把等待连接 EOF 当作业务截止时间。 */
    private String readFixedHttpResponse(InputStream stream) throws IOException {
        var header = new ByteArrayOutputStream();
        while (header.size() < 16384) {
            int value = stream.read(); if (value < 0) throw new IOException("响应头中断");
            header.write(value);
            if (header.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) break;
        }
        String text = header.toString(StandardCharsets.ISO_8859_1);
        var length = java.util.regex.Pattern.compile("(?im)^Content-Length: ([0-9]+)").matcher(text);
        if (!length.find()) throw new IOException("提前拒绝响应必须带明确长度");
        int bytes = Integer.parseInt(length.group(1));
        if (bytes > 65536) throw new IOException("错误响应过大");
        byte[] body = stream.readNBytes(bytes);
        if (body.length != bytes) throw new IOException("错误响应中断");
        return text + new String(body, StandardCharsets.UTF_8);
    }

    /** 无效正文不影响提前权限判断：Viewer 为 403，其他应用为 404。 */
    private void verifyPermissionBeforeMultipart(String app, String route) throws Exception {
        UUID viewer = UUID.randomUUID();
        String email = "viewer-" + viewer + "@example.invalid";
        jdbc.update("INSERT INTO apm_user VALUES (?,?,'只读验收',?,'ACTIVE',clock_timestamp(),clock_timestamp())",
                viewer, email, passwords.encode("Viewer-password-123!"));
        jdbc.update("INSERT INTO app_member(app_id,user_id,role,created_at) VALUES (?,?,'VIEWER',clock_timestamp())", UUID.fromString(app), viewer);
        var viewerCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(viewerCookies).build();
        client.send(HttpRequest.newBuilder(base().resolve("/api/v1/session")).build(), HttpResponse.BodyHandlers.ofString());
        String csrf = viewerCookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
        var logged = client.send(HttpRequest.newBuilder(base().resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", email, "password", "Viewer-password-123!")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logged.statusCode());
        csrf = viewerCookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
        var denied = client.send(HttpRequest.newBuilder(base().resolve(route + "/native-batches"))
                .header("Content-Type", "multipart/form-data; boundary=missing").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode(), denied.body());
        var hidden = web.send(HttpRequest.newBuilder(base().resolve("/api/v1/apps/" + UUID.randomUUID() + "/symbols/native-batches"))
                .header("Content-Type", "multipart/form-data; boundary=missing").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, hidden.statusCode(), hidden.body());
    }

    /** 共享 Spring multipart 接收大于旧 32 MiB 的内存附件，仍走报告业务限制。 */
    private void verifySharedMemoryMultipart(String key) throws Exception {
        var metadata = (ObjectNode) mapper.readTree(Files.readString(Path.of("../docs/fixtures/memory-leak-metadata.example.json")));
        metadata.put("eventId", UUID.randomUUID().toString()).put("occurredAt", Instant.now().minusSeconds(1).toEpochMilli())
                .put("packageName", "com.example.app").put("processName", "com.example.app");
        String report = Files.readString(Path.of("../docs/fixtures/memory-leak-report.example.json"));
        String prefix = "--memory\r\nContent-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"\r\nContent-Type: application/json\r\n\r\n" + metadata
                + "\r\n--memory\r\nContent-Disposition: form-data; name=\"report\"; filename=\"report.json\"\r\nContent-Type: application/json\r\n\r\n" + report
                + "\r\n--memory\r\nContent-Disposition: form-data; name=\"hprof\"; filename=\"synthetic.hprof\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix), zeroPublisher(33L * 1024 * 1024), HttpRequest.BodyPublishers.ofString("\r\n--memory--\r\n"));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/ingest/v1/memory-reports"))
                .header("Content-Type", "multipart/form-data; boundary=memory").header("X-App-Key", key).POST(body).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    /** 使用磁盘及流式 HTTP 验证实际 256 MiB ZIP 边界和 mapping 的独立 32 MiB 限制。 */
    private void verifyRealFileLimits(String route, Path root) throws Exception {
        Path zip = Files.createTempFile("native-limit-", ".zip");
        try {
            byte[] elf = Files.readAllBytes(root.resolve("arm64-v8a/libapm_fixture.so"));
            String moduleName = "libfixture.so", helperName = "__MACOSX/padding";
            long maximum = 256L * 1024 * 1024;
            long overhead = 22 + 2 * (30 + 46) + 2 * (moduleName.length() + helperName.length());
            long padding = maximum - overhead - elf.length;
            var crc = new CRC32(); crc.update(elf);
            var helperCrc = new CRC32();
            byte[] zeros = new byte[65536];
            for (long count = 0; count < padding;) {
                int length = (int) Math.min(zeros.length, padding - count); helperCrc.update(zeros, 0, length); count += length;
            }
            try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
                var module = new ZipEntry(moduleName); module.setMethod(ZipEntry.STORED); module.setTime(315532800000L);
                module.setSize(elf.length); module.setCrc(crc.getValue()); archive.putNextEntry(module); archive.write(elf); archive.closeEntry();
                var helper = new ZipEntry(helperName); helper.setMethod(ZipEntry.STORED); helper.setTime(315532800000L);
                helper.setSize(padding); helper.setCrc(helperCrc.getValue()); archive.putNextEntry(helper);
                for (long count = 0; count < padding;) {
                    int length = (int) Math.min(zeros.length, padding-count); archive.write(zeros, 0, length); count += length;
                }
                archive.closeEntry();
            }
            assertEquals(maximum, Files.size(zip));
            var atLimit = multipartStream(route + "/native-batches", "symbols.zip", HttpRequest.BodyPublishers.ofFile(zip));
            assertEquals(200, atLimit.statusCode(), atLimit.body());
            // 未声明总长度的实际超限流不能靠 Content-Length 提前检查代替。
            var tooLarge = multipartStream(route + "/native-batches", "symbols.zip", zeroPublisher(maximum + 1));
            assertEquals(413, tooLarge.statusCode(), tooLarge.body());
            var mapping = multipartStream(route, "mapping.txt", zeroPublisher(32L * 1024 * 1024 + 1));
            assertEquals(413, mapping.statusCode(), mapping.body());
            assertEquals("MAPPING_TOO_LARGE", mapper.readTree(mapping.body()).path("code").asText());
        } finally { Files.deleteIfExists(zip); }
    }
    /** 不分配大数组，按实际读取生成指定字节流。 */
    private HttpRequest.BodyPublisher zeroPublisher(long bytes) {
        return HttpRequest.BodyPublishers.ofInputStream(() -> new InputStream() {
            /** 剩余合成零字节。 */
            private long remaining = bytes;
            /** 读取单字节测试数据。 */
            public int read() { if (remaining == 0) return -1; remaining--; return 0; }
            /** 使用固定大小网络缓冲写出数据。 */
            public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length); Arrays.fill(buffer, offset, offset + count, (byte) 0); remaining -= count; return count;
            }
        });
    }
    /** multipart 文本和文件都经真实 Socket，未知长度文件使用 chunked 传输。 */
    private HttpResponse<String> multipartStream(String route, String filename, HttpRequest.BodyPublisher file) throws Exception {
        String prefix = "--large\r\nContent-Disposition: form-data; name=\"appVersion\"\r\n\r\n3.2.0\r\n"
                + "--large\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix), file, HttpRequest.BodyPublishers.ofString("\r\n--large--\r\n"));
        return web.send(HttpRequest.newBuilder(base().resolve(route)).timeout(Duration.ofSeconds(60))
                .header("X-XSRF-TOKEN", csrf()).header("Content-Type", "multipart/form-data; boundary=large")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 半包不会进入归档或元数据；关闭连接后测试下一次正常请求成功。 */
    private void verifyInterruptedReceiveReleasesPermit(String route, byte[] zip) throws Exception {
        // 只在半包真正取得许可后发起竞争请求，避免两个网络请求的调度竞态。
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.shanshui.apmserver.symbol.internal.archive.NativeUploadLimiter.class);
        var observer = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            /** 在安全日志出现时唤醒测试，不读取上传内容。 */
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                if ("SO 接收许可已取得".equals(event.getFormattedMessage())) acquired.countDown();
            }
        };
        observer.start();
        logger.addAppender(observer);
        try (var socket = new Socket("127.0.0.1", port)) {
            String cookieHeader = cookies.getCookieStore().getCookies().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
            String header = "POST " + route + "/native-batches HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nCookie: " + cookieHeader + "\r\nX-XSRF-TOKEN: " + csrf()
                    + "\r\nContent-Type: multipart/form-data; boundary=partial\r\nContent-Length: 1000000\r\n\r\n"
                    + "--partial\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.zip\"\r\nContent-Type: application/zip\r\n\r\nabc";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            assertTrue(acquired.await(10, java.util.concurrent.TimeUnit.SECONDS), "半包未取得 SO 接收许可");
            boolean busy = false;
            for (int attempt = 0; attempt < 30; attempt++) {
                var second = multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, true);
                if (second.statusCode() == 503) { busy = true; break; }
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            }
            assertTrue(busy, "半包应持有接收许可，第二份请求不能排队");
        } finally {
            logger.detachAppender(observer);
            observer.stop();
        }
        for (int attempt = 0; attempt < 30; attempt++) {
            var after = multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, true);
            if (after.statusCode() == 200) return;
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        fail("断开半包后未释放 SO 许可");
    }
    /** 所有候选来自四 ABI NDK 实际夹具。 */
    private byte[] nativeZip(Path root) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                zip.putNextEntry(new ZipEntry(abi + "/libfixture.so")); zip.write(Files.readAllBytes(root.resolve(abi).resolve("libapm_fixture.so"))); zip.closeEntry();
            }
            // 不同归档名称的同一模块同摘要应合并为一个登记。
            zip.putNextEntry(new ZipEntry("mirror/libsame.so")); zip.write(Files.readAllBytes(root.resolve("arm64-v8a/libapm_fixture.so"))); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    /** 真实 Servlet 接收 .so.sym 混合批次，并保持精确身份、冲突确认和版本隔离。 */
    @Test
    @org.junit.jupiter.api.Order(9)
    void validatesAgpStyleBatchesOverHttp() throws Exception {
        /** 由测试夹具生成器提供的四 ABI 符号包。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var created = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.agp.symbols\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String app = mapper.readTree(created.body()).path("appId").asText();
        String route = "/api/v1/apps/" + app + "/symbols/native-batches";
        Path root = Path.of(fixture);
        byte[] archive = Files.readAllBytes(root.resolve("fixture-native-debug-symbols.zip"));
        String buildId = Files.readString(root.resolve("arm64-v8a/build-id.txt")).strip();
        /** 目标不匹配时整包不能产生管理记录或工作。 */
        var wrong = multipart(route, Map.of("appVersion", "agp-1", "targetAbi", "arm64-v8a", "targetElfBuildId", "deadbeef"),
                "native-debug-symbols.zip", archive, true);
        assertEquals(422, wrong.statusCode(), wrong.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, UUID.fromString(app)));
        var uploaded = multipart(route, Map.of("appVersion", "agp-1", "targetAbi", "arm64-v8a", "targetElfBuildId", buildId),
                "native-debug-symbols.zip", archive, true);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        JsonNode body = mapper.readTree(uploaded.body());
        assertEquals(4, body.path("createdCount").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=? AND app_version=? AND crash_kind='native'", Integer.class, UUID.fromString(app), "agp-1"));
        /** 全部显示名保留 .so.sym，确认信息引用真实身份和修订。 */
        var expected = new ArrayList<Map<String,Object>>();
        for (JsonNode item : body.path("items")) {
            assertTrue(item.path("originalFilename").asText().endsWith(".so.sym"), item.toString());
            expected.add(Map.of("abi", item.path("abi").asText(), "elfBuildId", item.path("elfBuildId").asText(),
                    "symbolId", item.path("symbolId").asText(), "revision", item.path("revision").asInt()));
        }
        var retry = multipart(route, Map.of("appVersion", "agp-1"), "native-debug-symbols.zip", archive, true);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(4, mapper.readTree(retry.body()).path("unchangedCount").asInt());
        /** 同身份相同字节的 .so 与 .so.sym 合并，不同字节则全批拒绝。 */
        byte[] bytes = Files.readAllBytes(root.resolve("arm64-v8a/libapm_fixture.so.sym"));
        var duplicate = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(duplicate)) {
            zip.putNextEntry(new ZipEntry("arm64-v8a/a.so")); zip.write(bytes); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("arm64-v8a/b.so.sym")); zip.write(bytes); zip.closeEntry();
        }
        var same = multipart(route, Map.of("appVersion", "agp-1"), "mixed.zip", duplicate.toByteArray(), true);
        assertEquals(200, same.statusCode(), same.body());
        assertEquals(1, mapper.readTree(same.body()).path("unchangedCount").asInt());
        var conflicting = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(conflicting)) {
            zip.putNextEntry(new ZipEntry("arm64-v8a/a.so")); zip.write(bytes); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("arm64-v8a/b.so.sym")); zip.write(bytes); zip.write(1); zip.closeEntry();
        }
        var rejected = multipart(route, Map.of("appVersion", "agp-1"), "mixed.zip", conflicting.toByteArray(), true);
        assertEquals(422, rejected.statusCode(), rejected.body());
        assertEquals("SO_ARCHIVE_IDENTITY_CONFLICT", mapper.readTree(rejected.body()).path("code").asText());
        /** 只改变一个候选的字节，以完整快照确认替换并检查旧快照失效。 */
        var changed = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(changed)) {
            for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                zip.putNextEntry(new ZipEntry(abi + "/libapm_fixture.so.sym"));
                zip.write(Files.readAllBytes(root.resolve(abi).resolve("libapm_fixture.so.sym")));
                if (abi.equals("arm64-v8a")) zip.write(1);
                zip.closeEntry();
            }
        }
        var conflict = multipart(route, Map.of("appVersion", "agp-1"), "native-debug-symbols.zip", changed.toByteArray(), true);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(4, mapper.readTree(conflict.body()).path("errors").size());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=? AND app_version=?", Integer.class, UUID.fromString(app), "agp-1"));
        String confirmation = mapper.writeValueAsString(expected);
        var replaced = multipart(route, Map.of("appVersion", "agp-1", "expectedEntries", confirmation), "native-debug-symbols.zip", changed.toByteArray(), true);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, mapper.readTree(replaced.body()).path("replacedCount").asInt());
        assertEquals(409, multipart(route, Map.of("appVersion", "agp-1", "expectedEntries", confirmation), "native-debug-symbols.zip", changed.toByteArray(), true).statusCode());
        assertEquals(4, mapper.readTree(multipart(route, Map.of("appVersion", "agp-1"), "native-debug-symbols.zip", changed.toByteArray(), true).body()).path("unchangedCount").asInt());
        /** 同一应用的另一个版本拥有独立身份。 */
        var otherVersion = multipart(route, Map.of("appVersion", "agp-2"), "native-debug-symbols.zip", archive, true);
        assertEquals(201, otherVersion.statusCode(), otherVersion.body());
        assertEquals(4, mapper.readTree(otherVersion.body()).path("createdCount").asInt());
        /** 同一版本和相同内容在其他应用仍建立独立记录。 */
        var otherCreated = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.agp.other\"}", true);
        assertEquals(201, otherCreated.statusCode(), otherCreated.body());
        String otherApp = mapper.readTree(otherCreated.body()).path("appId").asText();
        var otherAppUpload = multipart("/api/v1/apps/" + otherApp + "/symbols/native-batches", Map.of("appVersion", "agp-1"),
                "native-debug-symbols.zip", archive, true);
        assertEquals(201, otherAppUpload.statusCode(), otherAppUpload.body());
        assertEquals(4, mapper.readTree(otherAppUpload.body()).path("createdCount").asInt());
    }

    /** 原始 AGP ZIP 通过真实 HTTP 上传后还原合成事件，并固定首次成功来源。 */
    @Test
    @org.junit.jupiter.api.Order(10)
    void resolvesSyntheticEventFromActualAgpZip(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        /** 全部文件和隔离工具均由调用者明确提供，避免把缺少真实材料当成通过。 */
        String archivePath = System.getenv("APM_AGP_NATIVE_ZIP");
        String fullPath = System.getenv("APM_AGP_NATIVE_FULL_SO");
        String ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(archivePath != null && fullPath != null && ndk != null && System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "AGP 联合验收需要原始 ZIP、同构建未剥离 SO、NDK 和隔离 LLVM 镜像");
        /** 只提取预期模块到测试临时目录，不使用归档路径决定服务端存储位置。 */
        Path symbol = temporary.resolve("libapm_crash_demo.so.sym");
        try (var zip = new java.util.zip.ZipFile(archivePath)) {
            var entry = zip.getEntry("arm64-v8a/libapm_crash_demo.so.sym");
            assertNotNull(entry);
            try (var input = zip.getInputStream(entry)) { Files.copy(input, symbol); }
        }
        var inspector = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector();
        var original = inspector.inspect(symbol);
        var full = inspector.inspect(Path.of(fullPath));
        assertEquals("arm64-v8a", original.abi());
        assertEquals(original.elfBuildId(), full.elfBuildId());
        assertFalse(original.debugLine());
        assertTrue(full.debugLine(), "同构建 SO 需要包含调试行信息");
        String pc = fixtureAddress(Path.of(ndk), symbol, "apm_demo_segv");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var created = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.agp.e2e\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String app = mapper.readTree(created.body()).path("appId").asText();
        String route = "/api/v1/apps/" + app + "/symbols/native-batches";
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        /** 合成采集事件使用真实 AGP 模块身份和从符号表取得的函数地址。 */
        var event = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")).path("events").get(0).deepCopy();
        event.put("eventId", "native-agp-synthetic").put("packageName", "com.example.agp.e2e")
                .put("occurredAt", Instant.now().minusSeconds(2).toEpochMilli()).put("appVersion", "agp-e2e");
        var crash = (ObjectNode) event.path("crash").path("nativeCrash");
        ((ObjectNode) crash.path("modules").get(0)).put("name", "libapm_crash_demo.so").put("elfBuildId", original.elfBuildId());
        ((ObjectNode) crash.path("backtrace").path("frames").get(0)).put("relativePc", pc);
        ingest(event, key);
        JsonNode before = detail(app, "native-agp-synthetic");
        JsonNode statistics = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats");
        var uploaded = multipart(route, Map.of("appVersion", "agp-e2e", "targetAbi", "arm64-v8a", "targetElfBuildId", original.elfBuildId()),
                "native-debug-symbols.zip", Files.readAllBytes(Path.of(archivePath)), true);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        assertTrue(mapper.readTree(uploaded.body()).path("createdCount").asInt() > 0);
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        JsonNode resolved = detail(app, "native-agp-synthetic");
        /** 容器冷启动偶尔触发工具期限，遵循后台实际退避后再次尝试。 */
        if ("native_tool_timeout".equals(resolved.path("symbolicationReason").asText())) {
            Instant retryAt = Instant.parse(resolved.path("stack").path("content").path("backtrace").path("frames").get(0)
                    .path("resolution").path("retryAt").asText());
            assertTrue(retryAt.isBefore(Instant.now().plusSeconds(35)));
            while (Instant.now().isBefore(retryAt.plusMillis(250))) Thread.sleep(100);
            for (int round = 0; round < 4; round++) nativeBackground.runOnce();
            resolved = detail(app, "native-agp-synthetic");
        }
        JsonNode resolution = resolved.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution");
        assertEquals("symbolicated", resolution.path("status").asText(), resolved.toString());
        assertEquals("apm_demo_segv", resolution.path("inline").get(0).path("function").asText());
        assertEquals(0, resolution.path("inline").get(0).path("line").asInt());
        JsonNode source = resolution.path("source");
        assertFalse(source.path("symbolFileId").asText().isBlank());
        assertEquals(before.path("fingerprint"), resolved.path("fingerprint"));
        assertEquals(statistics, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        /** 确认替换为同 Build ID 的完整调试 SO，已固定帧不得补出新行号。 */
        byte[] replacement = singleNativeZip(Path.of(fullPath));
        assertEquals(409, multipart(route, Map.of("appVersion", "agp-e2e"), "full.zip", replacement, true).statusCode());
        String expected = mapper.writeValueAsString(List.of(Map.of("abi", "arm64-v8a", "elfBuildId", original.elfBuildId(),
                "symbolId", source.path("symbolFileId").asText(), "revision", source.path("revision").asInt())));
        var replaced = multipart(route, Map.of("appVersion", "agp-e2e", "expectedEntries", expected), "full.zip", replacement, true);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, mapper.readTree(replaced.body()).path("replacedCount").asInt());
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        JsonNode after = detail(app, "native-agp-synthetic");
        assertEquals(resolution, after.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution"));
        assertEquals(before.path("fingerprint"), after.path("fingerprint"));
        assertEquals(statistics, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        /** 仅在人工页面核对时保持隔离服务，凭据不写入证据。 */
        String hold = System.getenv("APM_AGP_HTTP_HOLD");
        if (hold != null && !hold.isBlank()) {
            Files.writeString(Path.of(hold + ".ready"), mapper.writeValueAsString(Map.of("port", port, "appId", app, "eventId", "native-agp-synthetic")));
            Instant deadline = Instant.now().plusSeconds(240);
            while (!Files.exists(Path.of(hold)) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertTrue(Files.exists(Path.of(hold)), "页面验收未在期限内结束");
        }
    }

    /** FULL 符号批次按 ELF 内容核对架构并保持幂等和修订确认。 */
    @Test
    @org.junit.jupiter.api.Order(11)
    void validatesFullDebugBatchesOverHttp() throws Exception {
        /** 四 ABI 的 NDK 夹具与精确目标身份。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        Assumptions.assumeTrue(fixture != null, "需提供临时 NDK ELF 夹具");
        Path root = Path.of(fixture);
        byte[] archive = Files.readAllBytes(root.resolve("fixture-native-debug-symbols-full.zip"));
        String buildId = Files.readString(root.resolve("arm64-v8a/build-id.txt")).strip();
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var created = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.agp.full.symbols\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String app = mapper.readTree(created.body()).path("appId").asText();
        String route = "/api/v1/apps/" + app + "/symbols/native-batches";
        /** 目标不匹配时不能产生任何文件记录。 */
        var wrong = multipart(route, Map.of("appVersion", "full-1", "targetAbi", "arm64-v8a", "targetElfBuildId", "deadbeef"),
                "native-debug-symbols.zip", archive, true);
        assertEquals(422, wrong.statusCode(), wrong.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, UUID.fromString(app)));
        var uploaded = multipart(route, Map.of("appVersion", "full-1", "targetAbi", "arm64-v8a", "targetElfBuildId", buildId),
                "native-debug-symbols.zip", archive, true);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        JsonNode body = mapper.readTree(uploaded.body());
        assertEquals(4, body.path("createdCount").asInt());
        var expected = new ArrayList<Map<String, Object>>();
        for (JsonNode item : body.path("items")) {
            assertTrue(item.path("originalFilename").asText().endsWith(".so.dbg"), item.toString());
            expected.add(Map.of("abi", item.path("abi").asText(), "elfBuildId", item.path("elfBuildId").asText(),
                    "symbolId", item.path("symbolId").asText(), "revision", item.path("revision").asInt()));
        }
        var retry = multipart(route, Map.of("appVersion", "full-1"), "native-debug-symbols.zip", archive, true);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(4, mapper.readTree(retry.body()).path("unchangedCount").asInt());
        /** 即使目录写成 x86，arm64 ELF 也只能命中实际 arm64 目标。 */
        var mislabeled = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(mislabeled)) {
            zip.putNextEntry(new ZipEntry("x86/libapm_fixture.so.dbg"));
            zip.write(Files.readAllBytes(root.resolve("arm64-v8a/libapm_fixture.so.dbg")));
            zip.closeEntry();
        }
        var matched = multipart(route, Map.of("appVersion", "full-1", "targetAbi", "arm64-v8a", "targetElfBuildId", buildId),
                "mislabeled.zip", mislabeled.toByteArray(), true);
        assertEquals(200, matched.statusCode(), matched.body());
        assertEquals("arm64-v8a", mapper.readTree(matched.body()).path("items").get(0).path("abi").asText());
        var mismatch = multipart(route, Map.of("appVersion", "full-1", "targetAbi", "x86", "targetElfBuildId", buildId),
                "mislabeled.zip", mislabeled.toByteArray(), true);
        assertEquals(422, mismatch.statusCode(), mismatch.body());
        /** 单个 ELF 内容发生变化时，需要对完整批次快照确认替换。 */
        var changed = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(changed)) {
            for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                zip.putNextEntry(new ZipEntry(abi + "/libapm_fixture.so.dbg"));
                zip.write(Files.readAllBytes(root.resolve(abi).resolve("libapm_fixture.so.dbg")));
                if (abi.equals("arm64-v8a")) zip.write(1);
                zip.closeEntry();
            }
        }
        var conflict = multipart(route, Map.of("appVersion", "full-1"), "native-debug-symbols.zip", changed.toByteArray(), true);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(4, mapper.readTree(conflict.body()).path("errors").size());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, UUID.fromString(app)));
        String confirmation = mapper.writeValueAsString(expected);
        var replaced = multipart(route, Map.of("appVersion", "full-1", "expectedEntries", confirmation),
                "native-debug-symbols.zip", changed.toByteArray(), true);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, mapper.readTree(replaced.body()).path("replacedCount").asInt());
        assertEquals(409, multipart(route, Map.of("appVersion", "full-1", "expectedEntries", confirmation),
                "native-debug-symbols.zip", changed.toByteArray(), true).statusCode());
        var same = multipart(route, Map.of("appVersion", "full-1"), "native-debug-symbols.zip", changed.toByteArray(), true);
        assertEquals(200, same.statusCode(), same.body());
        assertEquals(4, mapper.readTree(same.body()).path("unchangedCount").asInt());
    }

    /** FULL ZIP 通过真实 HTTP 和隔离 LLVM 还原合成事件，并固定首次成功结果。 */
    @Test
    @org.junit.jupiter.api.Order(12)
    void resolvesFullDebugZipWithSourceLines() throws Exception {
        /** 测试依赖显式提供的夹具、NDK 和 Linux 符号化镜像。 */
        String fixture = System.getenv("APM_ELF_FIXTURES");
        String ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(fixture != null && ndk != null && System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "FULL 联合验收需要 NDK 夹具和隔离 LLVM 镜像");
        Path root = Path.of(fixture);
        /** 可选使用基于同一夹具构建的 AGP 原始 FULL ZIP。 */
        String actualArchive = System.getenv("APM_AGP_FULL_NATIVE_ZIP");
        Path archive = actualArchive == null ? root.resolve("fixture-native-debug-symbols-full.zip") : Path.of(actualArchive);
        if (actualArchive != null) {
            try (var zip = new ZipFile(archive.toFile())) {
                for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                    var entry = zip.getEntry(abi + "/libapm_fixture.so.dbg");
                    assertNotNull(entry, "AGP FULL 包必须来自同一四 ABI 夹具");
                    try (var input = zip.getInputStream(entry)) {
                        assertArrayEquals(Files.readAllBytes(root.resolve(abi).resolve("libapm_fixture.so.dbg")), input.readAllBytes());
                    }
                }
            }
        }
        Path symbol = root.resolve("arm64-v8a/libapm_fixture.so.dbg");
        var original = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector().inspect(symbol);
        assertEquals("arm64-v8a", original.abi());
        assertTrue(original.debugLine());
        String pc = fixtureAddress(Path.of(ndk), symbol, "apm_fixture_add");
        assertEquals(401, json("GET", "/api/v1/session", null, true).statusCode());
        assertEquals(200, json("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", true).statusCode());
        var created = json("POST", "/api/v1/apps", "{\"packageName\":\"com.example.agp.full.e2e\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String app = mapper.readTree(created.body()).path("appId").asText();
        String route = "/api/v1/apps/" + app + "/symbols/native-batches";
        String key = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/ingest-credential", null, true).body()).path("appKey").asText();
        /** 合成事件使用真实 FULL ELF 的 Build ID 与函数地址。 */
        var event = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")).path("events").get(0).deepCopy();
        event.put("eventId", "native-agp-full-synthetic").put("packageName", "com.example.agp.full.e2e")
                .put("occurredAt", Instant.now().minusSeconds(2).toEpochMilli()).put("appVersion", "full-e2e");
        var crash = (ObjectNode) event.path("crash").path("nativeCrash");
        ((ObjectNode) crash.path("modules").get(0)).put("name", "libapm_fixture.so").put("elfBuildId", original.elfBuildId());
        ((ObjectNode) crash.path("backtrace").path("frames").get(0)).put("relativePc", pc);
        ingest(event, key);
        JsonNode before = detail(app, "native-agp-full-synthetic");
        JsonNode statistics = mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats");
        var uploaded = multipart(route, Map.of("appVersion", "full-e2e", "targetAbi", "arm64-v8a", "targetElfBuildId", original.elfBuildId()),
                "native-debug-symbols.zip", Files.readAllBytes(archive), true);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        assertEquals(4, mapper.readTree(uploaded.body()).path("createdCount").asInt());
        JsonNode resolved = advanceNativeUntilAttempt(app, "native-agp-full-synthetic", "");
        /** 容器冷启动偶尔触发工具期限，遵循实际退避再重试。 */
        if ("native_tool_timeout".equals(resolved.path("symbolicationReason").asText())) {
            Instant retryAt = Instant.parse(resolved.path("stack").path("content").path("backtrace").path("frames").get(0)
                    .path("resolution").path("retryAt").asText());
            assertTrue(retryAt.isBefore(Instant.now().plusSeconds(35)));
            while (Instant.now().isBefore(retryAt.plusMillis(250))) Thread.sleep(100);
            String previousAttempt = resolved.path("stack").path("content").path("backtrace").path("frames").get(0)
                    .path("resolution").path("processedAt").asText();
            resolved = advanceNativeUntilAttempt(app, "native-agp-full-synthetic", previousAttempt);
        }
        JsonNode resolution = resolved.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution");
        assertEquals("symbolicated", resolution.path("status").asText(), resolved.toString());
        assertEquals("apm_fixture_add", resolution.path("inline").get(0).path("function").asText());
        assertTrue(resolution.path("inline").get(0).path("file").asText().endsWith("minimal.c"), resolution.toString());
        assertTrue(resolution.path("inline").get(0).path("line").asInt() > 0, resolution.toString());
        JsonNode source = resolution.path("source");
        assertFalse(source.path("symbolFileId").asText().isBlank());
        assertEquals(before.path("fingerprint"), resolved.path("fingerprint"));
        assertEquals(statistics, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        /** 替换成同 Build ID 的仅函数符号后，已固定的行号和来源保持不变。 */
        byte[] replacement = singleNativeZip(root.resolve("arm64-v8a/libapm_fixture.so.sym"));
        assertEquals(409, multipart(route, Map.of("appVersion", "full-e2e"), "function-only.zip", replacement, true).statusCode());
        String expected = mapper.writeValueAsString(List.of(Map.of("abi", "arm64-v8a", "elfBuildId", original.elfBuildId(),
                "symbolId", source.path("symbolFileId").asText(), "revision", source.path("revision").asInt())));
        var replaced = multipart(route, Map.of("appVersion", "full-e2e", "expectedEntries", expected), "function-only.zip", replacement, true);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(1, mapper.readTree(replaced.body()).path("replacedCount").asInt());
        for (int round = 0; round < 4; round++) nativeBackground.runOnce();
        JsonNode after = detail(app, "native-agp-full-synthetic");
        assertEquals(resolution, after.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution"));
        assertEquals(before.path("fingerprint"), after.path("fingerprint"));
        assertEquals(statistics, mapper.readTree(json("GET", "/api/v1/apps/" + app + "/crashes/overview", null, true).body()).path("stats"));
        /** 仅在人工页面验收时临时保持隔离服务。 */
        String hold = System.getenv("APM_FULL_HTTP_HOLD");
        if (hold != null && !hold.isBlank()) {
            Files.writeString(Path.of(hold + ".ready"), mapper.writeValueAsString(Map.of("port", port, "appId", app, "eventId", "native-agp-full-synthetic")));
            Instant deadline = Instant.now().plusSeconds(240);
            while (!Files.exists(Path.of(hold)) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertTrue(Files.exists(Path.of(hold)), "页面验收未在期限内结束");
        }
    }

    /** 隔离库可能有其他测试登记的版本任务；有界推进至目标帧真正尝试。 */
    private JsonNode advanceNativeUntilAttempt(String app, String eventId, String previousAttempt) throws Exception {
        JsonNode current = detail(app, eventId);
        for (int round = 0; round < 100; round++) {
            nativeBackground.runOnce();
            current = detail(app, eventId);
            JsonNode resolution = current.path("stack").path("content").path("backtrace").path("frames").get(0).path("resolution");
            String attempted = resolution.path("processedAt").asText("");
            if ("symbolicated".equals(resolution.path("status").asText())
                    || !attempted.isEmpty() && !attempted.equals(previousAttempt)) return current;
        }
        return current;
    }

    /** 同源网页 multipart 使用正确 CSRF 请求头，文件字节原样上传。 */
    private HttpResponse<String> multipart(String route, Map<String,String> fields, String filename, byte[] file, boolean csrf) throws Exception {
        var body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) body.write(("--symbols\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--symbols\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file); body.write("\r\n--symbols--\r\n".getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(base().resolve(route)).timeout(Duration.ofSeconds(15)).header("Content-Type", "multipart/form-data; boundary=symbols");
        if (csrf) request.header("X-XSRF-TOKEN", csrf());
        return web.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    /** 正式 App Key 上报请求不复用网页会话。 */
    private void ingest(ObjectNode event, String key) throws Exception {
        var body = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()); body.putArray("events").add(event);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base().resolve("/ingest/v1/batches"))
                .header("Content-Type", "application/json").header("X-App-Key", key).header("X-Schema-Version", "2")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, mapper.readTree(response.body()).path("accepted").asInt(), response.body());
    }
    /** 当前详情只读已持久结果。 */
    private JsonNode detail(String app, String event) throws Exception {
        var result = json("GET", "/api/v1/apps/" + app + "/crashes/events/" + event, null, true);
        assertEquals(200, result.statusCode(), result.body()); return mapper.readTree(result.body());
    }
    /** 真实 Session JSON 请求。 */
    private HttpResponse<String> json(String method, String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(15));
        if (csrf && !cookies.getCookieStore().getCookies().isEmpty()) request.header("X-XSRF-TOKEN", csrf());
        if (body != null) request.header("Content-Type", "application/json");
        return web.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    /** Cookie 仅在当前测试内存中使用，不输出至日志。 */
    private String csrf() { return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue(); }
    /** 本地随机端口。 */
    private URI base() { return URI.create("http://127.0.0.1:" + port); }
}
