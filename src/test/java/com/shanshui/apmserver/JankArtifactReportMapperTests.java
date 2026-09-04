package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.api.JankAnalysis;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.jank.internal.artifact.JankArtifactReportMapper;
import com.shanshui.apmserver.jank.internal.application.JankFingerprintService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankArtifactReportMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void countsAllTargetThreadSegmentsAndDeduplicatesStacks() throws Exception {
        JsonNode report = report(1_000L, 20_000_000L);
        JankEvent event = mapper(CrashTestSupport.ingestProperties()).map(
                TestAppIds.id("app-a"), report, Instant.parse("2026-08-30T00:00:00Z"), false);

        assertEquals(2, event.jank().expectedSampleCount());
        assertEquals(2, event.jank().parsedSampleCount());
        assertEquals(0, event.jank().missingSampleCount());
        assertEquals(1, event.jank().stackDictionary().size());
        assertEquals(2, event.jank().samples().size());
        assertEquals("jank-artifact-v2", event.jankAnalysis().algorithmVersion());
        assertEquals(java.util.List.of("processor warning"), event.jankAnalysis().warnings());
        assertFalse(event.anonymousDeviceId().equals("device-raw"));
        assertTrue(event.crashFingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void parsedMayExceedExpectedWithoutBeingTruncated() throws Exception {
        JsonNode report = report(1_000L, 10_000_000L);
        ArrayNode segments = (ArrayNode) report.path("threads").get(0).path("segments");
        ((ObjectNode) segments.get(0)).put("estimatedEndOffsetNs", 5_000_000L);
        ((ObjectNode) segments.get(1)).put("startOffsetNs", 5_000_000L);
        ((ObjectNode) segments.get(1)).put("estimatedEndOffsetNs", 10_000_000L);
        ((ObjectNode) report.path("threads").get(0)).put("estimatedCoveredDurationNs", 10_000_000L);
        ObjectNode callTree = (ObjectNode) report.path("threads").get(0).path("callTree").get(0);
        callTree.put("estimatedDurationNs", 10_000_000L);
        callTree.put("estimatedSelfDurationNs", 10_000_000L);

        JankEvent event = mapper(CrashTestSupport.ingestProperties()).map(
                TestAppIds.id("app-a"), report, Instant.now(), false);
        assertEquals(1, event.jank().expectedSampleCount());
        assertEquals(2, event.jank().parsedSampleCount());
        assertEquals(0, event.jank().missingSampleCount());
    }

    @Test
    void rejectsMissingDuplicateMainThreadAndEmptyStack() throws Exception {
        JsonNode missing = report(1_000L, 20_000_000L);
        ((ObjectNode) missing.path("threads").get(0)).put("tid", 7);
        assertCode("INVALID_JANK_EVIDENCE", missing);

        JsonNode duplicate = report(1_000L, 20_000_000L);
        ArrayNode threads = (ArrayNode) duplicate.path("threads");
        threads.add(threads.get(0).deepCopy());
        assertCode("INVALID_JANK_EVIDENCE", duplicate);

        JsonNode emptyStack = report(1_000L, 20_000_000L);
        ((ArrayNode) emptyStack.path("threads").get(0).path("segments").get(0).path("stack")).removeAll();
        assertCode("INVALID_JANK_EVIDENCE", emptyStack);
    }

    @Test
    void preservesNanosecondIntegersBeyondDoublePrecision() throws Exception {
        long start = 9_007_199_254_740_992L;
        JankEvent event = mapper(CrashTestSupport.ingestProperties()).map(
                TestAppIds.id("app-a"), report(start, 20_000_000L), Instant.now(), true);

        assertEquals(20_000_000L, event.jank().messageDurationNs());
        assertEquals("symbolicated", event.symbolicationStatus());
    }

    @Test
    void rejectsEvidenceLimitsInsteadOfSilentlyTruncating() throws Exception {
        IngestConfigurationProperties sampleLimit = CrashTestSupport.ingestProperties();
        sampleLimit.setMaxJankSamples(1);
        InvalidStackArtifactException samples = assertThrows(InvalidStackArtifactException.class,
                () -> mapper(sampleLimit).map(TestAppIds.id("app-a"), report(1_000L, 20_000_000L), Instant.now(), false));
        assertEquals("JANK_EVIDENCE_LIMIT_EXCEEDED", samples.getCode());

        IngestConfigurationProperties detailLimit = CrashTestSupport.ingestProperties();
        detailLimit.setMaxJankDetailBytes(32);
        InvalidStackArtifactException detail = assertThrows(InvalidStackArtifactException.class,
                () -> mapper(detailLimit).map(TestAppIds.id("app-a"), report(1_000L, 20_000_000L), Instant.now(), false));
        assertEquals("JANK_EVIDENCE_LIMIT_EXCEEDED", detail.getCode());
    }

    @Test
    void oldV2FixtureIsRejectedUntilARealV3FixtureIsAvailable() throws Exception {
        byte[] artifact;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/rhea-jank-artifact.b64")) {
            artifact = Base64.getMimeDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII));
        }
        JsonNode legacyManifest = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    legacyManifest = objectMapper.readTree(zip);
                    break;
                }
            }
        }
        if (legacyManifest == null) {
            throw new AssertionError("旧 v2 fixture 缺少 manifest.json");
        }
        var report = objectMapper.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("artifactType", "RHEA_STACK_REPORT");
        report.set("sourceManifest", legacyManifest);

        InvalidStackArtifactException exception = assertThrows(InvalidStackArtifactException.class,
                () -> mapper(CrashTestSupport.ingestProperties()).map(
                        TestAppIds.id("demo-app"), report,
                        Instant.parse("2026-08-29T12:30:00Z"), false));
        assertEquals("INVALID_JANK_MANIFEST", exception.getCode());
    }

    private void assertCode(String expected, JsonNode report) {
        InvalidStackArtifactException exception = assertThrows(InvalidStackArtifactException.class,
                () -> mapper(CrashTestSupport.ingestProperties()).map(TestAppIds.id("app-a"), report, Instant.now(), false));
        assertEquals(expected, exception.getCode());
    }

    private JankArtifactReportMapper mapper(IngestConfigurationProperties properties) {
        return new JankArtifactReportMapper(objectMapper, properties, new JankSanitizer(properties),
                new JankFingerprintService());
    }

    private JsonNode report(long start, long duration) throws Exception {
        long end = Math.addExact(start, duration);
        return objectMapper.readTree("""
                {
                  "schemaVersion":1,"artifactType":"RHEA_STACK_REPORT","recordCount":999,
                  "actualStartNs":%d,"actualEndNs":%d,
                  "sourceManifest":{
                    "schemaVersion":3,"artifactType":"RHEA_JANK","eventId":"event-1",
                    "occurredAt":1788006588468,"sessionId":"session","anonymousDeviceId":"device-raw",
                    "packageName":"app","appVersion":"1.0","versionCode":1,"buildId":"build-1",
                    "environment":"test","channel":"official","osVersion":"16","deviceModel":"Pixel",
                    "scene":"checkout","messageStartNs":%d,"messageEndNs":%d,"thresholdNs":10000000,
                    "minSampleIntervalNs":10000000,"attemptedSampleCount":1,"processId":42
                  },
                  "warnings":["processor warning"],
                  "threads":[
                    {"tid":42,"estimatedCoveredDurationNs":20000000,
                     "segments":[
                       {"startOffsetNs":0,"estimatedEndOffsetNs":10000000,"eventType":"kObjectAllocation",
                        "stack":[{"method":"app.Main.run(Main.java:10)","sourceFile":"Main.java","lineNumber":10}]},
                       {"startOffsetNs":10000000,"estimatedEndOffsetNs":20000000,"eventType":"kCustom",
                        "stack":[{"method":"app.Main.run(Main.java:10)","sourceFile":"Main.java","lineNumber":10}]}
                     ],
                     "callTree":[{"method":"app.Main.run(Main.java:10)","sourceFile":"Main.java","lineNumber":10,
                       "estimatedDurationNs":20000000,"estimatedSelfDurationNs":20000000,"children":[]}]},
                    {"tid":77,"estimatedCoveredDurationNs":10000000,
                     "segments":[{"startOffsetNs":0,"estimatedEndOffsetNs":10000000,"eventType":"kCustom",
                       "stack":[{"method":"worker.Task.run(Task.java:1)","sourceFile":"Task.java","lineNumber":1}]}],
                     "callTree":[]}
                  ]
                }
                """.formatted(start, end, start, end));
    }
}
