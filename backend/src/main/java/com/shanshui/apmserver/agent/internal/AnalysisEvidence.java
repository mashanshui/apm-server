package com.shanshui.apmserver.agent.internal;

import com.shanshui.apmserver.crash.api.CrashAnalysisSnapshot;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 仅选取必要单事件信息，形成有界且脱敏的不可变 JSON 材料。 */
public final class AnalysisEvidence {
    /** 消息中明确标记的凭据值。 */
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_-]?key|token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+");
    /** 从词边界识别常见邮箱；占有量词避免长非邮箱文本引发平方级回溯。 */
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)++");

    /** 工具类不接受实例化。 */
    private AnalysisEvidence() {}

    /** 序列化保留完整异常链，绝不通过截断满足大小上限。 */
    public static String create(ObjectMapper mapper, UUID evidenceId, CrashAnalysisSnapshot snapshot) {
        // 详情包含身份字段，但证据仅显式选择所需字段。
        var detail = snapshot.detail();
        // 事件基本事实不包含 sessionId、processId 或设备唯一标识。
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", detail.eventId());
        event.put("appId", detail.appId());
        event.put("buildId", detail.buildId());
        event.put("fingerprint", detail.fingerprint());
        event.put("fingerprintVersion", detail.fingerprintVersion());
        event.put("occurredAt", detail.occurredAt());
        event.put("appVersion", detail.appVersion());
        event.put("versionCode", detail.versionCode());
        event.put("osVersion", detail.osVersion());
        // 原始链保持帧与异常顺序，消息内容执行统一脱敏。
        List<Map<String, Object>> chain = new ArrayList<>();
        for (var throwable : detail.rawCrash().throwableChain()) {
            Map<String, Object> node = new LinkedHashMap<>(); // 单个异常节点。
            node.put("type", redact(throwable.type()));
            node.put("message", redact(throwable.message()));
            node.put("frames", throwable.frames().stream().map(frame -> new com.shanshui.apmserver.telemetry.api.StackFrame(
                    redact(frame.className()), redact(frame.methodName()), redact(frame.fileName()),
                    frame.lineNumber(), frame.applicationFrame())).toList());
            chain.add(node);
        }
        // 片段标识由服务端生成，供后续结果校验归属。
        List<Map<String, Object>> fragments = new ArrayList<>();
        fragments.add(Map.of("id", "raw-crash", "kind", "jvm-crash", "content", chain));
        if (detail.symbolicatedStackText() != null) {
            fragments.add(Map.of("id", "retrace", "kind", "retrace", "text", redact(detail.symbolicatedStackText())));
        }
        // 冻结所有关联版本；不包含登记核验备注，避免传出人工备注中的身份材料。
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 2);
        evidence.put("evidenceId", evidenceId);
        evidence.put("event", event);
        // 不存在 mapping 时不制造版本或摘要。
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put("symbolId", detail.symbolFileId());
        symbol.put("revision", detail.symbolFileRevision());
        symbol.put("sha256", snapshot.mappingSha256());
        symbol.put("status", detail.symbolicationStatus());
        symbol.put("reason", detail.symbolicationReason());
        evidence.put("mapping", symbol);
        evidence.put("fragments", fragments);
        return mapper.writeValueAsString(evidence);
    }

    /** 用发送原文的 UTF-8 字节计算 SHA-256；JSONB 另保存原文以避免重排影响摘要。 */
    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    /** 尽量减少明确凭据与邮箱外发，不声称能识别任意自然语言秘密。 */
    private static String redact(String text) {
        if (text == null) return null;
        return EMAIL.matcher(SECRET.matcher(text).replaceAll("$1=[REDACTED]")).replaceAll("[EMAIL_REDACTED]");
    }
}
