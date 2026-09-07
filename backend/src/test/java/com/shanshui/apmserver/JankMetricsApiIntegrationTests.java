package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JankMetricsApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryJankEventRepository repository;

    @Autowired
    private ApmAppRepository appRepository;

    @Autowired
    private AppMemberRepository memberRepository;

    @BeforeEach
    void clearRepository() {
        repository.clear();
        memberRepository.deleteAll();
        appRepository.deleteAll();
        resetAppCredential("com.example.app");
    }

    @Test
    void authorizedMemberCanQueryFpsSuspensionAndDimensions() throws Exception {
        long now = System.currentTimeMillis();
        String body = "{\"requestId\":\"metrics\",\"events\":["
                + frameJson("frame-api", now, "checkout", "fps-v1", 50.0)
                + "," + suspensionJson("susp-api", now, "suspension-v1", 3600000, 2000)
                + "]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));

        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/fps")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.metrics[0].algorithmVersion").value("fps-v1"))
                .andExpect(jsonPath("$.metrics[0].p50Fps").value(50.0));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/suspension-rate")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.metrics[0].averageSecondsPerHour").value(2.0));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/dimensions")
                        .param("metric", "fps").param("dimension", "scene").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].dimensionValue").value("checkout"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/dimensions")
                        .param("metric", "suspension_rate").param("dimension", "scene").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIMENSION"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/trend")
                        .param("metric", "fps").param("interval", "hour").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("fps"))
                .andExpect(jsonPath("$.interval").value("hour"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.points[0].algorithmVersion").value("fps-v1"))
                .andExpect(jsonPath("$.points[0].p50Fps").value(50.0))
                .andExpect(jsonPath("$.points[0].averageSecondsPerHour").isEmpty());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/trend")
                        .param("metric", "suspension_rate").param("interval", "day").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].validRecords").value(1))
                .andExpect(jsonPath("$.points[0].averageSecondsPerHour").value(2.0))
                .andExpect(jsonPath("$.points[0].averageFps").isEmpty());
    }

    @Test
    void metricsRequireSessionAndAppMembership() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/fps"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/jank-metrics/trend")
                        .param("metric", "fps").param("interval", "hour"))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/jank-metrics/fps")
                        .session(session).header("X-App-Id", "demo-app"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/jank-metrics/trend")
                        .param("metric", "fps").param("interval", "hour").session(session)
                        .header("X-App-Id", "demo-app"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    @Test
    void trendRejectsInvalidMetricIntervalRangeLimitAndTimeout() throws Exception {
        MockHttpSession session = loginAndCreateDemoApp();
        String path = "/api/v1/apps/" + appId() + "/jank-metrics/trend";
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "cpu").param("interval", "day").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_METRIC"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "fps").param("interval", "week").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INTERVAL"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "suspension_rate").param("interval", "hour").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INTERVAL"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "fps").param("interval", "hour")
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-03-01T00:00:00Z")
                        .session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_LARGE"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "fps").param("interval", "hour").param("limit", "501").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "fps").param("interval", "hour").param("timeoutMs", "5001").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIMEOUT"));
    }

    private MockHttpSession loginAndCreateDemoApp() throws Exception {
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        return session;
    }

    private String frameJson(String eventId, long occurredAt, String scene, String algorithm, double fps) {
        return "{\"schemaVersion\":2,\"eventId\":\"" + eventId + "\",\"eventType\":\"frame_scene_summary\"," 
                + "\"occurredAt\":" + occurredAt + ",\"sessionId\":\"session-metrics\",\"anonymousDeviceId\":\"device-metrics\","
                + "\"packageName\":\"com.example.app\",\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"production\",\"channel\":\"official\",\"osVersion\":\"16\",\"deviceModel\":\"Pixel\","
                + "\"frameSceneSummary\":{\"scene\":\"" + scene + "\",\"algorithmVersion\":\"" + algorithm
                + "\",\"activeDurationMs\":1000,\"uiRefreshFrameCount\":50,\"refreshRateHz\":60,\"normalizedFps60\":" + fps + "}}";
    }

    private String suspensionJson(String eventId, long occurredAt, String algorithm,
                                  long foregroundMs, long suspensionMs) {
        return "{\"schemaVersion\":2,\"eventId\":\"" + eventId + "\",\"eventType\":\"foreground_suspension_summary\"," 
                + "\"occurredAt\":" + occurredAt + ",\"sessionId\":\"session-metrics\",\"anonymousDeviceId\":\"device-metrics\","
                + "\"packageName\":\"com.example.app\",\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"production\",\"channel\":\"official\",\"osVersion\":\"16\",\"deviceModel\":\"Pixel\","
                + "\"foregroundSuspensionSummary\":{\"algorithmVersion\":\"" + algorithm + "\",\"foregroundDurationMs\":"
                + foregroundMs + ",\"suspensionDurationMs\":" + suspensionMs + ",\"suspensionCount\":2,\"thresholdMs\":200}}";
    }
}
