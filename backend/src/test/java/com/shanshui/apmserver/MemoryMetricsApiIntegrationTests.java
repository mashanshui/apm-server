package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryMetricsRepository;
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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证内存上报、成员查询、参数白名单和存储故障的 HTTP 边界。 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryMetricsApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMemoryMetricsRepository repository;

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
    void acceptsMemoryBatchAndAuthorizedMemberQueriesSummaryAndTrend() throws Exception {
        long now = System.currentTimeMillis();
        String body = "{\"requestId\":\"memory-api\",\"events\":["
                + memoryJson("memory-api-0", now - 2_000, 0, 10, null, true)
                + "," + memoryJson("memory-api-1", now - 1_000, 100, 20, 30, true) + "]}";

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .header("X-Schema-Version", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0));

        MockHttpSession session = loginAndCreateDemoApp();
        String path = "/api/v1/apps/" + appId() + "/memory-metrics";
        mockMvc.perform(MockMvcRequestBuilders.get(path + "/summary")
                        .param("from", java.time.Instant.ofEpochMilli(now - 10_000).toString())
                        .param("to", java.time.Instant.ofEpochMilli(now + 1_000).toString())
                        .param("processName", "com.example.app")
                        .param("scene", "com.example.HomeActivity")
                        .param("foreground", "true")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pss.sampleCount").value(2))
                .andExpect(jsonPath("$.pss.averageBytes").value(50.0))
                .andExpect(jsonPath("$.pss.p50Bytes").value(50.0))
                .andExpect(jsonPath("$.vss.sampleCount").value(2))
                .andExpect(jsonPath("$.javaHeap.sampleCount").value(1));

        mockMvc.perform(MockMvcRequestBuilders.get(path + "/trend")
                        .param("metric", "pss")
                        .param("interval", "hour")
                        .param("from", java.time.Instant.ofEpochMilli(now)
                                .truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString())
                        .param("to", java.time.Instant.ofEpochMilli(now)
                                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                                .plus(1, java.time.temporal.ChronoUnit.HOURS).toString())
                        .param("processName", "com.example.app")
                        .param("foreground", "true")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("pss"))
                .andExpect(jsonPath("$.interval").value("hour"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.points[0].sampleCount").value(2));
    }

    @Test
    void requiresSessionAndRejectsUnsupportedMemoryFilters() throws Exception {
        String path = "/api/v1/apps/" + appId() + "/memory-metrics/summary";
        mockMvc.perform(MockMvcRequestBuilders.get(path).header("X-App-Key", appKey()))
                .andExpect(status().isUnauthorized());

        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get(path).param("bitness", "64").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        memberRepository.deleteAll();
        mockMvc.perform(MockMvcRequestBuilders.get(path).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID()
                                + "/memory-metrics/summary")
                        .session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    @Test
    void rejectsWholeMemoryBatchWhenPackageDoesNotMatchCredential() throws Exception {
        String body = ("{\"requestId\":\"memory-package-mismatch\",\"events\":["
                + memoryJson("memory-package-mismatch-event", System.currentTimeMillis(), 1, null, null, true)
                + "]}").replace("\"packageName\":\"com.example.app\"",
                "\"packageName\":\"com.other.app\"");

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PACKAGE_NAME_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false));

        org.assertj.core.api.Assertions.assertThat(repository.findByEventId(appId(), "memory-package-mismatch-event"))
                .isEmpty();
    }

    @Test
    void rejectsInvalidMemoryMetricRangeForegroundAndTimeout() throws Exception {
        MockHttpSession session = loginAndCreateDemoApp();
        String path = "/api/v1/apps/" + appId() + "/memory-metrics/trend";
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "fd").param("interval", "hour").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_METRIC"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "pss").param("interval", "week").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTERVAL"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "pss").param("interval", "hour")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-03-01T00:00:00Z").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_LARGE"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "pss").param("interval", "hour")
                        .param("foreground", "unknown").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FOREGROUND"));
        mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .param("metric", "pss").param("interval", "hour")
                        .param("timeoutMs", "5001").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMEOUT"));
    }

    @Test
    void storageFailureIsRetryableAndDoesNotLookLikeEmptyData() throws Exception {
        repository.setAvailable(false);
        try {
            mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                            .header("X-App-Key", appKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"memory-store-down\",\"events\":["
                                    + memoryJson("memory-store-down-event", System.currentTimeMillis(), 1, null, null, true)
                                    + "]}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("EVENT_STORE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(true));
        } finally {
            repository.setAvailable(true);
        }
    }

    private MockHttpSession loginAndCreateDemoApp() throws Exception {
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private String memoryJson(String eventId, long occurredAt, long pss, Integer vss,
                              Integer javaHeap, boolean foreground) {
        String pssValue = Long.toString(pss);
        String vssValue = vss == null ? "null" : vss.toString();
        String javaValue = javaHeap == null ? "null" : javaHeap.toString();
        return "{\"schemaVersion\":2,\"eventId\":\"" + eventId + "\",\"eventType\":\"memory_sample\","
                + "\"occurredAt\":" + occurredAt + ",\"sessionId\":\"memory-session\",\"processId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"anonymousDeviceId\":\"memory-device\",\"packageName\":\"com.example.app\","
                + "\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"production\",\"channel\":\"official\",\"osVersion\":\"16\","
                + "\"deviceModel\":\"Pixel\",\"networkType\":\"wifi\",\"memorySample\":{"
                + "\"pssBytes\":" + pssValue + ",\"vssBytes\":" + vssValue
                + ",\"javaHeapUsedBytes\":" + javaValue + ",\"processName\":\"com.example.app\","
                + "\"foreground\":" + foreground + ",\"scene\":\"com.example.HomeActivity\"}}";
    }
}
