package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakPathNode;
import com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration;
import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 解析并限制 SDK 报告。size、nowTime 与数组位置均按原文保留，不推断额外指标。 */
@Service
public class MemoryLeakReportParser {
    private static final Set<String> METADATA_FIELDS = Set.of("schemaVersion", "eventId", "occurredAt",
            "packageName", "appVersion", "versionCode", "anonymousDeviceId", "processName",
            "sessionId", "buildId", "environment", "channel");
    private final ObjectMapper objectMapper;
    private final MemoryLeakReportConfiguration config;

    public MemoryLeakReportParser(ObjectMapper objectMapper, MemoryLeakReportConfiguration config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public MemoryLeakReport parse(UUID appId, JsonNode metadata, JsonNode report, Instant receivedAt) {
        if (metadata == null || !metadata.isObject()) throw invalid("metadata 必须是 JSON 对象");
        rejectUnknown(metadata, METADATA_FIELDS, "metadata 字段不受支持");
        int schema = Math.toIntExact(integer(metadata.get("schemaVersion"), "schemaVersion", 1, Integer.MAX_VALUE));
        if (schema != 1) throw invalid("schemaVersion 必须为 1");
        UUID eventId;
        try { eventId = UUID.fromString(text(metadata, "eventId")); }
        catch (IllegalArgumentException ex) { throw invalid("eventId 必须是 UUID"); }
        long occurredMillis = integer(metadata.get("occurredAt"), "occurredAt", 0, 9_007_199_254_740_991L);
        Instant occurredAt;
        try { occurredAt = Instant.ofEpochMilli(occurredMillis); }
        catch (DateTimeException ex) { throw invalid("occurredAt 超出时间范围"); }
        String packageName = text(metadata, "packageName");
        String appVersion = text(metadata, "appVersion");
        long versionCode = integer(metadata.get("versionCode"), "versionCode", 0, 9_007_199_254_740_991L);
        String device = text(metadata, "anonymousDeviceId");
        String process = text(metadata, "processName");
        if (report == null || report.isNull()) throw invalid("report 文件不能为空");
        validateReportShape(report);
        List<MemoryLeakPath> paths = paths(report.get("gcPaths"));
        JsonNode running = report.get("runningInfo");
        String deviceModel = optionalText(running, "buildModel");
        String scene = optionalText(running, "currentPage");
        String manufacturer = optionalText(running, "manufacture");
        Integer sdkInt = optionalInteger(running == null ? null : running.get("sdkInt"));
        String dumpReason = optionalText(running, "dumpReason");
        return new MemoryLeakReport(appId, eventId, occurredAt, receivedAt, packageName, appVersion,
                versionCode, device, process, optionalText(metadata, "sessionId"), optionalText(metadata, "buildId"),
                optionalText(metadata, "environment"), optionalText(metadata, "channel"), deviceModel, scene,
                manufacturer, sdkInt, dumpReason, report, paths,
                sha256("{\"metadata\":" + canonical(metadata) + ",\"report\":" + canonical(report) + "}"),
                null, null, 0);
    }

    private void validateReportShape(JsonNode report) {
        if (!report.isObject()) throw invalid("report 必须是对象");
        for (String field : List.of("runningInfo", "gcPaths", "classInfos", "leakObjects")) {
            JsonNode value = report.get(field);
            if (value == null || (field.equals("runningInfo") ? !value.isObject() : !value.isArray())) {
                throw invalid("report." + field + " 结构无效");
            }
        }
        validateClassInfos(report.get("classInfos"));
        validateLeakObjects(report.get("leakObjects"));
        validateTree(report, 1);
    }

    private void validateClassInfos(JsonNode node) {
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid("classInfos 元素必须是对象");
            text(item, "className");
            integer(item.get("instanceCount"), "classInfos.instanceCount", 0, 9_007_199_254_740_991L);
        }
    }

    private void validateLeakObjects(JsonNode node) {
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid("leakObjects 元素必须是对象");
            text(item, "className");
            // objectId 在 SDK 协议中始终按字符串保存，避免把对象标识误当成数值。
            text(item, "objectId");
            integer(item.get("size"), "leakObjects.size", 0, 9_007_199_254_740_991L);
            optionalText(item, "extDetail");
        }
    }

    private List<MemoryLeakPath> paths(JsonNode node) {
        List<MemoryLeakPath> result = new ArrayList<>();
        Map<String, MemoryLeakPath> bySignature = new LinkedHashMap<>();
        for (JsonNode pathNode : node) {
            String signature = text(pathNode, "signature");
            String gcRoot = text(pathNode, "gcRoot");
            String reason = text(pathNode, "leakReason");
            long count = integer(pathNode.get("instanceCount"), "instanceCount", 1, 9_007_199_254_740_991L);
            JsonNode nodes = pathNode.get("path");
            if (nodes == null || !nodes.isArray() || nodes.isEmpty()) throw invalid("gcPaths.path 不能为空");
            if (nodes.size() > config.getMaxPathNodes()) throw invalid("引用链节点超过上限");
            List<MemoryLeakPathNode> chain = new ArrayList<>();
            for (JsonNode n : nodes) {
                if (!n.isObject()) throw invalid("引用链节点必须是对象");
                chain.add(new MemoryLeakPathNode(text(n, "reference"), text(n, "referenceType"),
                        optionalText(n, "declaredClass")));
            }
            MemoryLeakPath candidate = new MemoryLeakPath(signature, gcRoot, reason, count,
                    List.copyOf(chain), sha256(canonical(pathNode)));
            MemoryLeakPath existing = bySignature.putIfAbsent(signature, candidate);
            if (existing != null && !existing.contentHash().equals(candidate.contentHash())) {
                throw invalid("同一报告 signature 对应了不同引用链");
            }
        }
        result.addAll(bySignature.values());
        return List.copyOf(result);
    }

    private void validateTree(JsonNode node, int depth) {
        if (depth > config.getMaxDepth()) throw invalid("报告 JSON 深度超过上限");
        if (node.isTextual() && node.asText().getBytes(StandardCharsets.UTF_8).length > config.getMaxStringBytes()) {
            throw invalid("报告字符串超过上限");
        }
        if (node.isArray()) {
            if (node.size() > config.getMaxArrayItems()) throw invalid("报告数组元素超过上限");
            for (JsonNode child : node) validateTree(child, depth + 1);
        } else if (node.isObject()) {
            for (JsonNode value : node.values()) validateTree(value, depth + 1);
        }
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String message) {
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid(message);
    }

    private JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) throw invalid("缺少字段: " + field);
        return value;
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = required(parent, field);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(field + " 必须是非空字符串");
        if (value.asText().getBytes(StandardCharsets.UTF_8).length > config.getMaxStringBytes()) throw invalid(field + " 超过长度上限");
        return value.asText();
    }

    private String optionalText(JsonNode parent, String field) {
        if (parent == null || parent.get(field) == null || parent.get(field).isNull()) return null;
        return text(parent, field);
    }

    private Integer optionalInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        long value = integer(node, "sdkInt", 0, Integer.MAX_VALUE);
        return (int) value;
    }

    private long integer(JsonNode value, String field, long min, long max) {
        if (value == null || value.isNull()) throw invalid("缺少字段: " + field);
        String raw = value.isIntegralNumber() ? value.asText() : value.isTextual() ? value.asText() : "";
        if (!raw.matches("[0-9]+")) throw invalid(field + " 必须是非负十进制整数");
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < min || parsed > max) throw invalid(field + " 超出范围");
            return parsed;
        } catch (NumberFormatException ex) { throw invalid(field + " 超出范围"); }
    }

    private String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames()); names.sort(String::compareTo);
            return "{" + names.stream().map(name -> quote(name) + ":" + canonical(node.get(name))).reduce((a,b)->a+","+b).orElse("") + "}";
        }
        if (node.isArray()) { List<String> values = new ArrayList<>(); node.forEach(n -> values.add(canonical(n))); return "[" + String.join(",", values) + "]"; }
        return node.toString();
    }
    private String quote(String value) { try { return objectMapper.writeValueAsString(value); } catch (RuntimeException ex) { throw invalid("JSON 字段无效"); } }
    private String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private MemoryLeakReportValidationException invalid(String message) { return new MemoryLeakReportValidationException(message); }
}
