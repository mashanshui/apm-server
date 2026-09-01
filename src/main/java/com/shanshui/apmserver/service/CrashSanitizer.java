package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.FrameSceneSummaryPayload;
import com.shanshui.apmserver.domain.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.domain.JankPayload;
import com.shanshui.apmserver.domain.JankSample;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.domain.ThrowableNode;
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

    private final IngestProperties properties;

    public CrashSanitizer(IngestProperties properties) {
        this.properties = properties;
    }

    public EventEnvelope sanitize(EventEnvelope event) {
        CrashPayload crash = event.crash() == null ? null : sanitizeCrash(event.crash());
        return new EventEnvelope(
                event.schemaVersion(),
                sanitizeIdentifier(event.eventId(), 128),
                sanitizeIdentifier(event.eventType(), 32),
                event.occurredAt(),
                sanitizeIdentifier(event.sessionId(), 128),
                hashDeviceId(event.anonymousDeviceId()),
                sanitizeIdentifier(event.packageName(), 255),
                sanitizeText(event.appVersion(), 128),
                event.versionCode(),
                sanitizeText(event.buildId(), 256),
                sanitizeIdentifier(event.environment(), 64),
                sanitizeIdentifier(event.channel(), 128),
                sanitizeIdentifier(event.osVersion(), 64),
                sanitizeText(event.deviceModel(), 256),
                sanitizeIdentifier(event.networkType(), 32),
                sanitizeMap(event.measurements(), false, true),
                sanitizeMap(event.attributes(), true, false),
                crash,
                event.jank() == null ? null : sanitizeJank(event.jank()),
                event.frameSceneSummary() == null ? null : sanitizeFrameScene(event.frameSceneSummary()),
                event.foregroundSuspensionSummary() == null
                        ? null : sanitizeSuspension(event.foregroundSuspensionSummary()));
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

    public JankPayload sanitizeJank(JankPayload jank) {
        Map<String, List<StackFrame>> dictionary = new LinkedHashMap<>();
        if (jank.stackDictionary() != null) {
            jank.stackDictionary().entrySet().stream().limit(properties.getMaxJankStackDictionary()).forEach(entry -> {
                String stackId = sanitizeIdentifier(entry.getKey(), 128);
                if (stackId == null || entry.getValue() == null) {
                    return;
                }
                List<StackFrame> frames = entry.getValue().stream()
                        .limit(properties.getMaxJankStackDepth())
                        .filter(frame -> frame != null)
                        .map(this::sanitizeFrame)
                        .toList();
                dictionary.put(stackId, frames);
            });
        }
        List<JankSample> samples = jank.samples() == null ? List.of() : jank.samples().stream()
                .filter(sample -> sample != null)
                .limit(properties.getMaxJankSamples())
                .map(sample -> new JankSample(sample.offsetNs(), sanitizeIdentifier(sample.stackId(), 128)))
                .toList();
        return new JankPayload(
                sanitizeText(jank.scene(), properties.getMaxSceneLength()),
                sanitizeIdentifier(jank.algorithmVersion(), 64),
                jank.messageDurationNs(),
                jank.thresholdNs(),
                jank.samplingIntervalNs(),
                samples,
                Map.copyOf(dictionary),
                jank.expectedSampleCount(),
                jank.parsedSampleCount(),
                jank.missingSampleCount());
    }

    public FrameSceneSummaryPayload sanitizeFrameScene(FrameSceneSummaryPayload payload) {
        return new FrameSceneSummaryPayload(
                sanitizeText(payload.scene(), properties.getMaxSceneLength()),
                sanitizeIdentifier(payload.algorithmVersion(), 64),
                payload.activeDurationMs(),
                payload.uiRefreshFrameCount(),
                payload.refreshRateHz(),
                payload.normalizedFps60(),
                payload.frameDurationHistogram() == null ? Map.of() : sanitizeHistogram(payload.frameDurationHistogram()));
    }

    public ForegroundSuspensionSummaryPayload sanitizeSuspension(ForegroundSuspensionSummaryPayload payload) {
        return new ForegroundSuspensionSummaryPayload(
                sanitizeIdentifier(payload.algorithmVersion(), 64),
                payload.foregroundDurationMs(),
                payload.suspensionDurationMs(),
                payload.suspensionCount(),
                payload.thresholdMs());
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

    private Map<String, Integer> sanitizeHistogram(Map<String, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        values.entrySet().stream().limit(properties.getMaxFrameHistogramBuckets()).forEach(entry -> {
            String key = entry.getKey();
            Integer value = entry.getValue();
            if (key != null && key.matches("[A-Za-z0-9_.-]{1,64}") && value != null && value >= 0) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
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
