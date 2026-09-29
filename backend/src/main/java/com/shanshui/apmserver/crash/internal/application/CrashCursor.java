package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
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

/** 版本化、查询绑定的 Crash 排序游标；不是授权凭据或数据库快照。 */
public final class CrashCursor {

    private static final int VERSION = 1;

    private CrashCursor() {
    }

    /** 解码后仍须在完成时间窗和筛选规范化后验证摘要。 */
    public static State decode(String encoded, Kind kind) {
        if (encoded == null || encoded.length() > 2048) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != VERSION || input.readUnsignedByte() != kind.code) throw invalid();
            Instant from = Instant.ofEpochMilli(input.readLong());
            Instant to = Instant.ofEpochMilli(input.readLong());
            String digest = input.readUTF();
            long count = input.readLong();
            Instant time = Instant.ofEpochMilli(input.readLong());
            String id = input.readUTF();
            if (input.available() != 0 || !from.isBefore(to) || id.isEmpty() || digest.length() != 64) throw invalid();
            return new State(kind, from, to, digest, count, time, id);
        } catch (IllegalArgumentException | IOException | DateTimeException ex) {
            throw invalid();
        }
    }

    /** 应用标识和所有筛选条件连同排序种类都绑定到游标。 */
    public static State validate(String encoded, Kind kind, CrashQueryFilter filter) {
        State state = decode(encoded, kind);
        if (!state.from().equals(filter.from()) || !state.to().equals(filter.to())
                || !state.digest().equals(digest(filter, kind))) throw invalid();
        return state;
    }

    /** Issue 页将聚合后的完整排序元组写入下一页游标。 */
    public static String issue(CrashQueryFilter filter, CrashIssueSummary value) {
        return encode(filter, Kind.ISSUES, value.eventCount(), value.lastSeenAt(), value.fingerprint());
    }

    /** 事件页将时间和事件 ID 写入下一页游标。 */
    public static String event(CrashQueryFilter filter, CrashEventSummary value) {
        return encode(filter, Kind.EVENTS, 0, value.occurredAt(), value.eventId());
    }

    private static String encode(CrashQueryFilter filter, Kind kind, long count, Instant time, String id) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeByte(VERSION);
            output.writeByte(kind.code);
            output.writeLong(filter.from().toEpochMilli());
            output.writeLong(filter.to().toEpochMilli());
            output.writeUTF(digest(filter, kind));
            output.writeLong(count);
            output.writeLong(time.toEpochMilli());
            output.writeUTF(id);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("游标编码失败", ex);
        }
    }

    private static String digest(CrashQueryFilter filter, Kind kind) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String[] values = {kind.name(), filter.appId().toString(), filter.from().toString(),
                    filter.to().toString(), filter.appVersion(), filter.channel(), filter.environment(),
                    filter.osVersion(), filter.deviceModel(), filter.fingerprint()};
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

    private static QueryValidationException invalid() {
        return new QueryValidationException("INVALID_CURSOR", "游标无效或与当前查询不匹配，请从第一页重新查询", 400);
    }

    /** 两种列表拥有不同的排序和摘要空间。 */
    public enum Kind {
        ISSUES('I'), EVENTS('E');

        private final char code;

        Kind(char code) {
            this.code = code;
        }
    }

    /** 解码后的时间窗和排序元组。 */
    public record State(Kind kind, Instant from, Instant to, String digest,
                        long count, Instant time, String id) {
    }
}
