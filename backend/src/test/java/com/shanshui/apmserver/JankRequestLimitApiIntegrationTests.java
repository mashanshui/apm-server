package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "apm.ingest.max-request-bytes=2048",
        "apm.ingest.max-decompressed-bytes=4096",
        "apm.ingest.max-event-bytes=300"
})
@AutoConfigureMockMvc
class JankRequestLimitApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryJankEventRepository repository;

    @BeforeEach
    void clearRepository() {
        repository.clear();
        resetAppCredential("com.example.app");
    }

    @Test
    void rejectsUncompressedRequestBeforeBindingWhenCompressedBudgetIsNotRelevant() throws Exception {
        String body = "{\"requestId\":\"large\",\"events\":[]}" + " ".repeat(3_000);
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void rejectsGzipRequestAfterDecompressionLimit() throws Exception {
        String body = "{\"requestId\":\"large-gzip\",\"events\":[]}" + " ".repeat(8_000);
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gzip(body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void rejectsLegacyJsonJankWithoutPersistingIt() throws Exception {
        String event = "{\"schemaVersion\":2,\"eventId\":\"large-event\",\"eventType\":\"jank\"," 
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"s\",\"processId\":\"11111111-1111-4111-8111-111111111111\",\"anonymousDeviceId\":\"d\","
                + "\"packageName\":\"com.example.app\",\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"prod\",\"channel\":\"official\",\"osVersion\":\"16\",\"deviceModel\":\"Pixel\","
                + "\"jank\":{\"scene\":\"checkout\",\"algorithmVersion\":\"jank-v1\",\"messageDurationNs\":200000000,"
                + "\"thresholdNs\":100000000,\"samplingIntervalNs\":100000000,\"samples\":[{\"offsetNs\":0,\"stackId\":\"stack\"}],"
                + "\"stackDictionary\":{\"stack\":[{\"className\":\"com.example.VeryLongApplicationClassName\",\"methodName\":\"run\",\"applicationFrame\":true}]},"
                + "\"expectedSampleCount\":1,\"parsedSampleCount\":1,\"missingSampleCount\":0}}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"oversized-event\",\"events\":[" + event + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("JANK_ARTIFACT_REQUIRED"));
    }

    private byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }
}
