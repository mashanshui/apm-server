package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.api.JankIngestConfiguration;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Jank 载荷及公共元数据的脱敏器，不依赖 Crash 内部实现。 */
@Service
public class JankSanitizer {

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+(?:\\?[^\\s]*)?");
    private static final Pattern HOME_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\Users\\\\|/Users/|/home/)[^\\s:]+", Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d)(?!\\d)");

    private final JankIngestConfiguration properties;

    public JankSanitizer(JankIngestConfiguration properties) {
        this.properties = properties;
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
        return new JankPayload(sanitizeText(jank.scene(), properties.getMaxSceneLength()),
                sanitizeIdentifier(jank.algorithmVersion(), 64), jank.messageDurationNs(), jank.thresholdNs(),
                jank.samplingIntervalNs(), samples, Map.copyOf(dictionary), jank.expectedSampleCount(),
                jank.parsedSampleCount(), jank.missingSampleCount());
    }

    public FrameSceneSummaryPayload sanitizeFrameScene(FrameSceneSummaryPayload payload) {
        return new FrameSceneSummaryPayload(sanitizeText(payload.scene(), properties.getMaxSceneLength()),
                sanitizeIdentifier(payload.algorithmVersion(), 64), payload.activeDurationMs(),
                payload.uiRefreshFrameCount(), payload.refreshRateHz(), payload.normalizedFps60(),
                payload.frameDurationHistogram() == null ? Map.of() : sanitizeHistogram(payload.frameDurationHistogram()));
    }

    public ForegroundSuspensionSummaryPayload sanitizeSuspension(ForegroundSuspensionSummaryPayload payload) {
        return new ForegroundSuspensionSummaryPayload(sanitizeIdentifier(payload.algorithmVersion(), 64),
                payload.foregroundDurationMs(), payload.suspensionDurationMs(), payload.suspensionCount(), payload.thresholdMs());
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

    public String hashDeviceId(String value) {
        return value == null || value.isBlank() ? value : sha256(properties.getDeviceHashSalt() + ":" + value.trim());
    }

    private StackFrame sanitizeFrame(StackFrame frame) {
        return new StackFrame(sanitizeText(frame.className(), 512), sanitizeText(frame.methodName(), 512),
                sanitizeText(frame.fileName(), 512), frame.lineNumber(), Boolean.TRUE.equals(frame.applicationFrame()));
    }

    private Map<String, Integer> sanitizeHistogram(Map<String, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        values.entrySet().stream().limit(properties.getMaxFrameHistogramBuckets()).forEach(entry -> {
            if (entry.getKey() != null && entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")
                    && entry.getValue() != null && entry.getValue() >= 0) {
                result.put(entry.getKey(), entry.getValue());
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
