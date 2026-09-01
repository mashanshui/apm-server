package com.shanshui.apmserver;

import com.bytedance.rheatrace.stack.StackAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RheaStackAnalyzerIntegrationTests {

    @Test
    void parsesRealBtraceArtifactIntoSegmentsAndCallTree() throws Exception {
        byte[] artifact = Base64.getMimeDecoder().decode(readFixture("/fixtures/rhea-stack-artifact.b64"));

        String reportJson = new StackAnalyzer().parse(new ByteArrayInputStream(artifact));
        JsonNode report = new ObjectMapper().readTree(reportJson);

        assertEquals(1, report.path("schemaVersion").asInt());
        assertEquals("RHEA_STACK_REPORT", report.path("artifactType").asText());
        assertTrue(report.path("recordCount").asInt() > 0);
        assertTrue(report.path("threads").isArray());
        assertFalse(report.path("threads").isEmpty());
        for (JsonNode thread : report.path("threads")) {
            assertTrue(thread.path("segments").isArray());
            assertFalse(thread.path("segments").isEmpty());
            assertTrue(thread.path("callTree").isArray());
            assertFalse(thread.path("callTree").isEmpty());
        }
    }

    @Test
    void rejectsLegacyV2RheaJankArtifactWithProcessorV101() throws Exception {
        byte[] artifact = Base64.getMimeDecoder().decode(readFixture("/fixtures/rhea-jank-artifact.b64"));
        assertThrows(IOException.class, () -> new StackAnalyzer().parseWithMappingResolver(
                new ByteArrayInputStream(artifact), metadata -> null));
    }

    @Test
    void parsesProvidedRheaJankV3ArtifactAndExposesVerifiedManifest() throws Exception {
        byte[] artifact = readProvidedRheaJankV3Artifact();
        AtomicReference<String> mappingId = new AtomicReference<>();

        String reportJson = new StackAnalyzer().parseWithMappingResolver(
                new ByteArrayInputStream(artifact), metadata -> {
                    mappingId.set(metadata.getMappingId());
                    return null;
                });
        JsonNode report = new ObjectMapper().readTree(reportJson);
        JsonNode manifest = report.path("sourceManifest");
        JsonNode mainThread = null;
        for (JsonNode thread : report.path("threads")) {
            if (thread.path("tid").asLong() == manifest.path("processId").asLong()) {
                mainThread = thread;
                break;
            }
        }

        assertEquals(11_189, artifact.length);
        assertEquals("1445628b2fa6d4ed055fdede218f950b1b44d20ffe6ae990f05869aceb6c16fe",
                hex(MessageDigest.getInstance("SHA-256").digest(artifact)));
        assertEquals("app-online-test-build", mappingId.get());
        assertEquals(3, manifest.path("schemaVersion").asInt());
        assertEquals("RHEA_JANK", manifest.path("artifactType").asText());
        assertEquals("demo-jank-2308233515248871", manifest.path("eventId").asText());
        assertEquals("rhea.sample.android", manifest.path("packageName").asText());
        assertFalse(manifest.has("appId"));
        assertEquals(1, manifest.path("attemptedSampleCount").asInt());
        assertTrue(report.path("recordCount").asInt() > 0);
        assertTrue(report.path("pointSampleCount").asInt() > 0);
        assertNotNull(mainThread);
        assertFalse(mainThread.path("segments").isEmpty());
        assertFalse(mainThread.path("callTree").isEmpty());
    }

    @Test
    void usesVerifiedProcessorFatJarAndPublicResolverApi() throws Exception {
        Path jar = Path.of(com.bytedance.rheatrace.stack.StackParser.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isRegularFile(jar));
        assertEquals("e31cc2b2bf9a4015419fa8d0cd07f89978f9000a8cc8bcf3952c1933e7a568f3",
                hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        assertNotNull(com.bytedance.rheatrace.stack.StackParser.class.getMethod(
                "parseWithMappingResolver", InputStream.class,
                com.bytedance.rheatrace.stack.StackMappingResolver.class));
    }

    @Test
    void repositoryFixtureMatchesClientZipEntriesSizesAndHashes() throws Exception {
        byte[] artifact = Base64.getMimeDecoder().decode(readFixture("/fixtures/rhea-jank-artifact.b64"));
        assertEquals(11_154, artifact.length);
        assertEquals("f328cc06a2307304810445092f2906d1ab002c79462670954bb1fcd2d41d6624",
                hex(MessageDigest.getInstance("SHA-256").digest(artifact)));

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertEquals(java.util.Set.of("manifest.json", "sampling.bin", "sampling-mapping.bin"), entries.keySet());
        assertEquals(788, entries.get("manifest.json").length);
        assertEquals(83_727, entries.get("sampling.bin").length);
        assertEquals(1_782, entries.get("sampling-mapping.bin").length);
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals(22, manifest.size());
        assertEquals(83_727, manifest.path("files").path("sampling").path("size").asInt());
        assertEquals("aa47a31e1ac819f4aef18c8262e3c81872533c7c1b2b7ad47c1c8d6bc7692c2d",
                manifest.path("files").path("sampling").path("sha256").asText());
        assertEquals("e75b24d4db7ba78c81cb79853397c3449500ad94a7777e4eb277f4d30c4a6dd0",
                manifest.path("files").path("sampling-mapping").path("sha256").asText());
    }

    private String readFixture(String path) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "缺少真实 btrace 堆栈产物夹具");
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private byte[] readProvidedRheaJankV3Artifact() throws Exception {
        String configuredPath = System.getProperty("rhea.jank.fixture");
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path fixture = Path.of(configuredPath);
            assertTrue(Files.isRegularFile(fixture), "指定的真实卡顿 ZIP 不存在: " + fixture);
            return Files.readAllBytes(fixture);
        }
        return Base64.getMimeDecoder().decode(readFixture("/fixtures/rhea-jank-v3-artifact.b64"));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
