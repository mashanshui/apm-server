package com.shanshui.apmserver;

import com.bytedance.rheatrace.stack.StackParser;
import com.bytedance.rheatrace.stack.StackMappingResolver;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.web.StackArtifactController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "apm.stack-parser.max-artifact-bytes=8",
        "apm.stack-parser.max-concurrent-parses=1"
})
@AutoConfigureMockMvc
@Import(StackArtifactApiIntegrationTests.ParserTestConfiguration.class)
class StackArtifactApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestStackParser parser;

    @Autowired
    private InMemoryEventRepository repository;

    @BeforeEach
    void resetParser() {
        parser.reset();
        repository.clear();
        repository.setAvailable(true);
        resetAppCredential("com.example.app");
    }

    @Test
    void persistsRawArtifactAndReturnsMinimalIdempotentResult() throws Exception {
        mockMvc.perform(request(new byte[]{1, 2, 3}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.appId").doesNotExist())
                .andExpect(jsonPath("$.report").doesNotExist());
        mockMvc.perform(request(new byte[]{1, 2, 3}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    void authenticatesBeforeParsingBody() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/stack-artifacts:parse")
                        .header("X-App-Key", "invalid")
                        .contentType(StackArtifactController.ARTIFACT_MEDIA_TYPE)
                        .content(new byte[]{1}))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_APP_KEY"));
    }

    @Test
    void rejectsEmptyBodyWrongMediaTypeAndOversizedBody() throws Exception {
        mockMvc.perform(request(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STACK_ARTIFACT_REQUEST"));

        mockMvc.perform(MockMvcRequestBuilders.post("/ingest/v1/stack-artifacts:parse")
                        .header("X-App-Key", appKey())
                        .contentType("application/vnd.shanshui.rheatrace+zip")
                        .content(new byte[]{1}))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        mockMvc.perform(request(new byte[9]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void returnsStableArtifactAndVersionErrors() throws Exception {
        mockMvc.perform(request(new byte[]{0}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STACK_ARTIFACT"))
                .andExpect(jsonPath("$.message").value("卡顿产物无法解析"));

        mockMvc.perform(request(new byte[]{9}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_JANK_ARTIFACT"));

        mockMvc.perform(request(new byte[]{7}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_JANK_MANIFEST"));
    }

    @Test
    void rejectsMismatchedManifestBeforeWritingJankFacts() throws Exception {
        resetAppCredential("com.other.app");

        mockMvc.perform(request(new byte[]{1}))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PACKAGE_NAME_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false));

        assertTrue(repository.findJankByEventId(TestAppIds.id("demo-app"), "fake-1").isEmpty());
    }

    @Test
    void returnsRetryableServiceUnavailableWhenStorageFails() throws Exception {
        repository.setAvailable(false);
        mockMvc.perform(request(new byte[]{5}))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("EVENT_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void returnsRetryableServiceUnavailableWhenParserIsBusy() throws Exception {
        parser.block();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> mockMvc.perform(request(new byte[]{1})).andReturn());
            assertTrue(parser.awaitEntered());

            mockMvc.perform(request(new byte[]{2}))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("STACK_PARSER_BUSY"))
                    .andExpect(jsonPath("$.retryable").value(true));

            parser.release();
            MvcResult firstResult = first.get(2, TimeUnit.SECONDS);
            assertTrue(firstResult.getResponse().getStatus() == 200);
        } finally {
            parser.release();
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(byte[] content) {
        return MockMvcRequestBuilders.post("/ingest/v1/stack-artifacts:parse")
                .header("X-App-Key", appKey())
                .contentType(StackArtifactController.ARTIFACT_MEDIA_TYPE)
                .content(content);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ParserTestConfiguration {

        @Bean
        @Primary
        TestStackParser testStackParser() {
            return new TestStackParser();
        }
    }

    static final class TestStackParser implements StackParser {

        private volatile boolean blocking;
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public String parse(InputStream artifactInput, File proguardMapping) throws IOException {
            return parseWithMappingResolver(artifactInput, metadata -> proguardMapping);
        }

        @Override
        public String parseWithMappingResolver(InputStream artifactInput,
                                               StackMappingResolver mappingResolver) throws IOException {
            byte[] content = artifactInput.readAllBytes();
            if (content.length > 0 && content[0] == 0) {
                throw new IOException("test invalid artifact detail");
            }
            if (blocking) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test wait timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test interrupted", ex);
                }
            }
            int marker = content[0] & 0xff;
            int manifestVersion = marker == 9 ? 1 : 3;
            String manifestType = marker == 9 ? "RHEA_STACK" : "RHEA_JANK";
            return report(marker, manifestVersion, manifestType);
        }

        private String report(int marker, int manifestVersion, String manifestType) {
            return "{\"schemaVersion\":1,\"artifactType\":\"RHEA_STACK_REPORT\","
                    + "\"actualStartNs\":1000,\"actualEndNs\":11000001,"
                    + "\"sourceManifest\":{\"schemaVersion\":" + manifestVersion
                    + ",\"artifactType\":\"" + manifestType + "\",\"eventId\":\"fake-" + marker
                    + "\",\"occurredAt\":" + System.currentTimeMillis()
                    + ",\"sessionId\":\"session\",\"anonymousDeviceId\":\"device\","
                    + (marker == 7 ? "" : "\"packageName\":\"com.example.app\",")
                    + "\"appVersion\":\"1.0\",\"versionCode\":1,"
                    + "\"buildId\":\"build-1\",\"environment\":\"test\",\"channel\":\"official\","
                    + "\"osVersion\":\"16\",\"deviceModel\":\"Pixel\",\"scene\":\"checkout\","
                    + "\"messageStartNs\":1000,\"messageEndNs\":11000001,\"thresholdNs\":10000000,"
                    + "\"minSampleIntervalNs\":10000000,\"attemptedSampleCount\":999,\"processId\":42},"
                    + "\"warnings\":[],\"threads\":[{\"tid\":42,\"estimatedCoveredDurationNs\":10000000,"
                    + "\"segments\":[{\"startOffsetNs\":0,\"estimatedEndOffsetNs\":10000000,"
                    + "\"eventType\":\"kCustom\",\"stack\":[{\"method\":\"app.Main.run(Main.java:10)\","
                    + "\"sourceFile\":\"Main.java\",\"lineNumber\":10}]}],"
                    + "\"callTree\":[{\"method\":\"app.Main.run(Main.java:10)\",\"sourceFile\":\"Main.java\","
                    + "\"lineNumber\":10,\"estimatedDurationNs\":10000000,"
                    + "\"estimatedSelfDurationNs\":10000000,\"children\":[]}]}]}";
        }

        void reset() {
            blocking = false;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(0);
        }

        void block() {
            blocking = true;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(2, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }
}
