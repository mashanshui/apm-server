package com.shanshui.apmserver;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Servlet、独立 Cookie 容器及 JDBC 会话验证，禁止以可变 Mock Session 代替旧标识。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.autoconfigure.exclude=", "spring.session.jdbc.initialize-schema=never",
        "spring.session.timeout=8h", "spring.session.jdbc.cleanup-cron=-", "apm.agent.query.enabled=true", "apm.agent.analysis.enabled=true", "server.servlet.session.cookie.http-only=true",
        "server.servlet.session.cookie.same-site=lax", "server.servlet.session.cookie.secure=false"})
@Import(AuthenticationHttpPostgresTests.AnonymousSessionConfiguration.class)
class AuthenticationHttpPostgresTests {
    /** 独立数据库仅保存本测试的合成数据。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 本地随机 HTTP 端口。 */
    @LocalServerPort private int port;
    /** 直接核验会话 ID 不存在旧标识别名。 */
    @Autowired private JdbcTemplate jdbc;
    /** 强制确认本测试实际装配 JDBC 会话实现。 */
    @Autowired private JdbcIndexedSessionRepository sessions;
    /** 安全读取测试业务响应，避免依赖 JSON 字段顺序。 */
    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    /** 将测试容器连接配置交给 Flyway、JPA 与 Spring Session。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 独立保存旧 Cookie，再确认轮换、CSRF 首写及退出。 */
    @Test
    void rotatesPersistedSessionAndRejectsOldCookie() throws Exception {
        // 用户与持有登录前标识的一方拥有独立 Cookie 容器。
        CookieManager browser = cookies();
        CookieManager oldBrowser = cookies();
        HttpClient client = client(browser);
        assertEquals(200, send(client, "GET", "/api/v1/auth/login", null, null).statusCode());
        assertEquals(401, send(client, "GET", "/api/v1/session", null, null).statusCode());
        String oldSession = cookie(browser, "SESSION");
        String oldCsrf = cookie(browser, "XSRF-TOKEN");
        for (HttpCookie value : browser.getCookieStore().getCookies()) {
            oldBrowser.getCookieStore().add(base(), (HttpCookie) value.clone());
        }
        HttpResponse<String> login = login(client, oldCsrf);
        assertEquals(200, login.statusCode());
        // Cookie 安全属性与数据库超时均由真实装配核验。
        String sessionHeader = login.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("SESSION=")).findFirst().orElseThrow();
        assertTrue(sessionHeader.contains("HttpOnly"));
        assertTrue(sessionHeader.contains("SameSite=Lax"));
        assertFalse(sessionHeader.contains("Secure"));
        String newSession = cookie(browser, "SESSION");
        String newCsrf = cookie(browser, "XSRF-TOKEN");
        assertNotEquals(oldSession, newSession);
        assertNotEquals(oldCsrf, newCsrf);
        assertNotNull(sessions.findById(decode(newSession)));
        assertEquals(Duration.ofHours(8), ((org.springframework.session.Session) sessions.findById(decode(newSession))).getMaxInactiveInterval());
        assertNull(sessions.findById(decode(oldSession)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?",
                Integer.class, decode(oldSession)));
        assertEquals(401, send(client(oldBrowser), "GET", "/api/v1/session", null, null).statusCode());
        assertEquals(200, send(client, "GET", "/api/v1/session", null, null).statusCode());
        // 新 Cookie 不能搭配旧 CSRF 请求头执行业务写入。
        assertEquals(403, send(client, "POST", "/api/v1/apps",
                "{\"packageName\":\"com.example.rejected\"}", oldCsrf).statusCode());
        HttpResponse<String> created = send(client, "POST", "/api/v1/apps",
                "{\"name\":\"原名称\",\"description\":\"描述\",\"packageName\":\"com.example.http" + UUID.randomUUID().toString().replace("-", "") + "\"}", newCsrf);
        assertEquals(201, created.statusCode());
        String appId = created.body().split("\"appId\":\"")[1].split("\"")[0];
        assertEquals(200, send(client, "PATCH", "/api/v1/apps/" + appId,
                "{\"name\":\"新名称\",\"description\":\"描述\"}", newCsrf).statusCode());
        assertEquals(204, send(client, "POST", "/api/v1/auth/logout", null, newCsrf).statusCode());
        assertNull(sessions.findById(decode(newSession)));
        assertEquals(401, send(client, "GET", "/api/v1/session", null, null).statusCode());
    }

    /** 无已有会话也能登录，并确认失败登录与缺少 CSRF 都不会保存身份。 */
    @Test
    void createsSessionOnlyAfterSuccessfulAuthentication() throws Exception {
        // CSRF 引导不应提前创建认证会话。
        CookieManager browser = cookies();
        HttpClient client = client(browser);
        assertEquals(401, send(client, "GET", "/api/v1/session", null, null).statusCode());
        assertTrue(browser.getCookieStore().getCookies().stream().noneMatch(c -> c.getName().equals("SESSION")));
        String token = cookie(browser, "XSRF-TOKEN");
        assertEquals(403, login(client, null).statusCode());
        assertEquals(401, send(client, "POST", "/api/v1/auth/login",
                "{\"email\":\"test@example.com\",\"password\":\"wrong\"}", token).statusCode());
        assertEquals(401, send(client, "GET", "/api/v1/session", null, null).statusCode());
        assertEquals(200, login(client, cookie(browser, "XSRF-TOKEN")).statusCode());
        assertNotNull(sessions.findById(decode(cookie(browser, "SESSION"))));
        assertNotEquals(token, cookie(browser, "XSRF-TOKEN"));
        assertEquals(200, send(client, "GET", "/api/v1/session", null, null).statusCode());
    }

    /** 三条真实安全链的框架失败仍返回统一 JSON，不经 /error 改写。 */
    @Test
    void preservesFrameworkErrorsAcrossWebAgentAndWorkerHttpChains() throws Exception {
        // 正式登录与创建入口建立本测试的独立身份。
        CookieManager browser = cookies();
        HttpClient web = client(browser);
        send(web, "GET", "/api/v1/session", null, null);
        assertEquals(200, login(web, cookie(browser, "XSRF-TOKEN")).statusCode());
        String csrf = cookie(browser, "XSRF-TOKEN");
        HttpResponse<String> created = send(web, "POST", "/api/v1/apps",
                "{\"packageName\":\"com.example.errors" + UUID.randomUUID().toString().replace("-", "") + "\"}", csrf);
        assertEquals(201, created.statusCode());
        String appId = mapper.readTree(created.body()).path("appId").asText();
        String prefix = "/api/v1/apps/" + appId;
        int workers = jdbc.queryForObject("SELECT count(*) FROM analysis_worker_credential", Integer.class);
        assertError(send(web, "GET", "/api/v1/apps/bad-id", null, null), 400, "INVALID_PARAMETER");
        assertError(send(web, "GET", prefix + "/janks/issues?limit=abc", null, null), 400, "INVALID_PARAMETER");
        assertError(send(web, "POST", prefix + "/analysis-workers", "{}", csrf), 400, "VALIDATION_FAILED");
        assertEquals(workers, jdbc.queryForObject("SELECT count(*) FROM analysis_worker_credential", Integer.class));
        assertError(send(web, "POST", "/api/v1/apps", "", csrf), 400, "INVALID_REQUEST_BODY");
        HttpResponse<String> method = send(web, "DELETE", "/api/v1/session", null, csrf);
        assertError(method, 405, "METHOD_NOT_ALLOWED");
        assertTrue(method.headers().firstValue("Allow").orElseThrow().contains("GET"));
        HttpRequest unsupported = HttpRequest.newBuilder(base().resolve("/api/v1/apps"))
                .header("X-XSRF-TOKEN", csrf).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("invalid")).build();
        assertError(web.send(unsupported, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
        // 真实创建的查询 Token 与 Worker 身份不使用 Cookie 替代。
        HttpResponse<String> queryToken = send(web, "POST", prefix + "/query-tokens",
                "{\"name\":\"HTTP 受控测试\",\"expiresInDays\":30}", csrf);
        assertEquals(201, queryToken.statusCode());
        String token = mapper.readTree(queryToken.body()).path("token").asText();
        HttpResponse<String> worker = send(web, "POST", prefix + "/analysis-workers", "{\"name\":\"HTTP 受控测试\"}", csrf);
        assertEquals(200, worker.statusCode());
        String credential = mapper.readTree(worker.body()).path("credential").asText();
        HttpClient isolated = client(cookies());
        assertError(bearer(isolated, "GET", "/api/agent/v1/janks/issues?limit=abc", null, token), 400, "INVALID_PARAMETER");
        assertError(bearer(isolated, "POST", "/api/agent/v1/application", null, token), 405, "AGENT_READ_ONLY");
        assertError(bearer(isolated, "GET", "/api/worker/v1/tasks/not-a-uuid", null, credential), 400, "INVALID_PARAMETER");
        String run = UUID.randomUUID().toString();
        assertError(bearer(isolated, "GET", "/api/worker/v1/tasks/runs/" + run + "/evidence", null, credential), 400, "INVALID_PARAMETER");
        assertError(bearer(isolated, "POST", "/api/worker/v1/tasks/" + run + "/claim", "{}", credential), 400, "VALIDATION_FAILED");
        HttpResponse<String> sensitive = bearer(isolated, "POST", "/api/worker/v1/tasks/runs/" + run + "/heartbeat",
                "{\"token\":\"SENSITIVE-lease\",", credential);
        assertError(sensitive, 400, "INVALID_REQUEST_BODY");
        assertFalse(sensitive.body().contains("SENSITIVE-lease"));
        assertError(send(isolated, "GET", "/api/v1/apps/not-a-uuid", null, null), 401, "AUTH_REQUIRED");
        assertError(send(isolated, "GET", "/api/agent/v1/janks/issues?limit=abc", null, null), 401, "QUERY_TOKEN_INVALID");
        assertError(send(isolated, "GET", "/api/worker/v1/tasks/not-a-uuid", null, null), 401, "WORKER_CREDENTIAL_INVALID");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_analysis_run", Integer.class));
    }

    /** JDBC 会话下经真实 HTTP 遍历 Jank，并核对网页与应用 Token 的完整统计。 */
    @Test void exercisesQueryPagesDetailsReportTrendAndAgentIsolation() throws Exception {
        CookieManager browser = cookies();
        HttpClient web = client(browser);
        send(web,"GET","/api/v1/session",null,null);
        assertEquals(200,login(web,cookie(browser,"XSRF-TOKEN")).statusCode());
        String csrf = cookie(browser,"XSRF-TOKEN");
        var created = send(web,"POST","/api/v1/apps","{\"packageName\":\"com.example.query"+UUID.randomUUID().toString().replace("-","")+"\"}",csrf);
        UUID app = UUID.fromString(mapper.readTree(created.body()).path("appId").asText());
        var fixture = mapper.readValue(getClass().getResourceAsStream("/fixtures/jank-dataset.json"),com.shanshui.apmserver.ingest.api.EventBatchRequest.class);
        JankTestSupport.appendFixture(app,jankEvents,fixture);
        String range = "from=2026-08-15T09:59:00Z&to=2026-08-16T00:02:00Z";
        String prefix = "/api/v1/apps/"+app;
        String token = mapper.readTree(send(web,"POST",prefix+"/query-tokens","{\"name\":\"综合验收\",\"expiresInDays\":30}",csrf).body()).path("token").asText();
        HttpClient agent = client(cookies());
        var overview = send(web,"GET",prefix+"/janks/overview?"+range,null,null);
        assertEquals(3,mapper.readTree(overview.body()).path("stats").path("jankEvents").asLong());
        assertEquals(mapper.readTree(overview.body()),mapper.readTree(bearer(agent,"GET","/api/agent/v1/janks/overview?"+range,null,token).body()));
        var issues = mapper.readTree(send(web,"GET",prefix+"/janks/issues?"+range+"&limit=1",null,null).body());
        String fingerprint = issues.path("issues").get(0).path("fingerprint").asText();
        String cursor = issues.path("nextCursor").asText();
        var second = send(web,"GET",prefix+"/janks/issues?"+range+"&limit=1&cursor="+cursor,null,null);
        assertEquals(1,mapper.readTree(second.body()).path("issues").size());
        assertEquals(mapper.readTree(second.body()),mapper.readTree(bearer(agent,"GET","/api/agent/v1/janks/issues?"+range+"&limit=1&cursor="+cursor,null,token).body()));
        var events = mapper.readTree(send(web,"GET",prefix+"/janks/issues/"+fingerprint+"/events?"+range+"&limit=1",null,null).body());
        String eventId = events.path("events").get(0).path("eventId").asText();
        assertEquals(200,send(web,"GET",prefix+"/janks/events/"+eventId,null,null).statusCode());
        assertEquals(1,mapper.readTree(send(web,"GET",prefix+"/janks/issues/"+fingerprint+"/events?"+range+"&limit=1&cursor="+events.path("nextCursor").asText(),null,null).body()).path("events").size());
        var path = new com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath("A","root","reason",1,java.util.List.of(new com.shanshui.apmserver.memory.api.MemoryLeakPathNode("A","instance",null)),"");
        for (int index=0;index<3;index++) memoryReports.append(new com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport(app,UUID.randomUUID(),java.time.Instant.parse("2026-08-15T10:00:00Z"),java.time.Instant.now(),"synthetic","1",1,"d"+index,null,"p",null,null,null,null,null,null,null,null,null,mapper.readTree("{}"),java.util.List.of(path),"0".repeat(64),null,null,0));
        var reports = send(web,"GET",prefix+"/memory-leaks/issues?"+range+"&pageSize=1",null,null);
        assertEquals(3,mapper.readTree(reports.body()).path("totalOccurrences").asLong());
        assertEquals(3,mapper.readTree(send(web,"GET",prefix+"/memory-leaks/issues?"+range+"&page=2147483647",null,null).body()).path("totalOccurrences").asLong());
        assertEquals(mapper.readTree(reports.body()),mapper.readTree(bearer(agent,"GET","/api/agent/v1/memory-leaks/issues?"+range+"&pageSize=1",null,token).body()));
        assertEquals(200,send(web,"GET",prefix+"/memory-leaks/trend?"+range+"&interval=hour",null,null).statusCode());
        assertError(bearer(agent,"GET","/api/agent/v1/janks/issues?"+range+"&cursor=old-fingerprint",null,token),400,"INVALID_CURSOR");
        assertError(bearer(agent,"GET","/api/agent/v1/janks/issues?appId="+UUID.randomUUID(),null,token),400,"INVALID_FILTER");
        var otherCreated = send(web,"POST","/api/v1/apps","{\"packageName\":\"com.example.other"+UUID.randomUUID().toString().replace("-","")+"\"}",csrf);
        String otherApp = mapper.readTree(otherCreated.body()).path("appId").asText();
        String other = mapper.readTree(send(web,"POST","/api/v1/apps/"+otherApp+"/query-tokens","{\"name\":\"隔离检查\",\"expiresInDays\":30}",csrf).body()).path("token").asText();
        assertError(bearer(client(cookies()),"GET","/api/agent/v1/janks/events/"+eventId,null,other),404,"EVENT_NOT_FOUND");
    }

    /** 仅 HTTP 综合测试使用的内存事实，与真实 ClickHouse 对照测试分别记录。 */
    @Autowired private com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository jankEvents;
    /** 测试直接写入合成报告，不改变上传协议。 */
    @Autowired private com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryLeakReportRepository memoryReports;

    /** 独立 Bearer 入口没有网页 Cookie 或 CSRF。 */
    private HttpResponse<String> bearer(HttpClient client, String method, String path, String body, String secret)
            throws Exception {
        // 请求秘密仅用于当前 HTTP 头，不写入诊断输出。
        HttpRequest.Builder request = HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 不打印整个输入或含凭据的成功响应，只断言失败形状。 */
    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        var error = mapper.readTree(response.body());
        assertEquals(code, error.path("code").asText());
        assertFalse(error.path("message").asText().isEmpty());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(error.has("requestId"));
        assertTrue(error.path("errors").isArray());
        assertFalse(error.path("timestamp").asText().isEmpty());
    }

    /** 返回隔离 Cookie 管理器，不在响应或日志中输出凭据。 */
    private CookieManager cookies() { return new CookieManager(null, CookiePolicy.ACCEPT_ALL); }
    /** 限定本地请求的连接超时。 */
    private HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }
    /** 本测试的 HTTP 根地址。 */
    private URI base() { return URI.create("http://127.0.0.1:" + port); }
    /** 取浏览器当前有效 Cookie 值，仅用于内存断言。 */
    private String cookie(CookieManager browser, String name) {
        return browser.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
                .findFirst().orElseThrow().getValue();
    }
    /** Spring Session 默认把 UUID 会话 ID 编码到 Cookie。 */
    private String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    /** 发送正确凭据，复用标准 CSRF Cookie 流程。 */
    private HttpResponse<String> login(HttpClient client, String csrf) throws Exception {
        return send(client, "POST", "/api/v1/auth/login",
                "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}", csrf);
    }
    /** 请求经过真实安全过滤链与 Servlet 分派。 */
    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String csrf)
            throws Exception {
        // 请求上限避免测试等待无界。
        HttpRequest.Builder request = HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 测试专用入口只创建匿名会话，不改变生产路由。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class AnonymousSessionConfiguration {
        /** 在登录的 GET 路由上提供匿名会话引导。 */
        @Bean AnonymousSessionEndpoint anonymousSessionEndpoint() { return new AnonymousSessionEndpoint(); }
    }
    /** 匿名会话不在业务 JSON 中公开 ID。 */
    @RestController
    static class AnonymousSessionEndpoint {
        /** 模拟用户登录前已持有匿名会话。 */
        @GetMapping("/api/v1/auth/login")
        void create(HttpServletRequest request) { request.getSession(true); }
    }
}
