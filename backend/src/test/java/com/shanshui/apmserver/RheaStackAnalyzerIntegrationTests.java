package com.shanshui.apmserver;

import com.bytedance.rheatrace.stack.StackAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RheaStackAnalyzerIntegrationTests {

    /** 测试使用的标准 UUID v4 进程身份。 */
    private static final String TEST_PROCESS_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    void parsesRealBtraceArtifactIntoSegmentsAndCallTree() throws Exception {
        byte[] artifact = readOnlineStackArtifact();

        String reportJson = new StackAnalyzer().parse(new ByteArrayInputStream(artifact));
        JsonNode report = new ObjectMapper().readTree(reportJson);

        assertEquals(1, report.path("schemaVersion").asInt());
        assertEquals("RHEA_STACK_REPORT", report.path("artifactType").asText());
        assertEquals(TEST_PROCESS_ID, report.path("processId").asText());
        assertTrue(report.path("recordCount").asInt() > 0);
        assertTrue(report.path("threads").isArray());
        assertEquals(1, report.path("threads").size());
        assertEquals("main", report.path("threads").get(0).path("threadName").asText());
        assertFalse(report.path("threads").isEmpty());
        for (JsonNode thread : report.path("threads")) {
            assertTrue(thread.path("segments").isArray());
            assertFalse(thread.path("segments").isEmpty());
            assertTrue(thread.path("callTree").isArray());
            assertFalse(thread.path("callTree").isEmpty());
        }
    }

    @Test
    void rejectsLegacyV2RheaJankArtifactWithProcessorV102() throws Exception {
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
        assertTrue(manifest.path("processId").isTextual());
        assertEquals(TEST_PROCESS_ID, manifest.path("processId").asText());
        assertEquals("main", manifest.path("threadScope").asText());
        JsonNode mainThread = null;
        for (JsonNode thread : report.path("threads")) {
            if ("main".equals(thread.path("threadName").asText())) {
                mainThread = thread;
                break;
            }
        }

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

    /** 固定经源码、发布元数据及解析回归验证的 1.0.2 制品，后续替换须重新验证。 */
    @Test
    void usesVerifiedProcessorFatJarAndPublicResolverApi() throws Exception {
        // 检查实际加载位置，避免只校验 Maven Local 中未被运行时使用的文件。
        Path jar = Path.of(com.bytedance.rheatrace.stack.StackParser.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isRegularFile(jar));
        assertEquals("0c1ac6952ab92526bec24355523048b5e10b14a6ec8a85c9c03070cc67ddb64f",
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

    /** 将仓库中的历史 v1 样本转换为当前线上 UUID 主线程协议样本。 */
    private byte[] readOnlineStackArtifact() throws Exception {
        byte[] legacyArtifact = Base64.getMimeDecoder().decode(
                readFixture("/fixtures/rhea-stack-artifact.b64"));
        return rewriteOnlineMainThreadArtifact(legacyArtifact, TEST_PROCESS_ID);
    }

    /** 读取外部真实 v3 样本；未配置时使用仓库样本生成协议一致的测试输入。 */
    private byte[] readProvidedRheaJankV3Artifact() throws Exception {
        String configuredPath = System.getProperty("rhea.jank.fixture");
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path fixture = Path.of(configuredPath);
            assertTrue(Files.isRegularFile(fixture), "指定的真实卡顿 ZIP 不存在: " + fixture);
            return Files.readAllBytes(fixture);
        }
        byte[] legacyArtifact = Base64.getMimeDecoder().decode(
                readFixture("/fixtures/rhea-jank-v3-artifact.b64"));
        return rewriteOnlineMainThreadArtifact(legacyArtifact, TEST_PROCESS_ID);
    }

    /** 将历史数字 PID ZIP 改写为 UUID、主线程范围且只保留主线程记录。 */
    private byte[] rewriteOnlineMainThreadArtifact(byte[] artifact, String processId)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ObjectNode manifest = null;
        byte[] sampling = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                byte[] content = input.readAllBytes();
                entries.put(entry.getName(), content);
                if ("manifest.json".equals(entry.getName())) {
                    manifest = (ObjectNode) objectMapper.readTree(content);
                } else if ("sampling.bin".equals(entry.getName())) {
                    sampling = content;
                }
            }
        }
        if (manifest == null || sampling == null) {
            throw new IOException("历史测试 ZIP 缺少 manifest.json 或 sampling.bin");
        }
        int legacyMainTid = manifest.path("processId").asInt(-1);
        if (legacyMainTid <= 0) {
            throw new IOException("历史测试 manifest 缺少数字主线程 PID");
        }

        SamplingRewrite samplingRewrite = rewriteSamplingToMainThread(
                sampling, legacyMainTid, processId, objectMapper);
        manifest.put("processId", processId);
        manifest.put("threadScope", "main");
        if (manifest.path("schemaVersion").asInt(-1) == 1) {
            manifest.put("recordCount", samplingRewrite.recordCount());
        }
        JsonNode filesNode = manifest.get("files");
        JsonNode samplingFileNode = filesNode == null ? null : filesNode.get("sampling");
        if (!(samplingFileNode instanceof ObjectNode samplingFile)) {
            throw new IOException("历史测试 manifest 缺少 files.sampling");
        }
        samplingFile.put("size", samplingRewrite.bytes().length);
        samplingFile.put("sha256", hex(MessageDigest.getInstance("SHA-256")
                .digest(samplingRewrite.bytes())));

        entries.put("sampling.bin", samplingRewrite.bytes());
        entries.put("manifest.json", objectMapper.writeValueAsBytes(manifest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** 将 sampling 二进制中的记录和 extra 改写为当前单主线程线上协议。 */
    private SamplingRewrite rewriteSamplingToMainThread(byte[] sampling, int mainTid,
                                                        String processId, ObjectMapper objectMapper)
            throws Exception {
        final int headerLength = 28;
        final int extraLengthOffset = 24;
        final int recordCountOffset = 20;
        final int typeTraceArg = 15;
        ByteBuffer source = ByteBuffer.wrap(sampling).order(ByteOrder.LITTLE_ENDIAN);
        if (sampling.length < headerLength) {
            throw new IOException("sampling 测试样本头部不完整");
        }
        int version = source.getInt(8);
        int recordCount = source.getInt(recordCountOffset);
        int extraLength = source.getInt(extraLengthOffset);
        int recordsOffset = headerLength + extraLength;
        if (recordCount < 0 || extraLength < 0 || recordsOffset > sampling.length) {
            throw new IOException("sampling 测试样本头部无效");
        }

        ObjectNode extra = (ObjectNode) objectMapper.readTree(
                new String(sampling, headerLength, extraLength, StandardCharsets.UTF_8));
        extra.put("processId", processId);
        extra.put("threadScope", "main");
        byte[] rewrittenExtra = objectMapper.writeValueAsBytes(extra);

        ByteBuffer records = ByteBuffer.wrap(sampling).order(ByteOrder.LITTLE_ENDIAN);
        records.position(recordsOffset);
        ByteArrayOutputStream selectedRecords = new ByteArrayOutputStream();
        int selectedCount = 0;
        for (int index = 0; index < recordCount; index++) {
            int recordStart = records.position();
            requireSamplingBytes(records, 40, index, "固定字段");
            int type = records.getShort() & 0xffff;
            int tid = records.getShort();
            records.getInt();
            skipSamplingBytes(records, 32, index, "时间字段");
            if (type == typeTraceArg) {
                skipSamplingBytes(records, 8, index, "TraceArg 字段");
            }
            if (version >= 4) {
                skipSamplingBytes(records, 16, index, "分配字段");
            }
            if (version >= 5) {
                skipSamplingBytes(records, 12, index, "rusage 字段");
            }
            requireSamplingBytes(records, 8, index, "栈深度");
            int savedDepth = records.getInt();
            int actualDepth = records.getInt();
            if (savedDepth < 0 || actualDepth < 0 || savedDepth > 128) {
                throw new IOException("sampling 测试样本栈深度无效: " + index);
            }
            skipSamplingBytes(records, savedDepth * 8, index, "栈帧");
            int recordEnd = records.position();
            if (tid == mainTid) {
                selectedRecords.write(sampling, recordStart, recordEnd - recordStart);
                selectedCount++;
            }
        }
        if (records.hasRemaining() || selectedCount == 0) {
            throw new IOException("sampling 测试样本未得到唯一主线程记录");
        }

        byte[] rewrittenHeader = Arrays.copyOf(sampling, headerLength);
        ByteBuffer header = ByteBuffer.wrap(rewrittenHeader).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(recordCountOffset, selectedCount);
        header.putInt(extraLengthOffset, rewrittenExtra.length);
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        rewritten.write(rewrittenHeader);
        rewritten.write(rewrittenExtra);
        rewritten.write(selectedRecords.toByteArray());
        return new SamplingRewrite(rewritten.toByteArray(), selectedCount);
    }

    /** 校验 sampling 读取范围，避免测试样本解析越界。 */
    private void requireSamplingBytes(ByteBuffer buffer, int bytes, int index, String field)
            throws IOException {
        if (bytes < 0 || buffer.remaining() < bytes) {
            throw new IOException("sampling 第 " + index + " 条记录的" + field + "不完整");
        }
    }

    /** 移动 sampling 读取位置，统一执行边界校验。 */
    private void skipSamplingBytes(ByteBuffer buffer, int bytes, int index, String field)
            throws IOException {
        requireSamplingBytes(buffer, bytes, index, field);
        buffer.position(buffer.position() + bytes);
    }

    /** sampling 改写结果，包含新二进制和 Manifest 所需的记录数量。 */
    private record SamplingRewrite(byte[] bytes, int recordCount) {
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
