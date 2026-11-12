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
    /** 当前随机端口。 */
    @LocalServerPort private int port;
    /** 使用正式 JSON 模型。 */
    @Autowired private ObjectMapper mapper;
    /** 合成只读成员使用正式密码编码。 */
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwords;
    /** 主动执行有限后台工作，不等待定时器。 */
    @Autowired private CrashBackgroundProcessor background;
    /** 核对 SO 不产生 JVM 工作和审计。 */
    @Autowired private JdbcTemplate jdbc;
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
        for (String name : List.of("008_background_crash_symbolication.sql", "006_memory_leak_reports.sql", "010_crash_diagnostics.sql", "011_native_crash_state.sql")) {
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
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, UUID.fromString(app)));
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
        assertEquals(422, json("GET", route + "/" + soId + "/crash-symbolication", null, true).statusCode());
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
        Files.writeString(Path.of("build/validation/versioned-symbols-http.json"), mapper.writeValueAsString(Map.of(
                "port", port, "appId", app, "token", token, "nativeEvent", "http-native", "defaultId", defaultId,
                "nativeBefore", nativeBefore, "jvmBefore", fixed)));
        String hold = System.getenv("APM_SYMBOL_HTTP_HOLD");
        if (hold != null) {
            Path stop = Path.of(hold);
            while (!Files.exists(stop)) java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
        }
    }

    /** 完整销毁并重建后端上下文后，文件、查询 Token、原始 Native 与成功来源保持。 */
    @Test
    @org.junit.jupiter.api.Order(2)
    void preservesFilesSourcesAndNativeMaterialAcrossBackendRestart() throws Exception {
        var proof = mapper.readTree(Files.readString(Path.of("build/validation/versioned-symbols-http.json")));
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
        assertEquals("raw_native", mapper.readTree(agent.body()).path("stack").path("format").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_id=?", Integer.class, UUID.fromString(app)));
    }

    /** 持续有字节到达也受总 60 秒期限约束，而非只靠空闲读取超时。 */
    @Test
    @org.junit.jupiter.api.Order(3)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_NATIVE_SLOW_HTTP", matches="true")
    void stopsAContinuouslySlowMultipartUploadAtTheSharedDeadline() throws Exception {
        var proof = mapper.readTree(Files.readString(Path.of("build/validation/versioned-symbols-http.json")));
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
        try (var socket = new Socket("127.0.0.1", port)) {
            String cookieHeader = cookies.getCookieStore().getCookies().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
            String header = "POST " + route + "/native-batches HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nCookie: " + cookieHeader + "\r\nX-XSRF-TOKEN: " + csrf()
                    + "\r\nContent-Type: multipart/form-data; boundary=partial\r\nContent-Length: 1000000\r\n\r\n"
                    + "--partial\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.zip\"\r\nContent-Type: application/zip\r\n\r\nabc";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            boolean busy = false;
            for (int attempt = 0; attempt < 30; attempt++) {
                var second = multipart(route + "/native-batches", Map.of("appVersion", "3.2.0"), "symbols.zip", zip, true);
                if (second.statusCode() == 503) { busy = true; break; }
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            }
            assertTrue(busy, "半包应持有接收许可，第二份请求不能排队");
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
