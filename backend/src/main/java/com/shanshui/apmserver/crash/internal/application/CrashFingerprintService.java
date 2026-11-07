package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.symbol.api.RetraceFingerprintInput;
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

    /** 临时指纹显式绑定构建，混淆名称巧合相同也不能跨构建归组。 */
    public String temporary(java.util.UUID appId, String packageName, String buildId, CrashPayload crash) {
        return sha256("tmp-v1|" + appId + "|" + buildId + "|" + fingerprint(appId, packageName, crash));
    }

    /** 正式指纹只使用还原后的身份；包含一个候选组时保留该组全部候选。 */
    public String resolved(java.util.UUID appId, String packageName, RetraceFingerprintInput input) {
        // 应用关键调用按原顺序选取最多八组，组内已稳定排序。
        List<List<String>> application = input.frameGroups().stream()
                .filter(group -> group.stream().anyMatch(frame -> frame.startsWith(packageName + ".")))
                .limit(MAX_FRAMES).toList();
        // 无可靠应用帧时使用版本化的完整候选/帧回退，不能随意选择第一条。
        List<List<String>> selected = application.isEmpty() ? input.frameGroups() : application;
        StringBuilder identity = new StringBuilder("resolved-v1");
        appendIdentity(identity, appId.toString());
        input.exceptions().forEach(header -> appendIdentity(identity, normalize(header)));
        appendIdentity(identity, application.isEmpty() ? "all-candidates-v1" : "application-frames-v1");
        for (List<String> group : selected) {
            appendIdentity(identity, "frame-group");
            group.forEach(frame -> appendIdentity(identity, frame));
        }
        return sha256(identity.toString());
    }

    /** 长度前缀避免消息、候选或类名中的分隔符造成身份碰撞。 */
    private void appendIdentity(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
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
