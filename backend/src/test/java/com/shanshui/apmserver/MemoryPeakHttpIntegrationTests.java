package com.shanshui.apmserver;

import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 Servlet HTTP、ClickHouse、网页 Session 与 Agent/MCP 的独立合成验收。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apm.storage.mode=clickhouse", "apm.agent.query.enabled=true", "apm.ingest.max-past-days=7", "server.address=127.0.0.1", "apm.auth.bootstrap-admin-display-name=Memory validation", "APM_MEMORY_REPORT_CLEANUP_INITIAL_DELAY_MS=3600000"})
class MemoryPeakHttpIntegrationTests {
    /** 只使用本测试临时数据库及公开测试凭据。 */
    @Container private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test").withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);
    /** 框架分配的临时 HTTP 端口。 */
    @LocalServerPort private int port;
    /** 真实查询配置，用于注入边界后恢复。 */
    @Autowired private QueryProperties budgets;
    /** 真实连接配置，用于可恢复存储故障注入。 */
    @Autowired private ClickHouseProperties storage;
    /** 测试 JSON 编码器。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 独立 Cookie 容器模拟网页浏览器。 */
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    /** 仅本测试持有的登录会话客户端。 */
    private final HttpClient web = HttpClient.newBuilder().cookieHandler(cookies).build();

    /** 将存储连接绑定到临时容器，不使用现有业务库。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        // 应用启动时Crash状态仓库会读取持久状态，先在隔离库准备相关只读启动表。
        var bootstrap = new ClickHouseProperties();
        bootstrap.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        bootstrap.setUsername("apm_test");
        bootstrap.setPassword("apm_test_password");
        bootstrap.setDatabase("default");
        var client = new ClickHouseHttpClient(bootstrap);
        client.execute("CREATE DATABASE IF NOT EXISTS apm");
        for (String migration : List.of("008_background_crash_symbolication.sql", "009_memory_runtime_peaks.sql", "010_crash_diagnostics.sql")) {
            String ddl = Files.readString(Path.of("src/main/resources/db/clickhouse", migration));
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        registry.add("apm.clickhouse.url", () -> "http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        registry.add("apm.clickhouse.database", () -> "apm");
        registry.add("apm.clickhouse.username", () -> "apm_test");
        registry.add("apm.clickhouse.password", () -> "apm_test_password");
    }

    /** 固定样例从上传到持久查询、跨入口比较和故障处理均走真实 HTTP。 */
    @Test
    void validatesPersistentHttpAndAgentContracts() throws Exception {
        // 先创建临时 schema，后台维护在测试配置中延迟，不会提前查询领域表。
        storage.setDatabase("default");
        var database = new ClickHouseHttpClient(storage);
        database.execute("CREATE DATABASE IF NOT EXISTS apm");
        database.execute(Files.readString(Path.of("src/main/resources/db/clickhouse/009_memory_runtime_peaks.sql")));
        database.execute(Files.readString(Path.of("src/main/resources/db/clickhouse/005_memory_metrics.sql")));
        storage.setDatabase("apm");
        send("GET", "/api/v1/session", null, Map.of(), 401);
        send("POST", "/api/v1/auth/login", "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", Map.of(), 200);
        /** 原运行测试应用，只包含合成信息。 */
        String app = send("POST", "/api/v1/apps", "{\"packageName\":\"com.shanshui.performance\"}", Map.of(), 201).path("appId").asText();
        String key = send("GET", "/api/v1/apps/" + app + "/ingest-credential", null, Map.of(), 200).path("appKey").asText();
        String token = send("POST", "/api/v1/apps/" + app + "/query-tokens", "{\"name\":\"memory-test\",\"expiresInDays\":30}", Map.of(), 201).path("token").asText();
        /** 本次时间窗与旧运行截止，避免固定历史样例过期。 */
        long end = Instant.now().minusSeconds(600).toEpochMilli();
        String from = Instant.ofEpochMilli(end - 10000).toString();
        String to = Instant.ofEpochMilli(end + 10000).toString();
        var events = mapper.createArrayNode();
        for (int index = 0; index < 5; index++) {
            ObjectNode event = (ObjectNode) mapper.readTree(Files.readString(Path.of("../docs/fixtures/memory-runtime-peaks.json")));
            event.put("eventId", UUID.randomUUID().toString()).put("occurredAt", end);
            ObjectNode payload = (ObjectNode) event.path("memoryPeakSummary");
            payload.put("collectionStartedAt", end - 5000).put("lastSampledAt", end);
            for (String metric : List.of("pss", "vss", "javaHeap")) {
                ObjectNode peak = (ObjectNode) payload.path(metric);
                peak.put("occurredAt", end - 1000);
                peak.put("scene", metric.equals("javaHeap") ? "B" : "A");
                peak.put("foreground", !metric.equals("vss"));
            }
            if (index < 4) ((ObjectNode) payload.path("pss")).put("bytes", index * 100L);
            else payload.putNull("pss");
            events.add(event);
        }
        String batch = mapper.writeValueAsString(Map.of("requestId", "peak-http", "events", events));
        assertThat(send("POST", "/ingest/v1/batches", batch, Map.of("X-App-Key", key, "X-Schema-Version", "2"), 200).path("accepted").asInt()).isEqualTo(5);
        assertThat(send("POST", "/ingest/v1/batches", batch, Map.of("X-App-Key", key), 200).path("duplicate").asInt()).isEqualTo(5);
        // 普通样本即使数值很大也不会进入运行峰值概览。
        database.execute("INSERT INTO apm_memory_sample (app_id,event_id,event_time,received_time,pss_bytes) VALUES ('" + app + "','legacy',fromUnixTimestamp64Milli(" + end + "),now64(3),999999999)");
        String query = "?from=" + from + "&to=" + to;
        String prefix = "/api/v1/apps/" + app + "/memory-metrics";
        JsonNode summary = send("GET", prefix + "/summary" + query, null, Map.of(), 200);
        assertThat(summary.path("pss").path("runCount").asInt()).isEqualTo(4);
        assertThat(summary.path("pss").path("averageBytes").asDouble()).isEqualTo(150);
        assertThat(summary.path("pss").path("p95Bytes").asDouble()).isEqualTo(285);
        assertThat(summary.path("dataSource").asText()).isEqualTo("clickhouse");
        assertThat(summary.toString()).doesNotContain("sampleCount");
        for (String suffix : List.of("/summary", "/trend")) {
            JsonNode expected = send("GET", prefix + suffix + query, null, Map.of(), 200);
            JsonNode actual = send("GET", "/api/agent/v1/memory-metrics" + suffix + query, null, Map.of("Authorization", "Bearer " + token), 200);
            assertThat(actual).isEqualTo(expected);
        }
        JsonNode foreground = send("GET", prefix + "/summary" + query + "&scene=A&foreground=true", null, Map.of(), 200);
        assertThat(foreground.path("pss").path("runCount").asInt()).isEqualTo(4);
        assertThat(foreground.path("vss").path("runCount").asInt()).isZero();
        assertThat(foreground.path("javaHeap").path("runCount").asInt()).isZero();
        long oldRows = budgets.getMaxRowsToRead();
        try {
            budgets.setMaxRowsToRead(1);
            assertThat(send("GET", prefix + "/summary" + query, null, Map.of(), 422).path("code").asText()).isEqualTo("QUERY_RESOURCE_LIMIT");
        } finally { budgets.setMaxRowsToRead(oldRows); }
        try {
            storage.setDatabase("missing_test_database");
            assertThat(send("POST", "/ingest/v1/batches", batch, Map.of("X-App-Key", key), 503).path("retryable").asBoolean()).isTrue();
            assertThat(send("GET", prefix + "/summary" + query, null, Map.of(), 503).path("code").asText()).isEqualTo("EVENT_STORE_UNAVAILABLE");
        } finally { storage.setDatabase("apm"); }
        // Node 官方 MCP 客户端经真实 HTTP 复用 Java Agent 的同一固定集。
        if ("true".equals(System.getenv("APM_MEMORY_MCP_VERIFY"))) {
            Process mcp = new ProcessBuilder("node", "scripts/verify-memory-runtime-peaks.mjs")
                    .directory(Path.of("../mcp-server").toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (var input = mcp.getOutputStream()) {
                input.write(mapper.writeValueAsBytes(Map.of("origin", origin(), "token", token, "appId", app, "from", from, "to", to)));
            }
            if (!mcp.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                mcp.destroyForcibly();
                throw new AssertionError("MCP 验收超过45秒");
            }
            String mcpResult = new String(mcp.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(mcp.exitValue()).as(mcpResult).isZero();
            System.out.println(mcpResult);
        }
        if ("true".equals(System.getenv("APM_MEMORY_JOINT_VERIFY"))) {
            verifyJointClient(app, key, token, from, to);
        }
        // 仅显式视觉验收时保留测试服务器，配置文件不包含上报Key或查询Token。
        if ("true".equals(System.getenv("APM_MEMORY_VISUAL_HOLD"))) {
            // 视觉样例使用可读MiB数值，并用独立版本筛选，不影响共同固定集断言。
            var visualEvents = mapper.createArrayNode();
            for (int index = 0; index < 4; index++) {
                ObjectNode event = (ObjectNode) mapper.readTree(mapper.writeValueAsString(events.get(index)));
                event.put("eventId", UUID.randomUUID().toString()).put("appVersion", "visual");
                ObjectNode peaks = (ObjectNode) event.path("memoryPeakSummary");
                ((ObjectNode) peaks.path("pss")).put("bytes", (index + 1L) * 32 * 1048576);
                ((ObjectNode) peaks.path("vss")).put("bytes", (index + 1L) * 128 * 1048576);
                ((ObjectNode) peaks.path("javaHeap")).put("bytes", (index + 1L) * 16 * 1048576);
                visualEvents.add(event);
            }
            send("POST", "/ingest/v1/batches", mapper.writeValueAsString(Map.of("requestId", "visual", "events", visualEvents)), Map.of("X-App-Key", key), 200);
            Path visual = Path.of("build/memory-peak-visual.json");
            Files.writeString(visual, mapper.writeValueAsString(Map.of("origin", origin(), "appId", app, "from", from, "to", to)));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
            while (!Files.exists(Path.of("build/memory-peak-visual.done")) && System.nanoTime() < deadline) Thread.sleep(1000);
        }
    }

    /** 显式联合验收才驱动模拟器；凭据只经标准输入传递，控制文件不包含秘密。 */
    private void verifyJointClient(String app, String key, String token, String from, String to) throws Exception {
        /** 联合工具在仓库根目录运行，不操作现有开发服务。 */
        Process process = new ProcessBuilder("python3", "scripts/verify-memory-runtime-peaks.py")
                .directory(Path.of("..").toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        /** 原隔离存储配置，故障注入结束必须恢复。 */
        String originalDatabase = storage.getDatabase();
        /** 只传模式的测试控制文件与确认文件。 */
        Path control = Path.of("build/memory-peak-joint-control.json");
        Path acknowledgment = Path.of("build/memory-peak-joint-control.ack");
        Files.deleteIfExists(control);
        Files.deleteIfExists(acknowledgment);
        try (var input = process.getOutputStream()) {
            input.write(mapper.writeValueAsBytes(Map.of("origin", origin(), "appId", app, "appKey", key,
                    "token", token, "from", from, "to", to, "clickhouseUrl", storage.getUrl(),
                    "clickhouseUser", storage.getUsername(), "clickhousePassword", storage.getPassword())));
        }
        /** 有界会话，为覆盖安装、故障退避与真实网页检查留出时间。 */
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
        /** 仅处理模式改变，不重复修改业务配置。 */
        String previousMode = "normal";
        try {
            while (!process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline) throw new AssertionError("联合验收超过30分钟");
                if (Files.exists(control)) {
                    /** Python 通过原子替换发布完整控制文件。 */
                    String mode = mapper.readTree(Files.readString(control)).path("mode").asText();
                    if (!mode.equals(previousMode)) {
                        assertThat(mode).isIn("normal", "fail");
                        storage.setDatabase(mode.equals("fail") ? "missing_joint_database" : originalDatabase);
                        previousMode = mode;
                        Files.writeString(acknowledgment, mode);
                    }
                }
            }
            /** 子工具只输出脱敏断言进度，不输出上报 Key 或 Token。 */
            String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(result).isZero();
            System.out.println(result);
        } finally {
            storage.setDatabase(originalDatabase);
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            }
            Files.deleteIfExists(control);
            Files.deleteIfExists(acknowledgment);
        }
    }

    /** 返回临时后端地址。 */
    private String origin() { return "http://127.0.0.1:" + port; }

    /** 真实请求复用当前CSRF Cookie，不输出任何凭据。 */
    private JsonNode send(String method, String path, String body, Map<String,String> headers, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin() + path)).timeout(java.time.Duration.ofSeconds(15));
        headers.forEach(request::header);
        if (!method.equals("GET") && !path.startsWith("/ingest")) cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst()
                .ifPresent(cookie -> request.header("X-XSRF-TOKEN", URLDecoder.decode(cookie.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
        if (body != null) request.header("Content-Type", "application/json");
        var result = web.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).as("%s %s: %s", method, path, result.body()).isEqualTo(status);
        return mapper.readTree(result.body());
    }
}
