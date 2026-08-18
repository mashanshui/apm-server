package com.shanshui.apmserver;

import com.shanshui.apmserver.repository.InMemoryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CrashApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEventRepository repository;

    @BeforeEach
    void clearRepository() {
        repository.clear();
    }

    @Test
    void acceptsJsonGzipAndReturnsPartialAcceptance() throws Exception {
        String body = "{\"requestId\":\"api-test\",\"events\":["
                + "{\"schemaVersion\":1,\"eventId\":\"api-start\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"api-session\","
                + "\"anonymousDeviceId\":\"api-device\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\"},"
                + "{\"schemaVersion\":1,\"eventId\":\"api-native\",\"eventType\":\"crash\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"api-session\","
                + "\"anonymousDeviceId\":\"api-device\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\",\"crash\":{\"kind\":\"native\",\"fatal\":true,"
                + "\"throwableChain\":[{\"type\":\"x\",\"frames\":[{\"className\":\"A\",\"methodName\":\"b\"}]}]}}"
                + "]}";
        byte[] compressed = gzip(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-Project-Key", "local-demo-key")
                        .header("X-Schema-Version", "1")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED_CRASH_KIND"));
    }

    @Test
    void protectsQueryByProjectHeaderAndReturnsOverview() throws Exception {
        String event = "{\"requestId\":\"api-query\",\"events\":["
                + "{\"schemaVersion\":1,\"eventId\":\"query-start\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"query-session\","
                + "\"anonymousDeviceId\":\"query-device\",\"appVersion\":\"3.2.0\",\"versionCode\":320,"
                + "\"buildId\":\"build-320\",\"environment\":\"production\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel-8\"}]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-Project-Key", "local-demo-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/demo-project/crashes/overview")
                        .header("X-Project-Id", "demo-project")
                        .header("X-User-Project-Ids", "demo-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.startedSessions").value(1))
                .andExpect(jsonPath("$.stats.status").value("ok"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/other-project/crashes/overview")
                        .header("X-Project-Id", "demo-project"))
                .andExpect(status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    private byte[] gzip(byte[] body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }
}
