package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接入真实 ClickHouse 的 HTTP 和应用权限联合测试。 */
@Testcontainers
@SpringBootTest(properties = {"apm.storage.mode=clickhouse", "apm.clickhouse.enabled=true"})
@AutoConfigureMockMvc
class AnrClickHouseHttpTests extends AppIngestApiTestSupport {
    /** 隔离数据库，不连接工作区已有服务。 */
    @Container
    private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);
    /** MVC 请求入口。 */
    @Autowired private MockMvc mvc;
    /** 应用成员关系，用于撤销测试用户的查看权限。 */
    @Autowired private AppMemberRepository members;

    /** 启动上下文前初始化独立数据库和必要迁移。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        properties.setUsername("test");
        properties.setPassword("test-only");
        properties.setDatabase("default");
        var client = new ClickHouseHttpClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "010_crash_diagnostics.sql",
                "011_native_crash_state.sql", "012_native_symbolication_state.sql", "013_anr_events.sql",
                "014_anr_symbolication_state.sql")) {
            String ddl;
            try (var stream = AnrClickHouseHttpTests.class.getResourceAsStream("/db/clickhouse/" + name)) {
                ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        registry.add("apm.clickhouse.url", properties::getUrl);
        registry.add("apm.clickhouse.database", () -> "apm");
        registry.add("apm.clickhouse.username", () -> "test");
        registry.add("apm.clickhouse.password", () -> "test-only");
    }

    /** 已存在数据库记录在成员权限撤销后不能被查询。 */
    @Test
    void enforcesAuthorizationAgainstPersistedEvents() throws Exception {
        resetAppCredential("com.example.app");
        String body = """
                {"requestId":"anr-http","events":[{"schemaVersion":2,"eventId":"http-anr","eventType":"anr",
                 "occurredAt":%d,"sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"1","versionCode":1,
                 "buildId":"build","environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic",
                 "anr":{"source":"message_timeout","mainThreadStack":"a.b(Source.java:1)","mainThreadStackTruncated":false}}]}
                """.formatted(System.currentTimeMillis());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        String base = "/api/v1/apps/" + appId() + "/anrs";
        mvc.perform(get(base + "/overview").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(1));
        mvc.perform(get(base + "/events/http-anr").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.anr.mainThreadStack").value("a.b(Source.java:1)"));
        members.deleteAll();
        mvc.perform(get(base + "/overview").session(session)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/events").session(session)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/events/http-anr").session(session)).andExpect(status().isNotFound());
    }
}
