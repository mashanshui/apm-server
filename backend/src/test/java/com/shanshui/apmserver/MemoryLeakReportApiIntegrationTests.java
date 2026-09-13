package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryLeakReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证内存泄漏报告的文件上传、幂等、查询授权和失败语义。 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryLeakReportApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMemoryLeakReportRepository repository;

    @BeforeEach
    void reset() {
        repository.clear();
        repository.setAvailable(true);
        resetAppCredential("com.example.memoryleak");
    }

    @Test
    void acceptsMultipartFilesWithIdempotentConflictChecks() throws Exception {
        Instant now = Instant.now();
        UUID event = UUID.randomUUID();
        MockMultipartFile metadata = metadataPart(event, now);
        MockMultipartFile report = reportPart("LeakA");

        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .file(metadata).file(report).header("X-App-Key", appKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.attachmentStatus").value("absent"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .file(metadata).file(report).header("X-App-Key", appKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .file(metadata).file(reportPart("LeakB")).header("X-App-Key", appKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"));

        UUID multipartEvent = UUID.randomUUID();
        MockMultipartFile multipartMetadata = metadataPart(multipartEvent, now);
        MockMultipartFile multipartReport = reportPart("LeakB");
        MockMultipartFile hprof = new MockMultipartFile("hprof", "sample.hprof",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .file(multipartMetadata).file(multipartReport).file(hprof).header("X-App-Key", appKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.attachmentStatus").value("stored"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .file(multipartMetadata).file(multipartReport).file(hprof).header("X-App-Key", appKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"))
                .andExpect(jsonPath("$.attachmentStatus").value("stored"));
    }

    @Test
    void servesAuthorizedIssuesAndTrendAndKeepsEmptyStatusDistinct() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = login();
        String base = "/api/v1/apps/" + appId() + "/memory-leaks";
        String from = now.minusSeconds(60).toString();
        String to = now.plusSeconds(60).toString();

        mockMvc.perform(MockMvcRequestBuilders.get(base + "/issues")
                        .param("from", from).param("to", to).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_data"))
                .andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/trend")
                        .param("from", from).param("to", to).param("interval", "5m").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_data"))
                .andExpect(jsonPath("$.points").isArray());

        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey())
                        .file(metadataPart(UUID.randomUUID(), now)).file(reportPart("LeakA")))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/issues")
                        .param("from", from).param("to", to).param("pageSize", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].signature").value("LeakA"));
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/trend")
                        .param("from", from).param("to", to).param("interval", "5m").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.points").isArray());
    }

    @Test
    void enforcesQueryAllowlistAndStorageFailureStatus() throws Exception {
        MockHttpSession session = login();
        String base = "/api/v1/apps/" + appId() + "/memory-leaks";
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/issues").param("unknown", "x").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/trend").param("page", "1").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        mockMvc.perform(MockMvcRequestBuilders.get(base + "/issues"))
                .andExpect(status().isUnauthorized());

        repository.setAvailable(false);
        try {
            mockMvc.perform(MockMvcRequestBuilders.get(base + "/issues").session(session))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("EVENT_STORE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(true));
            mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                            .header("X-App-Key", appKey())
                            .file(metadataPart(UUID.randomUUID(), Instant.now())).file(reportPart("Unavailable")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("EVENT_STORE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(true));
        } finally {
            repository.setAvailable(true);
        }
    }

    @Test
    void rejectsUnknownMetadataFieldsAndMissingReport() throws Exception {
        String invalid = metadata(UUID.randomUUID(), Instant.now())
                .replace("\"processName\":", "\"unexpected\":true,\"processName\":");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey())
                        .file(new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                                invalid.getBytes(StandardCharsets.UTF_8)))
                        .file(reportPart("LeakA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMORY_LEAK_REPORT"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey())
                        .file(metadataPart(UUID.randomUUID(), Instant.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMORY_LEAK_REPORT"));
    }

    @Test
    void rejectsUnsupportedMediaAndOversizedReportBeforePersisting() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey()).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey())
                        .file(metadataPart(UUID.randomUUID(), Instant.now()))
                        .file(new MockMultipartFile("report", "report.json", MediaType.APPLICATION_JSON_VALUE,
                                new byte[2 * 1024 * 1024 + 1])))
                .andExpect(status().isPayloadTooLarge());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/ingest/v1/memory-reports")
                        .header("X-App-Key", appKey())
                        .file(metadataPart(UUID.randomUUID(), Instant.now()))
                        .file(new MockMultipartFile("report", "report.json", MediaType.TEXT_PLAIN_VALUE,
                                report("LeakA").getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType());
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockMultipartFile metadataPart(UUID eventId, Instant occurredAt) {
        return new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                metadata(eventId, occurredAt).getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile reportPart(String signature) {
        return new MockMultipartFile("report", "hprof.json", MediaType.APPLICATION_JSON_VALUE,
                report(signature).getBytes(StandardCharsets.UTF_8));
    }

    private String metadata(UUID eventId, Instant occurredAt) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"occurredAt\":"
                + occurredAt.toEpochMilli() + ",\"packageName\":\"com.example.memoryleak\","
                + "\"appVersion\":\"1.0\",\"versionCode\":1,\"anonymousDeviceId\":\"device-1\","
                + "\"processName\":\"com.example.memoryleak\"}";
    }

    private String report(String signature) {
        return "{\"runningInfo\":{\"buildModel\":\"Pixel\",\"currentPage\":\"Home\","
                + "\"manufacture\":\"Google\",\"sdkInt\":34,\"dumpReason\":\"manual\"},"
                + "\"gcPaths\":[{\"signature\":\"" + signature + "\",\"gcRoot\":\"root\","
                + "\"leakReason\":\"retained\",\"instanceCount\":1,\"path\":[{"
                + "\"reference\":\"com.example.Leak\",\"referenceType\":\"instance\","
                + "\"declaredClass\":\"com.example.Leak\"}]}],\"classInfos\":[],\"leakObjects\":[]}";
    }
}
