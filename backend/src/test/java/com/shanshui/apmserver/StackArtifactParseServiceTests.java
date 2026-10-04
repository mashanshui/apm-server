package com.shanshui.apmserver;

import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.jank.api.JankAnalysis;

import com.bytedance.rheatrace.stack.StackMappingResolver;
import com.bytedance.rheatrace.stack.StackArtifactMetadata;
import com.bytedance.rheatrace.stack.StackParser;
import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.jank.internal.config.StackParserProperties;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.jank.internal.artifact.JankArtifactReportMapper;
import com.shanshui.apmserver.jank.internal.application.JankFingerprintService;
import com.shanshui.apmserver.jank.internal.artifact.AppStackMappingResolver;
import com.shanshui.apmserver.identity.api.PackageNameMismatchException;
import com.shanshui.apmserver.jank.internal.artifact.StackArtifactParseService;
import com.shanshui.apmserver.jank.api.StackParserBusyException;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        InMemoryJankEventRepository repository = repository();
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
        var metrics = new MicrometerTelemetryMetrics(registry);
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

    /** 统一注册表命中时，解析器必须在整个 processor 调用期间持有固定版本租约。 */
    @Test
    void usesRegisteredMappingAndReleasesLeaseAfterProcessor() throws Exception {
        Path mapping = Files.writeString(mappingRoot.resolve("registered.mapping"), "mapping");
        AtomicInteger closed = new AtomicInteger();
        InMemoryJankEventRepository repository = repository();
        StackArtifactParseService service = serviceWithRegistry(
                parserThatResolvesMapping(mapping), repository, registry(mapping, closed, true), 2);

        assertEquals("accepted", service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})).status());
        assertEquals(1, closed.get());
        assertEquals("symbolicated", repository.findJankByEventId(TestAppIds.id("demo-app"), "artifact-event")
                .orElseThrow().symbolicationStatus());
    }

    /** mapping 缺失仍保存未解混淆证据，注册表故障则按可重试错误传播。 */
    @Test
    void distinguishesMissingMappingFromRegistryFailure() throws Exception {
        SymbolRegistry missing = new SymbolRegistry() {
            @Override
            public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
                return Optional.empty();
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines) {
                throw new AssertionError("缺失 mapping 不应执行 Retrace");
            }
        };
        InMemoryJankEventRepository missingRepository = repository();
        StackArtifactParseService missingService = serviceWithRegistry(
                parserThatResolvesMapping(null), missingRepository, missing, 2);
        assertEquals("accepted", missingService.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})).status());
        assertEquals("raw_only", missingRepository.findJankByEventId(TestAppIds.id("demo-app"), "artifact-event")
                .orElseThrow().symbolicationStatus());

        SymbolRegistry unavailable = new SymbolRegistry() {
            @Override
            public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
                throw new SymbolStoreUnavailableException("registry unavailable");
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines) {
                throw new AssertionError("注册表故障时不应执行 Retrace");
            }
        };
        StackArtifactParseService unavailableService = serviceWithRegistry(
                parserThatResolvesMapping(null), repository(), unavailable, 2);
        assertThrows(SymbolStoreUnavailableException.class,
                () -> unavailableService.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})));
    }

    /** 同一事件在 mapping 替换后再次上传仍按既有幂等语义返回 duplicate。 */
    @Test
    void duplicateUploadDoesNotReplaceSavedJankEvidence() throws Exception {
        Path mapping = Files.writeString(mappingRoot.resolve("duplicate.mapping"), "mapping");
        AtomicInteger closed = new AtomicInteger();
        InMemoryJankEventRepository repository = repository();
        StackArtifactParseService service = serviceWithRegistry(
                parserThatResolvesMapping(mapping), repository, registry(mapping, closed, true), 2);

        assertEquals("accepted", service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{1})).status());
        assertEquals("duplicate", service.parse(app("demo-app"), new ByteArrayInputStream(new byte[]{2})).status());
        assertEquals(2, closed.get());
        assertEquals("symbolicated", repository.findJankByEventId(TestAppIds.id("demo-app"), "artifact-event")
                .orElseThrow().symbolicationStatus());
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

    @Test
    void rejectsMissingBlankNumericAndNonV4ZipProcessIdsBeforeProcessor() {
        StackArtifactParseService service = service(parser(report()), repository(), 2);
        for (String processIdJson : new String[]{null, "\"\"", "17100",
                "\"11111111-1111-3111-8111-111111111111\"",
                "\"11111111-1111-4111-7111-111111111111\""}) {
            InvalidStackArtifactException failure = assertThrows(InvalidStackArtifactException.class,
                    () -> service.parse(app("demo-app"),
                            new ByteArrayInputStream(zipManifest("com.example.app", processIdJson))));
            assertEquals("INVALID_JANK_MANIFEST", failure.getCode());
        }
    }

    private byte[] zipManifest(String packageName) throws IOException {
        return zipManifest(packageName, null);
    }

    private byte[] zipManifest(String packageName, String processIdJson) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"schemaVersion\":3,\"artifactType\":\"RHEA_JANK\","
                    + "\"packageName\":\"" + packageName + "\""
                    + (processIdJson == null ? "" : ",\"processId\":" + processIdJson) + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private StackArtifactParseService service(StackParser parser,
                                              InMemoryJankEventRepository repository,
                                              int concurrency) {
        return service(parser, repository, concurrency,
                new MicrometerTelemetryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private StackArtifactParseService service(StackParser parser,
                                              InMemoryJankEventRepository repository,
                                              int concurrency,
                                              MicrometerTelemetryMetrics metrics) {
        StackParserProperties stackProperties = new StackParserProperties();
        stackProperties.setMappingRoot(mappingRoot.toString());
        stackProperties.setMaxConcurrentParses(concurrency);
        IngestConfigurationProperties ingestProperties = CrashTestSupport.ingestProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        JankSanitizer sanitizer = new JankSanitizer(ingestProperties);
        JankArtifactReportMapper mapper = new JankArtifactReportMapper(objectMapper, ingestProperties,
                sanitizer, new JankFingerprintService());
        return new StackArtifactParseService(parser, objectMapper,
                new AppStackMappingResolver(stackProperties), mapper,
                new com.shanshui.apmserver.jank.internal.application.JankWriteCoordinator(repository), stackProperties,
                metrics);
    }

    /** 使用统一注册表构造卡顿解析服务，避免测试回退到旧手工路径。 */
    private StackArtifactParseService serviceWithRegistry(StackParser parser,
                                                          InMemoryJankEventRepository repository,
                                                          SymbolRegistry registry,
                                                          int concurrency) {
        StackParserProperties stackProperties = new StackParserProperties();
        stackProperties.setMaxConcurrentParses(concurrency);
        IngestConfigurationProperties ingestProperties = CrashTestSupport.ingestProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        JankSanitizer sanitizer = new JankSanitizer(ingestProperties);
        JankArtifactReportMapper mapper = new JankArtifactReportMapper(objectMapper, ingestProperties,
                sanitizer, new JankFingerprintService());
        return new StackArtifactParseService(parser, objectMapper,
                new AppStackMappingResolver(registry), mapper,
                new com.shanshui.apmserver.jank.internal.application.JankWriteCoordinator(repository),
                stackProperties, new MicrometerTelemetryMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** 创建会主动向统一 resolver 请求一个 buildId 的 processor 替身。 */
    private StackParser parserThatResolvesMapping(Path expectedMapping) {
        return new StackParser() {
            @Override
            public String parse(InputStream artifactInput, File proguardMapping) {
                throw new AssertionError("生产路径必须使用统一 mapping resolver");
            }

            @Override
            public String parseWithMappingResolver(InputStream artifactInput,
                                                   StackMappingResolver mappingResolver) throws IOException {
                File resolved = mappingResolver.resolve(metadata("build-1"));
                if (expectedMapping != null && !expectedMapping.toFile().equals(resolved)) {
                    throw new AssertionError("processor 未取得注册表 mapping");
                }
                if (expectedMapping == null && resolved != null) {
                    throw new AssertionError("缺失 mapping 不应返回文件");
                }
                return report();
            }
        };
    }

    /** 创建 processor 传给 resolver 的 metadata，覆盖 buildId 选择。 */
    private StackArtifactMetadata metadata(String mappingId) throws IOException {
        try {
            var constructor = StackArtifactMetadata.class.getDeclaredConstructor(
                    int.class, String.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(3, "RHEA_JANK", "demo-app", mappingId);
        } catch (ReflectiveOperationException ex) {
            throw new IOException("无法构造 processor metadata 测试值", ex);
        }
    }

    /** 创建固定版本租约的统一注册表测试替身。 */
    private SymbolRegistry registry(Path mapping, AtomicInteger closed, boolean available) {
        return new SymbolRegistry() {
            @Override
            public Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
                if (!available) {
                    return Optional.empty();
                }
                return Optional.of(new SymbolFileLease() {
                    private boolean released;

                    @Override
                    public UUID symbolId() {
                        return TestAppIds.id("jank-symbol");
                    }

                    @Override
                    public int revision() {
                        return 1;
                    }

                    /** 合成租约摘要。 */
                    @Override
                    public String sha256() { return "a".repeat(64); }

                    @Override
                    public Path path() {
                        return mapping;
                    }

                    @Override
                    public void close() {
                        if (!released) {
                            released = true;
                            closed.incrementAndGet();
                        }
                    }
                });
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines) {
                return SymbolicationResult.failed("unused");
            }
        };
    }

    private InMemoryJankEventRepository repository() {
        return new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
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
                    "occurredAt":%d,"sessionId":"session","processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"device",
                    "packageName":"com.example.app","appVersion":"1.0","versionCode":1,
                    "buildId":"build-1","environment":"test","channel":"official",
                    "osVersion":"16","deviceModel":"Pixel","scene":"checkout",
                    "messageStartNs":1000,"messageEndNs":11000001,"thresholdNs":10000000,
                    "minSampleIntervalNs":10000000,"attemptedSampleCount":99,"processId":"11111111-1111-4111-8111-111111111111","files":{}
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
