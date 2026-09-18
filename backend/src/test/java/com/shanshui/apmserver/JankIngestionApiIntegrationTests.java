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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JankIngestionApiIntegrationTests extends AppIngestApiTestSupport {

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
    void gzipBatchRejectsJsonJankAndMismatchedMetricPayload() throws Exception {
        String valid = eventJson("jank-api-1", "stack");
        String invalid = eventJson("jank-api-invalid", "missing").replace("\"eventType\":\"jank\"", "\"eventType\":\"frame_scene_summary\"");
        String body = "{\"requestId\":\"jank-api\",\"events\":[" + valid + "," + invalid + "]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .header("X-Schema-Version", "2")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gzip(body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.errors[0].code").value("JANK_ARTIFACT_REQUIRED"))
                .andExpect(jsonPath("$.errors[1].code").value("INVALID_FRAME_PAYLOAD"));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"jank-api-retry\",\"events\":[" + valid + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("JANK_ARTIFACT_REQUIRED"));
    }

    @Test
    void rejectsUnknownJsonFieldBeforeBinding() throws Exception {
        String body = "{\"requestId\":\"unknown\",\"events\":[" + eventJson("jank-unknown", "stack").replace(
                "\"eventType\":\"jank\"", "\"eventType\":\"jank\",\"unexpected\":true") + "]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BATCH"));
    }

    @Test
    void rejectsInvalidAppKeyBeforeReadingPayload() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", "not-a-valid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"ignored\",\"events\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_APP_KEY"));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-Project-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_APP_KEY"));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey().replace("apm_ak_", "apm_pk_"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_APP_KEY"));
    }

    @Test
    void rejectsJsonV1AndLegacyAppIdWithoutWriting() throws Exception {
        String legacyBody = "{\"requestId\":\"legacy-json\",\"events\":[{"
                + "\"schemaVersion\":1,\"eventId\":\"legacy-event\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"session\",\"processId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"anonymousDeviceId\":\"device\",\"packageName\":\"com.example.app\","
                + "\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"test\",\"channel\":\"official\",\"osVersion\":\"16\","
                + "\"deviceModel\":\"Pixel\",\"networkType\":\"wifi\"}]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED_SCHEMA_VERSION"));

        String legacyAppIdBody = legacyBody.replace("\"packageName\":\"com.example.app\"",
                "\"appId\":\"legacy-package\",\"packageName\":\"com.example.app\"");
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyAppIdBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BATCH"));
    }

    private String eventJson(String eventId, String sampleStackId) {
        return "{\"schemaVersion\":2,\"eventId\":\"" + eventId + "\",\"eventType\":\"jank\"," 
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"session-api\",\"processId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"anonymousDeviceId\":\"device-api\",\"packageName\":\"com.example.app\",\"appVersion\":\"1.0\","
                + "\"versionCode\":1,\"buildId\":\"build\",\"environment\":\"prod\",\"channel\":\"official\","
                + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel\",\"jank\":{"
                + "\"scene\":\"api\",\"algorithmVersion\":\"jank-v1\",\"messageDurationNs\":200000000,"
                + "\"thresholdNs\":100000000,\"samplingIntervalNs\":100000000,"
                + "\"samples\":[{\"offsetNs\":0,\"stackId\":\"" + sampleStackId + "\"}],"
                + "\"stackDictionary\":{\"stack\":[{\"className\":\"com.example.Api\",\"methodName\":\"run\",\"applicationFrame\":true}]},"
                + "\"expectedSampleCount\":1,\"parsedSampleCount\":1,\"missingSampleCount\":0}}";
    }

    private byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }
}
