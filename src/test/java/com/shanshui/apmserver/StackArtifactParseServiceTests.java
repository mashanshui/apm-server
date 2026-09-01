package com.shanshui.apmserver;

import com.bytedance.rheatrace.stack.StackMappingResolver;
import com.bytedance.rheatrace.stack.StackParser;
import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.config.StackParserProperties;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.service.CrashSanitizer;
import com.shanshui.apmserver.service.CrashQualityMetrics;
import com.shanshui.apmserver.service.InvalidStackArtifactException;
import com.shanshui.apmserver.service.JankArtifactReportMapper;
import com.shanshui.apmserver.service.JankFingerprintService;
import com.shanshui.apmserver.service.AppStackMappingResolver;
import com.shanshui.apmserver.service.PackageNameMismatchException;
import com.shanshui.apmserver.service.StackArtifactParseService;
import com.shanshui.apmserver.service.StackParserBusyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackArtifactParseServiceTests {

    @TempDir
    Path mappingRoot;

    @Test
    void returnsAcceptedThenDuplicateAndStoresDerivedQuality() {
        InMemoryEventRepository repository = repository();
        StackArtifactParseService service = service(parser(report()), repository, 2);

        var accepted = service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1}));
        var duplicate = service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1}));

        assertTrue(accepted.success());
        assertEquals("accepted", accepted.status());
        assertEquals("duplicate", duplicate.status());
        var stored = repository.findJankByEventId(TestAppIds.id("demo-app"), "artifact-event").orElseThrow();
        assertEquals(2, stored.jankAnalysis().expectedSampleCount());
        assertEquals(1, stored.jankAnalysis().parsedSampleCount());
        assertEquals(1, stored.jankAnalysis().missingSampleCount());
    }

    @Test
    void rejectsInvalidReportAndParserIOExceptionWithStableCode() {
        StackArtifactParseService invalidReport = service(parser("{}"), repository(), 2);
        InvalidStackArtifactException invalid = assertThrows(InvalidStackArtifactException.class,
                () -> invalidReport.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})));
        assertEquals("INVALID_STACK_REPORT", invalid.getCode());

        StackArtifactParseService parserFailure = service(parserFailure(), repository(), 2);
        InvalidStackArtifactException failure = assertThrows(InvalidStackArtifactException.class,
                () -> parserFailure.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})));
        assertEquals("INVALID_STACK_ARTIFACT", failure.getCode());
        assertEquals("卡顿产物无法解析", failure.getMessage());
    }

    @Test
    void rejectsConcurrentParseAndReleasesPermitAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StackParser parser = blockingParser(report(), entered, release);
        StackArtifactParseService service = service(parser, repository(), 1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.parse(app("demo-app"),
                    new ByteArrayInputStream(new byte[]{1})));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertThrows(StackParserBusyException.class,
                    () -> service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{2})));

            release.countDown();
            assertEquals("accepted", first.get(2, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recordsArtifactAcceptedDuplicateAndVersionMetrics() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metrics = new CrashQualityMetrics(registry);
        var service = service(parser(report()), repository(), 1, metrics);

        assertEquals("accepted", service.parse(app("app-a"), new ByteArrayInputStream(new byte[]{1})).status());
        assertEquals("duplicate", service.parse(app("app-a"), new ByteArrayInputStream(new byte[]{1})).status());

        assertEquals(2.0, registry.counter("apm_jank_events_received_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_accepted_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_duplicate_total").count());
        assertEquals(2.0, registry.counter("apm_jank_schema_version_total", "version", "3").count());
        assertEquals(2.0, registry.counter("apm_jank_algorithm_version_total", "event_type", "jank",
                "version", "jank-artifact-v2").count());
    }

    @Test
    void rejectsZipPackageMismatchBeforeProcessorAndMapping() throws Exception {
        AtomicBoolean parserCalled = new AtomicBoolean();
        StackParser parser = new StackParser() {
            @Override
            public String parse(InputStream artifactInput, File proguardMapping) {
                parserCalled.set(true);
                return report();
            }

            @Override
            public String parseWithMappingResolver(InputStream artifactInput,
                                                   StackMappingResolver mappingResolver) {
                parserCalled.set(true);
                return report();
            }
        };
        StackArtifactParseService service = service(parser, repository(), 2);

        assertThrows(PackageNameMismatchException.class,
                () -> service.parse(app("demo-app"),
                        new ByteArrayInputStream(zipManifest("com.other.app"))));
        assertTrue(!parserCalled.get(), "包名不匹配时不得调用 processor");
    }

    private byte[] zipManifest(String packageName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"schemaVersion\":3,\"artifactType\":\"RHEA_JANK\","
                    + "\"packageName\":\"" + packageName + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private StackArtifactParseService service(StackParser parser,
                                              InMemoryEventRepository repository,
                                              int concurrency) {
        return service(parser, repository, concurrency,
                new CrashQualityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private StackArtifactParseService service(StackParser parser,
                                              InMemoryEventRepository repository,
                                              int concurrency,
                                              CrashQualityMetrics metrics) {
        StackParserProperties stackProperties = new StackParserProperties();
        stackProperties.setMappingRoot(mappingRoot.toString());
        stackProperties.setMaxConcurrentParses(concurrency);
        IngestProperties ingestProperties = CrashTestSupport.ingestProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        CrashSanitizer sanitizer = new CrashSanitizer(ingestProperties);
        JankArtifactReportMapper mapper = new JankArtifactReportMapper(objectMapper, ingestProperties,
                sanitizer, new JankFingerprintService());
        return new StackArtifactParseService(parser, objectMapper,
                new AppStackMappingResolver(stackProperties), mapper, repository, stackProperties,
                metrics);
    }

    private InMemoryEventRepository repository() {
        return new InMemoryEventRepository(CrashTestSupport.storageProperties());
    }

    private AuthenticatedApp app(String appId) {
        return new AuthenticatedApp(TestAppIds.id(appId), "com.example.app");
    }

    private StackParser parser(String report) {
        return new StackParser() {
            @Override
            public String parse(InputStream artifactInput, File proguardMapping) {
                return report;
            }

            @Override
            public String parseWithMappingResolver(InputStream artifactInput,
                                                   StackMappingResolver mappingResolver) {
                return report;
            }
        };
    }

    private StackParser parserFailure() {
        return new StackParser() {
            @Override
            public String parse(InputStream artifactInput, File proguardMapping) throws IOException {
                throw new IOException("unstable parser detail");
            }

            @Override
            public String parseWithMappingResolver(InputStream artifactInput,
                                                   StackMappingResolver mappingResolver) throws IOException {
                throw new IOException("unstable parser detail");
            }
        };
    }

    private StackParser blockingParser(String report, CountDownLatch entered, CountDownLatch release) {
        return new StackParser() {
            @Override
            public String parse(InputStream artifactInput, File proguardMapping) {
                return report;
            }

            @Override
            public String parseWithMappingResolver(InputStream artifactInput,
                                                   StackMappingResolver mappingResolver) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ex);
                }
                return report;
            }
        };
    }

    private String report() {
        long occurredAt = Instant.now().toEpochMilli();
        return """
                {
                  "schemaVersion":1,
                  "artifactType":"RHEA_STACK_REPORT",
                  "actualStartNs":1000,
                  "actualEndNs":11000001,
                  "sourceManifest":{
                    "schemaVersion":3,"artifactType":"RHEA_JANK","eventId":"artifact-event",
                    "occurredAt":%d,"sessionId":"session","anonymousDeviceId":"device",
                    "packageName":"com.example.app","appVersion":"1.0","versionCode":1,
                    "buildId":"build-1","environment":"test","channel":"official",
                    "osVersion":"16","deviceModel":"Pixel","scene":"checkout",
                    "messageStartNs":1000,"messageEndNs":11000001,"thresholdNs":10000000,
                    "minSampleIntervalNs":10000000,"attemptedSampleCount":99,"processId":42,"files":{}
                  },
                  "warnings":["point samples"],
                  "threads":[{
                    "tid":42,"threadName":"main","estimatedCoveredDurationNs":10000000,
                    "segments":[{
                      "startOffsetNs":0,"estimatedEndOffsetNs":10000000,"eventType":"kCustom",
                      "stack":[{"method":"com.example.app.Main.run(Main.java:10)",
                        "displayName":"com.example.app.Main.run(Main.java:10)",
                        "sourceFile":"Main.java","lineNumber":10,"nativeMethod":false}]
                    }],
                    "callTree":[{"method":"com.example.app.Main.run(Main.java:10)",
                      "displayName":"com.example.app.Main.run(Main.java:10)",
                      "sourceFile":"Main.java","lineNumber":10,"nativeMethod":false,
                      "estimatedDurationNs":10000000,"estimatedSelfDurationNs":10000000,"children":[]}]
                  }]
                }
                """.formatted(occurredAt);
    }
}
