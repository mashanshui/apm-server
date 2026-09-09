package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryIngestCommand;
import com.shanshui.apmserver.memory.api.MemoryMetricsConfiguration;
import com.shanshui.apmserver.memory.api.MemorySamplePayload;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 内存事件公共字段和文本字段脱敏器，不依赖 Crash/Jank 内部实现。 */
@Service
public class MemorySanitizer {

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+(?:\\?[^\\s]*)?");
    private static final Pattern HOME_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\Users\\\\|/Users/|/home/)[^\\s:]+", Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d)(?!\\d)");
    private static final int MAX_ATTRIBUTE_COUNT = 32;

    private final MemoryMetricsConfiguration properties;

    public MemorySanitizer(MemoryMetricsConfiguration properties) {
        this.properties = properties;
    }

    public MemoryIngestCommand sanitize(MemoryIngestCommand event) {
        MemorySamplePayload sample = event.memorySample();
        MemorySamplePayload sanitizedSample = sample == null ? null : new MemorySamplePayload(
                sample.pssBytes(), sample.vssBytes(), sample.javaHeapUsedBytes(),
                sanitizeName(sample.processName(), properties.getMaxProcessNameLength()), sample.foreground(),
                sanitizeName(sample.scene(), properties.getMaxSceneLength()));
        return new MemoryIngestCommand(event.schemaVersion(), sanitizeIdentifier(event.eventId(), 128),
                sanitizeIdentifier(event.eventType(), 32), event.occurredAt(), sanitizeIdentifier(event.sessionId(), 128),
                hashDeviceId(event.anonymousDeviceId()), sanitizeIdentifier(event.packageName(), 255),
                sanitizeText(event.appVersion(), 128), event.versionCode(), sanitizeText(event.buildId(), 256),
                sanitizeIdentifier(event.environment(), 64), sanitizeIdentifier(event.channel(), 128),
                sanitizeIdentifier(event.osVersion(), 64), sanitizeText(event.deviceModel(), 256),
                sanitizeIdentifier(event.networkType(), 32), sanitizeMap(event.measurements(), false, true),
                sanitizeMap(event.attributes(), true, false), sanitizedSample);
    }

    public String sanitizeIdentifier(String value, int maxLength) {
        return value == null ? null : truncate(value.trim().replaceAll("[\\r\\n\\t]", "?"), maxLength);
    }

    public String sanitizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\u0000', ' ').replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        sanitized = HOME_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = URL.matcher(sanitized).replaceAll("[url]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[email]");
        sanitized = UUID.matcher(sanitized).replaceAll("[id]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[phone]");
        return truncate(sanitized, maxLength);
    }

    /**
     * 清理进程名和 Activity 名称等结构化标识符。
     *
     * 这些字段用于精确筛选，包名中的数字不能按手机号脱敏，否则客户端上传的
     * 原始名称会与查询条件不一致。通用文本字段仍由 {@link #sanitizeText(String, int)}
     * 执行隐私模式替换。
     */
    private String sanitizeName(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value.trim().replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?")
                .replaceAll("[\\r\\n\\t]", "?");
        return truncate(sanitized, maxLength);
    }

    public String hashDeviceId(String value) {
        return value == null || value.isBlank() ? value : sha256(properties.getDeviceHashSalt() + ":" + value.trim());
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> values, boolean redact, boolean numericOnly) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.entrySet().stream().limit(MAX_ATTRIBUTE_COUNT).forEach(entry -> {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")) {
                return;
            }
            Object value = entry.getValue();
            if (numericOnly && !(value instanceof Number)) {
                return;
            }
            if (value instanceof Number || value instanceof Boolean) {
                result.put(entry.getKey(), value);
            } else if (value != null) {
                result.put(entry.getKey(), redact ? sanitizeText(String.valueOf(value), 512)
                        : truncate(String.valueOf(value), 512));
            }
        });
        return Map.copyOf(result);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", ex);
        }
    }
}
