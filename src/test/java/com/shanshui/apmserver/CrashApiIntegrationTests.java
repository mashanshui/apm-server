package com.shanshui.apmserver;

import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.repository.ApmAppRepository;
import com.shanshui.apmserver.repository.AppMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
class CrashApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEventRepository repository;

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
    void acceptsJsonGzipAndReturnsPartialAcceptance() throws Exception {
        String body = "{\"requestId\":\"api-test\",\"events\":["
                        + "{\"schemaVersion\":2,\"eventId\":\"api-start\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"api-session\","
                + "\"anonymousDeviceId\":\"api-device\",\"packageName\":\"com.example.app\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\"},"
                + "{\"schemaVersion\":2,\"eventId\":\"api-native\",\"eventType\":\"crash\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"api-session\","
                + "\"anonymousDeviceId\":\"api-device\",\"packageName\":\"com.example.app\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\",\"crash\":{\"kind\":\"native\",\"fatal\":true,"
                + "\"throwableChain\":[{\"type\":\"x\",\"frames\":[{\"className\":\"A\",\"methodName\":\"b\"}]}]}}"
                + "]}";
        byte[] compressed = gzip(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .header("X-Schema-Version", "2")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED_CRASH_KIND"));
    }

    @Test
    void protectsQueryByMembershipAndReturnsOverview() throws Exception {
        String event = "{\"requestId\":\"api-query\",\"events\":["
                + "{\"schemaVersion\":2,\"eventId\":\"query-start\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"query-session\","
                + "\"anonymousDeviceId\":\"query-device\",\"packageName\":\"com.example.app\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\"}]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event))
                .andExpect(status().isOk());

        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/crashes/overview")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.startedSessions").value(1))
                .andExpect(jsonPath("$.stats.status").value("ok"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/crashes/overview")
                        .session(session)
                        .header("X-App-Id", "other-app")
                        .header("X-User-App-Ids", "other-app"))
                .andExpect(status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    @Test
    void rejectsWholeBatchWhenAnyEventUsesAnotherApplication() throws Exception {
        String body = "{\"requestId\":\"package-mismatch\",\"events\":["
                + "{\"schemaVersion\":2,\"eventId\":\"matching-event\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"session\","
                + "\"anonymousDeviceId\":\"device\",\"packageName\":\"com.example.app\",\"appVersion\":\"1.0\","
                + "\"versionCode\":1,\"buildId\":\"build\",\"environment\":\"test\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel\"},"
                + "{\"schemaVersion\":2,\"eventId\":\"wrong-event\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"session\","
                + "\"anonymousDeviceId\":\"device\",\"packageName\":\"com.other.app\",\"appVersion\":\"1.0\","
                + "\"versionCode\":1,\"buildId\":\"build\",\"environment\":\"test\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel\"}]}";

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PACKAGE_NAME_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertTrue(repository.findByEventId(TestAppIds.id("demo-app"), "matching-event").isEmpty());
        assertTrue(repository.findByEventId(TestAppIds.id("demo-app"), "wrong-event").isEmpty());
    }

    @Test
    void rejectsBatchContractWhenAppIdIsMissing() throws Exception {
        String body = "{\"requestId\":\"missing-app-id\",\"events\":[{"
                + "\"schemaVersion\":2,\"eventId\":\"missing-app-event\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"session\","
                + "\"anonymousDeviceId\":\"device\",\"appVersion\":\"1.0\",\"versionCode\":1,"
                + "\"buildId\":\"build\",\"environment\":\"test\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel\"}]}";

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BATCH"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertTrue(repository.findByEventId(TestAppIds.id("demo-app"), "missing-app-event").isEmpty());
    }

    private MockHttpSession loginAndCreateDemoApp() throws Exception {
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        return session;
    }

    private byte[] gzip(byte[] body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }
}
