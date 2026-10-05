package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.api.JankEventSummary;
import com.shanshui.apmserver.jank.api.JankIssueSummary;
import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.platform.api.QueryValidationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.HexFormat;

/** 版本化、查询绑定的 Jank 排序游标；不是授权凭据或数据库快照。 */
public final class JankCursor {

    /** 领域独立版本，拒绝旧纯文本及 Crash 游标。 */
    private static final int VERSION = 3;

    /** 无状态编码器禁止实例化。 */
    private JankCursor() {
    }

    /** 解码后仍须在完成时间窗和筛选规范化后验证摘要。 */
    public static State decode(String encoded, Kind kind) {
        if (encoded == null || encoded.length() > 2048) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != VERSION || input.readUnsignedByte() != kind.code) throw invalid();
            Instant from = Instant.parse(input.readUTF());
            Instant to = Instant.parse(input.readUTF());
            String digest = input.readUTF();
            long count = input.readLong();
            Instant time = Instant.parse(input.readUTF());
            String id = input.readUTF();
            if (input.available() != 0 || !from.isBefore(to) || id.isEmpty() || id.length() > 256
                    || !digest.matches("[a-f0-9]{64}") || time.isBefore(from) || !time.isBefore(to)
                    || (kind == Kind.ISSUES ? count < 1 : count != 0)) throw invalid();
            return new State(kind, from, to, digest, count, time, id);
        } catch (IllegalArgumentException | IOException | DateTimeException ex) {
            throw invalid();
        }
    }

    /** 应用标识和所有筛选条件连同排序种类都绑定到游标。 */
    public static State validate(String encoded, Kind kind, JankQueryFilter filter) {
        State state = decode(encoded, kind);
        if (!state.from().equals(filter.from()) || !state.to().equals(filter.to())
                || !state.digest().equals(digest(filter, kind))) throw invalid();
        return state;
    }

    /** Issue 页将聚合后的完整排序元组写入下一页游标。 */
    public static String issue(JankQueryFilter filter, JankIssueSummary value) {
        return encode(filter, Kind.ISSUES, value.eventCount(), value.lastSeenAt(), value.fingerprint());
    }

    /** 事件页将时间和事件 ID 写入下一页游标。 */
    public static String event(JankQueryFilter filter, JankEventSummary value) {
        return encode(filter, Kind.EVENTS, 0, value.occurredAt(), value.eventId());
    }

    /** 以长度边界明确的二进制结构生成 URL 安全游标。 */
    private static String encode(JankQueryFilter filter, Kind kind, long count, Instant time, String id) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeByte(VERSION);
            output.writeByte(kind.code);
            output.writeUTF(filter.from().toString());
            output.writeUTF(filter.to().toString());
            output.writeUTF(digest(filter, kind));
            output.writeLong(count);
            output.writeUTF(time.toString());
            output.writeUTF(id);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("游标编码失败", ex);
        }
    }

    /** 对入口、应用、绝对时间和规范化筛选做不可歧义摘要。 */
    private static String digest(JankQueryFilter filter, Kind kind) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String[] values = {kind.name(), filter.appId().toString(), filter.from().toString(),
                    filter.to().toString(), filter.appVersion(), filter.channel(), filter.environment(),
                    filter.osVersion(), filter.deviceModel(), filter.fingerprint(), filter.scene(), filter.algorithmVersion()};
            for (String value : values) {
                byte[] bytes = (value == null ? "\u0000" : value).getBytes(StandardCharsets.UTF_8);
                sha.update((byte) (bytes.length >>> 24));
                sha.update((byte) (bytes.length >>> 16));
                sha.update((byte) (bytes.length >>> 8));
                sha.update((byte) bytes.length);
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 所有游标结构及绑定失败共用稳定错误。 */
    private static QueryValidationException invalid() {
        return new QueryValidationException("INVALID_CURSOR", "游标无效或与当前查询不匹配，请从第一页重新查询", 400);
    }

    /** 两种列表拥有不同的排序和摘要空间。 */
    public enum Kind {
        ISSUES('I'), EVENTS('E');

        /** 列表种类在二进制游标中的独立标识。 */
        private final char code;

        /** 保存固定种类代码。 */
        Kind(char code) {
            this.code = code;
        }
    }

    /** 解码后的时间窗和排序元组。 */
    public record State(Kind kind, Instant from, Instant to, String digest,
                        long count, Instant time, String id) {
    }
}
