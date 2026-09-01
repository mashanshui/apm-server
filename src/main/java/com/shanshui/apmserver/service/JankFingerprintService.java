package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.JankCallTreeNode;
import com.shanshui.apmserver.domain.JankPayload;
import com.shanshui.apmserver.domain.JankSampleSlice;
import com.shanshui.apmserver.domain.StackFrame;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** 服务端生成的卡顿 Issue 指纹；版本值固定为 jank-v1。 */
@Service
public class JankFingerprintService {

    public static final String VERSION = "jank-v1";
    private static final int MAX_FRAMES = 8;
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX_ADDRESS = Pattern.compile("(?i)0x[0-9a-f]+");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    public String fingerprint(java.util.UUID appId, String packageName, JankPayload payload, JankAnalysis analysis) {
        String path = criticalPath(payload, analysis);
        return sha256(appId + "|" + packageName + "|" + normalize(payload.scene()) + "|" + path);
    }

    public String version() {
        return VERSION;
    }

    private String criticalPath(JankPayload payload, JankAnalysis analysis) {
        List<WeightedPath> paths = new ArrayList<>();
        for (JankSampleSlice slice : analysis.sampleSlices()) {
            if (!slice.covered() || slice.durationNs() <= 0) {
                continue;
            }
            List<StackFrame> frames = payload.stackDictionary().get(slice.stackId());
            if (frames == null || frames.isEmpty()) {
                continue;
            }
            List<String> normalized = frames.stream()
                    .filter(frame -> frame != null)
                    .filter(frame -> Boolean.TRUE.equals(frame.applicationFrame()))
                    .map(frame -> normalize(frame.className()) + "#" + normalize(frame.methodName()))
                    .limit(MAX_FRAMES)
                    .toList();
            if (normalized.isEmpty()) {
                normalized = frames.stream().filter(frame -> frame != null)
                        .map(frame -> normalize(frame.className()) + "#" + normalize(frame.methodName()))
                        .limit(MAX_FRAMES).toList();
            }
            paths.add(new WeightedPath(String.join(";", normalized), slice.durationNs()));
        }
        return paths.stream().collect(java.util.stream.Collectors.groupingBy(WeightedPath::path,
                        java.util.stream.Collectors.summingLong(WeightedPath::durationNs))).entrySet().stream()
                .sorted(MapEntryComparator.INSTANCE)
                .map(java.util.Map.Entry::getKey)
                .findFirst().orElseGet(() -> treePath(analysis.callTree()));
    }

    private String treePath(List<JankCallTreeNode> nodes) {
        JankCallTreeNode current = nodes.stream()
                .max(Comparator.comparingLong(JankCallTreeNode::estimatedDurationNs)
                        .thenComparing(node -> normalize(node.className()) + "#" + normalize(node.methodName())))
                .orElse(null);
        if (current == null) {
            return "missing";
        }
        List<String> path = new ArrayList<>();
        while (current != null && path.size() < MAX_FRAMES) {
            path.add(normalize(current.className()) + "#" + normalize(current.methodName()));
            current = current.children().stream()
                    .max(Comparator.comparingLong(JankCallTreeNode::estimatedDurationNs)
                            .thenComparing(node -> normalize(node.className()) + "#" + normalize(node.methodName())))
                    .orElse(null);
        }
        return String.join(";", path);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = UUID.matcher(value).replaceAll("<id>");
        normalized = HEX_ADDRESS.matcher(normalized).replaceAll("<address>");
        normalized = NUMBER.matcher(normalized).replaceAll("<number>");
        return normalized.replaceAll("\\s+", " ").trim().toLowerCase();
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

    private record WeightedPath(String path, long durationNs) {
    }

    private enum MapEntryComparator implements Comparator<java.util.Map.Entry<String, Long>> {
        INSTANCE;

        @Override
        public int compare(java.util.Map.Entry<String, Long> left, java.util.Map.Entry<String, Long> right) {
            int weight = Long.compare(right.getValue(), left.getValue());
            return weight != 0 ? weight : left.getKey().compareTo(right.getKey());
        }
    }
}
