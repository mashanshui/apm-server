package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CrashSanitizer {

    private static final int MAX_ATTRIBUTE_COUNT = 32;
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+(?:\\?[^\\s]*)?");
    private static final Pattern HOME_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\Users\\\\|/Users/|/home/)[^\\s:]+", Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d)(?!\\d)");

    private final CrashIngestConfiguration properties;

    public CrashSanitizer(CrashIngestConfiguration properties) {
        this.properties = properties;
    }

    public CrashIngestCommand sanitize(CrashIngestCommand event) {
        CrashPayload crash = event.crash() == null ? null : sanitizeCrash(event.crash());
        return new CrashIngestCommand(
                event.schemaVersion(),
                sanitizeIdentifier(event.eventId(), 128),
                sanitizeIdentifier(event.eventType(), 32),
                event.occurredAt(),
                sanitizeIdentifier(event.sessionId(), 128),
                event.processId(),
                hashDeviceId(event.anonymousDeviceId()),
                sanitizeIdentifier(event.packageName(), 255),
                sanitizeText(event.appVersion(), 128),
                event.versionCode(),
                sanitizeIdentifier(event.buildId(), 256),
                sanitizeIdentifier(event.environment(), 64),
                sanitizeIdentifier(event.channel(), 128),
                sanitizeIdentifier(event.osVersion(), 64),
                sanitizeText(event.deviceModel(), 256),
                sanitizeIdentifier(event.networkType(), 32),
                sanitizeMap(event.measurements(), false, true),
                sanitizeMap(event.attributes(), true, false),
                crash);
    }

    public CrashPayload sanitizeCrash(CrashPayload crash) {
        List<ThrowableNode> chain = crash.throwableChain().stream()
                .map(node -> new ThrowableNode(
                        sanitizeText(node.type(), 512),
                        sanitizeText(node.message(), properties.getMaxMessageLength()),
                        node.frames().stream().map(this::sanitizeFrame).toList()))
                .toList();
        return new CrashPayload(sanitizeIdentifier(crash.kind(), 32), crash.fatal(), chain);
    }

    public String sanitizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value
                .replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        sanitized = HOME_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = URL.matcher(sanitized).replaceAll("[url]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[email]");
        sanitized = UUID.matcher(sanitized).replaceAll("[id]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[phone]");
        return truncate(sanitized, maxLength);
    }

    public String hashDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return sha256(properties.getDeviceHashSalt() + ":" + value.trim());
    }

    private StackFrame sanitizeFrame(StackFrame frame) {
        return new StackFrame(
                sanitizeText(frame.className(), 512),
                sanitizeText(frame.methodName(), 512),
                sanitizeText(frame.fileName(), 512),
                frame.lineNumber(),
                Boolean.TRUE.equals(frame.applicationFrame()));
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
                result.put(entry.getKey(), redact
                        ? sanitizeText(String.valueOf(value), 512)
                        : truncate(String.valueOf(value), 512));
            }
        });
        return Map.copyOf(result);
    }

    public String sanitizeIdentifier(String value, int maxLength) {
        return value == null ? null : truncate(value.trim().replaceAll("[\\r\\n\\t]", "?"), maxLength);
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
