package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.domain.ThrowableNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class CrashFingerprintService {

    public static final String VERSION = "v1";
    private static final int MAX_FRAMES = 8;
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX_ADDRESS = Pattern.compile("(?i)0x[0-9a-f]+");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern QUOTED_VALUE = Pattern.compile("(['\"]).*?\\1");

    public String fingerprint(java.util.UUID appId, String packageName, CrashPayload crash) {
        if (crash == null || crash.throwableChain() == null || crash.throwableChain().isEmpty()) {
            return sha256(appId + "|" + packageName + "|missing-crash");
        }
        ThrowableNode primary = crash.throwableChain().get(0);
        List<StackFrame> frames = new ArrayList<>();
        for (ThrowableNode node : crash.throwableChain()) {
            if (node == null || node.frames() == null) {
                continue;
            }
            for (StackFrame frame : node.frames()) {
                if (frame != null && Boolean.TRUE.equals(frame.applicationFrame())) {
                    frames.add(frame);
                }
            }
        }
        if (frames.isEmpty()) {
            for (ThrowableNode node : crash.throwableChain()) {
                if (node != null && node.frames() != null) {
                    frames.addAll(node.frames().stream().filter(frame -> frame != null).toList());
                }
            }
        }
        StringBuilder normalized = new StringBuilder();
        normalized.append(appId).append('|').append(packageName).append('|').append(crash.kind()).append('|');
        normalized.append(normalize(primary.type())).append('|');
        normalized.append(normalize(primary.message())).append('|');
        frames.stream().limit(MAX_FRAMES).forEach(frame -> normalized
                .append(normalize(frame.className())).append('#')
                .append(normalize(frame.methodName())).append('@')
                .append(normalize(frame.fileName())).append(';'));
        return sha256(normalized.toString());
    }

    public String version() {
        return VERSION;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = UUID.matcher(value).replaceAll("<id>");
        normalized = HEX_ADDRESS.matcher(normalized).replaceAll("<address>");
        normalized = QUOTED_VALUE.matcher(normalized).replaceAll("<value>");
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
}
