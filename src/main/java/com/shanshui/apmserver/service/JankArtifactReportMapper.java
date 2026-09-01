package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.JankCallTreeNode;
import com.shanshui.apmserver.domain.JankPayload;
import com.shanshui.apmserver.domain.JankSample;
import com.shanshui.apmserver.domain.JankSampleSlice;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 processor 的 RHEA_STACK_REPORT 归一化为现有卡顿事实与详情模型。 */
@Service
public class JankArtifactReportMapper {

    public static final String ALGORITHM_VERSION = "jank-artifact-v2";
    private static final int REPORT_SCHEMA_VERSION = 1;
    private static final int MANIFEST_SCHEMA_VERSION = 3;

    private final ObjectMapper objectMapper;
    private final IngestProperties properties;
    private final CrashSanitizer sanitizer;
    private final JankFingerprintService fingerprintService;

    public JankArtifactReportMapper(ObjectMapper objectMapper,
                                    IngestProperties properties,
                                    CrashSanitizer sanitizer,
                                    JankFingerprintService fingerprintService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.fingerprintService = fingerprintService;
    }

    public StoredEvent map(java.util.UUID appId, JsonNode report, Instant receivedAt, boolean mappingApplied) {
        requireObject(report, "INVALID_STACK_REPORT", "解析结果不是受支持的堆栈报告");
        if (!report.path("schemaVersion").isIntegralNumber()
                || report.path("schemaVersion").asInt() != REPORT_SCHEMA_VERSION
                || !report.path("artifactType").isTextual()
                || !"RHEA_STACK_REPORT".equals(report.path("artifactType").asText())) {
            throw invalid("INVALID_STACK_REPORT", "解析结果不是受支持的堆栈报告");
        }
        JsonNode manifest = report.get("sourceManifest");
        requireObject(manifest, "INVALID_JANK_MANIFEST", "解析结果缺少卡顿 manifest");
        rejectLegacyManifestIdentity(manifest);
        if (requireInt(manifest, "schemaVersion") != MANIFEST_SCHEMA_VERSION
                || !"RHEA_JANK".equals(requireText(manifest, "artifactType"))) {
            throw invalid("UNSUPPORTED_JANK_ARTIFACT", "只支持 v3 RHEA_JANK 产物");
        }

        String eventId = requireText(manifest, "eventId");
        String packageName = requireText(manifest, "packageName");
        long occurredAt = requireLong(manifest, "occurredAt");
        long start = requireLong(manifest, "messageStartNs");
        long end = requireLong(manifest, "messageEndNs");
        if (start < 0 || end <= start) {
            throw invalid("INVALID_JANK_TIME_RANGE", "卡顿消息时间范围无效");
        }
        long duration = end - start;
        long threshold = requireLong(manifest, "thresholdNs");
        long interval = requireLong(manifest, "minSampleIntervalNs");
        long attempted = requireLong(manifest, "attemptedSampleCount");
        long processId = requireLong(manifest, "processId");
        if (threshold <= 0 || threshold > duration || interval <= 0 || attempted < 0 || processId <= 0) {
            throw invalid("INVALID_JANK_TIME_RANGE", "卡顿消息或采样参数无效");
        }
        if (duration > properties.getMaxJankMessageDurationNs()
                || threshold > properties.getMaxJankThresholdNs()
                || interval > properties.getMaxSamplingIntervalNs()) {
            throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "卡顿消息或采样参数超过服务端限制");
        }
        if (requireLong(report, "actualStartNs") != start || requireLong(report, "actualEndNs") != end) {
            throw invalid("INVALID_JANK_TIME_RANGE", "解析窗口与卡顿消息范围不一致");
        }

        JsonNode mainThread = findMainThread(report.get("threads"), processId);
        JsonNode segmentsNode = mainThread.get("segments");
        if (segmentsNode == null || !segmentsNode.isArray() || segmentsNode.isEmpty()) {
            throw invalid("INVALID_JANK_EVIDENCE", "目标主线程没有有效采样证据");
        }
        if (segmentsNode.size() > properties.getMaxJankSamples()) {
            throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "主线程采样数量超过服务端限制");
        }

        Map<String, List<StackFrame>> dictionary = new LinkedHashMap<>();
        List<JankSample> samples = new ArrayList<>();
        List<NormalizedSegment> normalizedSegments = new ArrayList<>();
        int totalUniqueFrames = 0;
        for (JsonNode segment : segmentsNode) {
            requireObject(segment, "INVALID_JANK_EVIDENCE", "主线程 segment 格式无效");
            long offset = requireLong(segment, "startOffsetNs");
            long estimatedEnd = requireLong(segment, "estimatedEndOffsetNs");
            if (offset < 0 || offset > duration || estimatedEnd < offset || estimatedEnd > duration) {
                throw invalid("INVALID_JANK_EVIDENCE", "主线程采样偏移不在消息区间内");
            }
            JsonNode stackNode = segment.get("stack");
            if (stackNode == null || !stackNode.isArray() || stackNode.isEmpty()) {
                throw invalid("INVALID_JANK_EVIDENCE", "主线程采样堆栈不能为空");
            }
            if (stackNode.size() > properties.getMaxJankStackDepth()) {
                throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "主线程采样堆栈深度超过服务端限制");
            }
            List<StackFrame> frames = new ArrayList<>();
            for (JsonNode frameNode : stackNode) {
                frames.add(toFrame(frameNode, packageName));
            }
            String stackId = stableStackId(frames);
            if (!dictionary.containsKey(stackId)) {
                totalUniqueFrames += frames.size();
                if (dictionary.size() >= properties.getMaxJankStackDictionary()
                        || totalUniqueFrames > properties.getMaxJankTotalFrames()) {
                    throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "卡顿堆栈证据规模超过服务端限制");
                }
                dictionary.put(stackId, List.copyOf(frames));
            }
            samples.add(new JankSample(offset, stackId));
            normalizedSegments.add(new NormalizedSegment(offset, estimatedEnd, stackId));
        }

        int parsed = samples.size();
        long expectedLong = duration / interval + (duration % interval == 0 ? 0 : 1);
        if (expectedLong > Integer.MAX_VALUE) {
            throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "理论采样数量超过服务端限制");
        }
        int expected = (int) expectedLong;
        int missing = Math.max(0, expected - parsed);
        List<JankSampleSlice> slices = slices(normalizedSegments, duration);
        List<JankCallTreeNode> callTree = callTree(mainThread.get("callTree"), packageName);
        long covered = requireLong(mainThread, "estimatedCoveredDurationNs");
        if (covered < 0 || covered > duration) {
            throw invalid("INVALID_JANK_EVIDENCE", "主线程估算覆盖范围无效");
        }
        long unattributed = callTree.stream().mapToLong(JankCallTreeNode::estimatedUnattributedDurationNs).sum();
        List<String> warnings = warnings(report.get("warnings"));

        JankPayload payload = new JankPayload(
                requireText(manifest, "scene"), ALGORITHM_VERSION, duration, threshold, interval,
                List.copyOf(samples), Map.copyOf(dictionary), expected, parsed, missing);
        payload = sanitizer.sanitizeJank(payload);
        JankAnalysis analysis = new JankAnalysis(
                duration, covered, Math.min(covered, unattributed), covered, duration - covered,
                slices, callTree, payload.stackDictionary(), expected, parsed, missing,
                ALGORITHM_VERSION, warnings);
        validateDetailSize(payload, analysis);

        String sanitizedAppId = sanitizer.sanitizeIdentifier(packageName, 255);
        String fingerprint = fingerprintService.fingerprint(appId, sanitizedAppId, payload, analysis);
        long versionCode = requireLong(manifest, "versionCode");
        if (versionCode < 0 || versionCode > Integer.MAX_VALUE) {
            throw invalid("INVALID_JANK_MANIFEST", "versionCode 超出服务端支持范围");
        }
        try {
            return new StoredEvent(
                    appId,
                    sanitizedAppId,
                    sanitizer.sanitizeIdentifier(eventId, 128),
                    "jank",
                    Instant.ofEpochMilli(occurredAt),
                    receivedAt,
                    MANIFEST_SCHEMA_VERSION,
                    sanitizer.sanitizeText(requireText(manifest, "sessionId"), 128),
                    sanitizer.hashDeviceId(requireText(manifest, "anonymousDeviceId")),
                    sanitizer.sanitizeText(requireText(manifest, "appVersion"), 128),
                    (int) versionCode,
                    sanitizer.sanitizeText(requireText(manifest, "buildId"), 256),
                    sanitizer.sanitizeText(requireText(manifest, "environment"), 64),
                    sanitizer.sanitizeText(requireText(manifest, "channel"), 128),
                    sanitizer.sanitizeText(requireText(manifest, "osVersion"), 64),
                    sanitizer.sanitizeText(requireText(manifest, "deviceModel"), 256),
                    null,
                    Map.of(),
                    Map.of(),
                    null, null, null,
                    fingerprint,
                    fingerprintService.version(),
                    mappingApplied ? "symbolicated" : "raw_only",
                    null,
                    payload,
                    analysis,
                    null,
                    null);
        } catch (RuntimeException ex) {
            if (ex instanceof InvalidStackArtifactException invalidStackArtifactException) {
                throw invalidStackArtifactException;
            }
            throw invalid("INVALID_JANK_MANIFEST", "卡顿 manifest 时间字段无效");
        }
    }

    public String sourcePackageName(JsonNode report) {
        requireObject(report, "INVALID_STACK_REPORT", "解析结果不是受支持的堆栈报告");
        if (!report.path("schemaVersion").isIntegralNumber()
                || report.path("schemaVersion").asInt() != REPORT_SCHEMA_VERSION
                || !report.path("artifactType").isTextual()
                || !"RHEA_STACK_REPORT".equals(report.path("artifactType").asText())) {
            throw invalid("INVALID_STACK_REPORT", "解析结果不是受支持的堆栈报告");
        }
        JsonNode manifest = report.get("sourceManifest");
        requireObject(manifest, "INVALID_JANK_MANIFEST", "解析结果缺少卡顿 manifest");
        rejectLegacyManifestIdentity(manifest);
        if (requireInt(manifest, "schemaVersion") != MANIFEST_SCHEMA_VERSION
                || !"RHEA_JANK".equals(requireText(manifest, "artifactType"))) {
            throw invalid("UNSUPPORTED_JANK_ARTIFACT", "只支持 v3 RHEA_JANK 产物");
        }
        return requireText(manifest, "packageName");
    }

    private void rejectLegacyManifestIdentity(JsonNode manifest) {
        if (manifest.has("appId")) {
            throw invalid("INVALID_JANK_MANIFEST", "卡顿 manifest 不得使用旧 appId 字段");
        }
    }

    private JsonNode findMainThread(JsonNode threads, long processId) {
        if (threads == null || !threads.isArray()) {
            throw invalid("INVALID_STACK_REPORT", "解析结果缺少线程证据");
        }
        JsonNode result = null;
        for (JsonNode thread : threads) {
            if (thread != null && thread.isObject() && thread.path("tid").isIntegralNumber()
                    && thread.path("tid").asLong() == processId) {
                if (result != null) {
                    throw invalid("INVALID_JANK_EVIDENCE", "解析结果包含重复目标主线程");
                }
                result = thread;
            }
        }
        if (result == null) {
            throw invalid("INVALID_JANK_EVIDENCE", "解析结果找不到目标主线程");
        }
        return result;
    }

    private List<JankSampleSlice> slices(List<NormalizedSegment> segments, long duration) {
        List<NormalizedSegment> sorted = segments.stream()
                .sorted(Comparator.comparingLong(NormalizedSegment::startOffsetNs)).toList();
        List<JankSampleSlice> result = new ArrayList<>();
        long cursor = 0;
        for (NormalizedSegment segment : sorted) {
            if (segment.startOffsetNs() > cursor) {
                result.add(new JankSampleSlice(cursor, segment.startOffsetNs(), null, false));
            }
            if (segment.estimatedEndOffsetNs() > segment.startOffsetNs()) {
                result.add(new JankSampleSlice(segment.startOffsetNs(), segment.estimatedEndOffsetNs(),
                        segment.stackId(), true));
                cursor = Math.max(cursor, segment.estimatedEndOffsetNs());
            }
        }
        if (cursor < duration) {
            result.add(new JankSampleSlice(cursor, duration, null, false));
        }
        return List.copyOf(result);
    }

    private List<JankCallTreeNode> callTree(JsonNode nodes, String packageName) {
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            throw invalid("INVALID_JANK_EVIDENCE", "目标主线程调用树不能为空");
        }
        Counter counter = new Counter();
        List<JankCallTreeNode> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(callTreeNode(node, packageName, 1, counter));
        }
        return List.copyOf(result);
    }

    private JankCallTreeNode callTreeNode(JsonNode node, String packageName, int depth, Counter counter) {
        requireObject(node, "INVALID_JANK_EVIDENCE", "调用树节点格式无效");
        if (depth > properties.getMaxJankStackDepth() || ++counter.value > properties.getMaxJankTotalFrames()) {
            throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "调用树证据规模超过服务端限制");
        }
        StackFrame frame = toFrame(node, packageName);
        long estimated = requireLong(node, "estimatedDurationNs");
        long unattributed = requireLong(node, "estimatedSelfDurationNs");
        if (estimated < 0 || unattributed < 0 || unattributed > estimated) {
            throw invalid("INVALID_JANK_EVIDENCE", "调用树估算耗时无效");
        }
        List<JankCallTreeNode> children = new ArrayList<>();
        JsonNode childNodes = node.get("children");
        if (childNodes == null || !childNodes.isArray()) {
            throw invalid("INVALID_JANK_EVIDENCE", "调用树 children 格式无效");
        }
        for (JsonNode child : childNodes) {
            children.add(callTreeNode(child, packageName, depth + 1, counter));
        }
        return new JankCallTreeNode(frame.className(), frame.methodName(), estimated,
                unattributed, List.copyOf(children));
    }

    private StackFrame toFrame(JsonNode node, String packageName) {
        requireObject(node, "INVALID_JANK_EVIDENCE", "堆栈帧格式无效");
        String symbol = requireText(node, "method").trim();
        String qualified = symbol;
        int parameters = qualified.indexOf('(');
        if (parameters >= 0) {
            qualified = qualified.substring(0, parameters);
        }
        int space = qualified.lastIndexOf(' ');
        if (space >= 0) {
            qualified = qualified.substring(space + 1);
        }
        int separator = qualified.lastIndexOf('.');
        String className = separator > 0 ? qualified.substring(0, separator) : "<unknown>";
        String methodName = separator > 0 ? qualified.substring(separator + 1) : qualified;
        String sourceFile = nullableText(node.get("sourceFile"));
        Integer lineNumber = node.path("lineNumber").isIntegralNumber() ? node.path("lineNumber").asInt() : null;
        return new StackFrame(
                sanitizer.sanitizeText(className, 512),
                sanitizer.sanitizeText(methodName, 512),
                sanitizer.sanitizeText(sourceFile, 512),
                lineNumber,
                packageName != null && className.startsWith(packageName));
    }

    private List<String> warnings(JsonNode warnings) {
        if (warnings == null || warnings.isNull()) {
            return List.of();
        }
        if (!warnings.isArray() || warnings.size() > 64) {
            throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "解析警告数量超过服务端限制");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode warning : warnings) {
            result.add(sanitizer.sanitizeText(warning.asText(), properties.getMaxMessageLength()));
        }
        return List.copyOf(result);
    }

    private void validateDetailSize(JankPayload payload, JankAnalysis analysis) {
        try {
            int size = objectMapper.writeValueAsBytes(Map.of("jank", payload, "analysis", analysis)).length;
            if (size > properties.getMaxJankDetailBytes()) {
                throw invalid("JANK_EVIDENCE_LIMIT_EXCEEDED", "归一化卡顿详情超过服务端限制");
            }
        } catch (InvalidStackArtifactException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid("INVALID_JANK_EVIDENCE", "归一化卡顿详情失败");
        }
    }

    private String stableStackId(List<StackFrame> frames) {
        StringBuilder value = new StringBuilder();
        for (StackFrame frame : frames) {
            value.append(frame.className()).append('#').append(frame.methodName()).append('|')
                    .append(frame.fileName()).append(':').append(frame.lineNumber()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("stack-");
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", ex);
        }
    }

    private void requireObject(JsonNode node, String code, String message) {
        if (node == null || !node.isObject()) {
            throw invalid(code, message);
        }
    }

    private String requireText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("INVALID_JANK_MANIFEST", "卡顿 manifest 字段无效: " + field);
        }
        return value.asText();
    }

    private long requireLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("INVALID_JANK_MANIFEST", "卡顿整数或证据字段无效: " + field);
        }
        return value.asLong();
    }

    private int requireInt(JsonNode node, String field) {
        long value = requireLong(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid("INVALID_JANK_MANIFEST", "卡顿整数字段超出范围: " + field);
        }
        return (int) value;
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private InvalidStackArtifactException invalid(String code, String message) {
        return new InvalidStackArtifactException(code, message);
    }

    private record NormalizedSegment(long startOffsetNs, long estimatedEndOffsetNs, String stackId) {
    }

    private static final class Counter {
        private int value;
    }
}
