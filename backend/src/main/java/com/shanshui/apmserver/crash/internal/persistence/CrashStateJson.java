package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 当前状态的确定 JSONEachRow 编码，数据库发布重放不得重新计算材料。 */
@Component
public class CrashStateJson {
    /** UTC 毫秒编码。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 当前应用 JSON 配置。 */
    private final ObjectMapper mapper;
    /** 注入固定编解码器。 */
    public CrashStateJson(ObjectMapper mapper) { this.mapper = mapper; }

    /** 校验 Native 快照沿同一采集身份单调演进。 */
    void validateNativeTransition(CrashState base, CrashState candidate) {
        new NativeStateTransition(mapper).validate(base, candidate);
        encode(candidate);
    }

    /** 为跨库发布登记提供确定的同字节载荷。 */
    public String encode(CrashState state) {
        if ("native_stack".equals(state.stackFormat())) {
            validateNativePayload(state.stackPayload(), state.threadStacksPayload(), state.status(), state.reason());
        }
        // 插入顺序固定，发布器重试直接使用登记的原始 JSON 字节。
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", state.appId());
        row.put("event_id", state.eventId());
        row.put("build_id", state.buildId());
        row.put("event_time", time(state.eventTime()));
        row.put("state_version", state.stateVersion());
        row.put("symbolication_status", state.status());
        row.put("symbolication_reason", empty(state.reason()));
        row.put("stack_format", state.stackFormat());
        row.put("stack_payload", state.stackPayload());
        row.put("exception_type", empty(state.exceptionType()));
        row.put("fingerprint", state.fingerprint());
        row.put("fingerprint_version", state.fingerprintVersion());
        row.put("grouping_kind", state.groupingKind());
        row.put("symbol_id", state.symbolId());
        row.put("symbol_revision", state.symbolRevision());
        row.put("symbol_sha256", empty(state.symbolSha256()));
        row.put("processed_at", state.processedAt() == null ? null : time(state.processedAt()));
        row.put("retry_at", state.retryAt() == null ? null : time(state.retryAt()));
        row.put("capture_sha256", state.captureSha256());
        row.put("thread_stacks_payload", state.threadStacksPayload());
        row.put("native_work_remaining", state.nativeWorkRemaining() ? 1 : 0);
        return mapper.writeValueAsString(row);
    }

    /** 强制使用当前模型构造校验，不吞掉非法状态。 */
    public CrashState decode(JsonNode row) {
        if (row.has("native_work_remaining") && (!row.path("native_work_remaining").isIntegralNumber()
                || row.path("native_work_remaining").asInt() < 0 || row.path("native_work_remaining").asInt() > 1)) {
            throw new IllegalArgumentException("Native 剩余工作标记不合法");
        }
        // Native 结构化材料必须通过严格 DTO 校验，未知字段不能伪装成有效状态。
        if ("native_stack".equals(row.path("stack_format").asText())) {
            if (!row.has("native_work_remaining")) throw new IllegalArgumentException("Native 剩余工作标记缺失");
            validateNativePayload(row.path("stack_payload").asText(), row.path("thread_stacks_payload").asText(""),
                    row.path("symbolication_status").asText(), row.path("symbolication_reason").asText());
        }
        return new CrashState(UUID.fromString(row.path("app_id").asText()), row.path("event_id").asText(),
                row.path("build_id").asText(), parseTime(row.path("event_time").asText()), row.path("state_version").asLong(),
                row.path("symbolication_status").asText(), nullableText(row, "symbolication_reason"),
                row.path("stack_format").asText(), row.path("stack_payload").asText(), nullableText(row, "exception_type"),
                row.path("fingerprint").asText(), row.path("fingerprint_version").asText(), row.path("grouping_kind").asText(),
                row.path("symbol_id").isNull() ? null : UUID.fromString(row.path("symbol_id").asText()),
                row.path("symbol_revision").isNull() ? null : row.path("symbol_revision").asInt(),
                nullableText(row, "symbol_sha256"), nullableTime(row, "processed_at"), nullableTime(row, "retry_at"),
                row.path("capture_sha256").asText(""), row.path("thread_stacks_payload").asText(""),
                row.has("native_work_remaining") ? row.path("native_work_remaining").asInt() == 1
                        : "raw_native".equals(row.path("stack_format").asText()));
    }

    /** 写入和读取均校验同一完整 Native 状态，避免内存适配器放行损坏数据。 */
    private void validateNativePayload(String payload, String auxiliary, String status, String reason) {
        com.shanshui.apmserver.crash.api.NativeStack nativeStack = mapper.readerFor(com.shanshui.apmserver.crash.api.NativeStack.class)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload);
        validateNativeSummary(nativeStack, status, reason);
        if (auxiliary != null && !auxiliary.isEmpty()) {
            com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks stacks = mapper
                    .readerFor(com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(auxiliary);
            for (var thread : stacks.nativeThreads()) {
                com.shanshui.apmserver.crash.api.NativeStack.validateFrames(nativeStack.modules(), thread.backtrace());
            }
        }
    }

    /** 摘要只反映主物理帧；空栈和临时失败不能伪装成成功。 */
    private static void validateNativeSummary(com.shanshui.apmserver.crash.api.NativeStack stack, String status, String reason) {
        var frames = stack.backtrace().frames();
        if (frames.isEmpty()) {
            if (!"pending".equals(status) || !"capture_stack_unavailable".equals(reason)) {
                throw new IllegalArgumentException("空 Native 主栈摘要无效");
            }
            return;
        }
        boolean allSuccess = frames.stream().allMatch(frame -> "symbolicated".equals(frame.resolution().status()));
        boolean waiting = frames.stream().anyMatch(frame -> "pending".equals(frame.resolution().status())
                || frame.resolution().retryAt() != null && !"symbolicated".equals(frame.resolution().status()));
        String expected = allSuccess ? "symbolicated" : waiting ? "pending" : "failed";
        if (!expected.equals(status)) throw new IllegalArgumentException("Native 主栈摘要与逐帧状态不一致");
    }

    /** 旧在途登记缺少新列时，按数据库默认空值比较全部状态，保留原始登记字节。 */
    public boolean matches(String payload, CrashState current) {
        return decode(mapper.readTree(payload)).equals(current);
    }

    /** 固定格式解析，保留毫秒身份。 */
    private static Instant parseTime(String value) {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")).toInstant(ZoneOffset.UTC);
    }

    /** 可空时间只从有效数据库列读取。 */
    private static Instant nullableTime(JsonNode row, String field) {
        return row.path(field).isNull() ? null : parseTime(row.path(field).asText());
    }

    /** 空原因与空来源摘要统一为缺失值。 */
    private static String nullableText(JsonNode row, String field) {
        return row.path(field).isNull() || row.path(field).asText().isEmpty() ? null : row.path(field).asText();
    }

    /** SQL 日期只接受服务端时间类型。 */
    private static String date(Instant value) { return "toDateTime64(" + quote(time(value)) + ",3,'UTC')"; }
    /** SQL 字面量统一转义，用户输入不能充当标识符。 */
    private static String quote(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    /** 毫秒 UTC 编码。 */
    private static String time(Instant value) { return TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC)); }
    /** ClickHouse 非空字符串列的缺失值。 */
    private static String empty(String value) { return value == null ? "" : value; }
}
