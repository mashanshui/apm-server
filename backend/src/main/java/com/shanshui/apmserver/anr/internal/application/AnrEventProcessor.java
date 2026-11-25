package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.anr.api.AnrEventProcessing;
import com.shanshui.apmserver.anr.api.AnrIngestCommand;
import com.shanshui.apmserver.anr.api.AnrPayload;
import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.port.AnrRepository;
import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.crash.api.DiagnosticSupport;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.telemetry.api.ApplicationBuildIdentity;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.ProcessIdentity;
import com.shanshui.apmserver.telemetry.api.SignalIngestResult;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 接收、验证并保存已判定的 ANR。 */
@Service
public class AnrEventProcessor implements AnrEventProcessing {
    /** 不记录诊断文本和凭据的接收日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrEventProcessor.class);
    /** 服务端单事件 JSON 字节上限。 */
    private static final int MAX_EVENT_BYTES = 256 * 1024;
    /** 通用诊断分区总字节上限。 */
    private static final int MAX_DIAGNOSTICS_BYTES = 96 * 1024;
    /** 合法的诊断状态。 */
    private static final Set<String> STATUSES = Set.of("ok", "partial", "unavailable", "skipped");
    /** 可安全公开的未完成原因。 */
    private static final Set<String> REASONS = Set.of("permission_denied", "unsupported", "timeout", "io_error",
            "memory_pressure", "interrupted", "size_limit", "count_limit", "not_found", "ambiguous");
    /** JSON 实际编码器。 */
    private final ObjectMapper mapper;
    /** 公共版本与时间容差。 */
    private final CrashIngestConfiguration configuration;
    /** 共用诊断校验和脱敏。 */
    private final DiagnosticSupport diagnostics;
    /** 事件存储。 */
    private final AnrRepository repository;
    /** 后台还原的非阻塞唤醒。 */
    private final AnrSymbolicationWakeup wakeup;

    /** 装配协议、诊断与事件存储。 */
    public AnrEventProcessor(ObjectMapper mapper, CrashIngestConfiguration configuration,
                             DiagnosticSupport diagnostics, AnrRepository repository, AnrSymbolicationWakeup wakeup) {
        this.mapper = mapper;
        this.configuration = configuration;
        this.diagnostics = diagnostics;
        this.repository = repository;
        this.wakeup = wakeup;
    }

    /** 确认持久化后返回公共批次结果。 */
    @Override
    public SignalIngestResult ingest(UUID appId, AnrIngestCommand command, Instant receivedAt) {
        validate(command, receivedAt);
        var payload = command.anr();
        var safePayload = new AnrPayload(payload.source(), diagnostics.sanitizeText(payload.mainThreadStack(), 24 * 1024),
                payload.mainThreadStackTruncated(), diagnostics.sanitizeText(payload.message(), 4 * 1024),
                payload.messageWhenMillis(), payload.foreground(), diagnostics.sanitizeText(payload.cgroup(), 4 * 1024),
                sanitizePartition(payload.anrInfo(), 8 * 1024), sanitizePartition(payload.trace(), 96 * 1024),
                diagnostics.sanitize(payload.diagnostics()));
        var metadata = new EventMetadata(appId, command.packageName(),
                command.eventId(), command.eventType(),
                Instant.ofEpochMilli(command.occurredAt()), receivedAt, command.schemaVersion(),
                command.sessionId(), command.processId(),
                diagnostics.hashDeviceId(command.anonymousDeviceId()), command.appVersion(),
                command.versionCode(), command.buildId(),
                diagnostics.sanitizeIdentifier(command.environment(), 64), diagnostics.sanitizeIdentifier(command.channel(), 128),
                diagnostics.sanitizeIdentifier(command.osVersion(), 64), diagnostics.sanitizeText(command.deviceModel(), 256),
                diagnostics.sanitizeIdentifier(command.networkType(), 32), diagnostics.sanitizeMeasurements(command.measurements()),
                diagnostics.sanitizeAttributes(command.attributes()));
        var result = repository.append(new AnrEvent(metadata, safePayload));
        wakeup.offer(appId, metadata.eventId());
        LOG.info("ANR 事件已处理，appId={} accepted={} duplicate={}", appId, result.accepted(), result.duplicate());
        return new SignalIngestResult(result, metadata, null);
    }

    /** 校验公共身份、来源、诊断结构和转义后的 UTF-8 字节。 */
    private void validate(AnrIngestCommand event, Instant receivedAt) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (event == null) throw new EventValidationException(List.of(issue("INVALID_EVENT", "事件不能为空")));
        if (event.schemaVersion() == null || event.schemaVersion() != configuration.getSupportedSchemaVersion()) {
            issues.add(issue("UNSUPPORTED_SCHEMA_VERSION", "不支持的事件 Schema 版本"));
        }
        require(event.eventId(), "eventId", 128, issues);
        require(event.sessionId(), "sessionId", 128, issues);
        validateStableIdentity(event.eventId(), "eventId", issues);
        validateStableIdentity(event.sessionId(), "sessionId", issues);
        require(event.anonymousDeviceId(), "anonymousDeviceId", 256, issues);
        require(event.packageName(), "packageName", 255, issues);
        ApplicationBuildIdentity.validate(event.appVersion(), event.buildId(), issues);
        require(event.environment(), "environment", 64, issues);
        require(event.channel(), "channel", 128, issues);
        require(event.osVersion(), "osVersion", 64, issues);
        require(event.deviceModel(), "deviceModel", 256, issues);
        if (!ProcessIdentity.isUuidV4(event.processId())) issues.add(issue("INVALID_PROCESS_ID", "processId 必须是 UUID v4"));
        if (event.versionCode() == null || event.versionCode() < 0) issues.add(issue("MISSING_VERSION_CODE", "versionCode 必须是非负整数"));
        if (!"anr".equals(event.eventType())) issues.add(issue("UNSUPPORTED_EVENT_TYPE", "ANR 类型不合法"));
        if (event.occurredAt() == null || event.occurredAt() < 0
                || Instant.ofEpochMilli(event.occurredAt()).isAfter(receivedAt.plus(Duration.ofMinutes(configuration.getMaxFutureSkewMinutes())))
                || Instant.ofEpochMilli(event.occurredAt()).isBefore(receivedAt.minus(Duration.ofDays(30)))) {
            issues.add(issue("INVALID_OCCURRED_AT", "事件时间超出允许范围"));
        }
        validatePayload(event.anr(), issues);
        limit(event, MAX_EVENT_BYTES, "事件", issues);
        if (!issues.isEmpty()) {
            LOG.warn("ANR 接收校验失败，问题数量={}", issues.size());
            throw new EventValidationException(issues);
        }
    }

    /** 原始事件和会话标识不能在存储前被静默改写。 */
    private void validateStableIdentity(String value, String field, List<ValidationIssue> issues) {
        if (value == null || value.isEmpty()) return;
        if (!value.equals(value.trim()) || value.codePoints().anyMatch(Character::isISOControl)) {
            issues.add(issue("INVALID_IDENTITY", field + " 不能包含边缘空白或控制字符"));
        }
    }

    /** 校验核心与各独立现场分区。 */
    private void validatePayload(AnrPayload payload, List<ValidationIssue> issues) {
        if (payload == null) {
            issues.add(issue("INVALID_ANR_PAYLOAD", "anr 载荷不能为空"));
            return;
        }
        if (!"message_timeout".equals(payload.source()) && !"process_error_state".equals(payload.source())) {
            issues.add(issue("INVALID_ANR_SOURCE", "ANR 判定来源不合法"));
        }
        if (payload.mainThreadStackTruncated() == null) issues.add(issue("INVALID_ANR_PAYLOAD", "主线程栈裁剪状态缺失"));
        limit(payload.mainThreadStack(), 24 * 1024, "主线程栈", issues);
        limit(payload.message(), 4 * 1024, "消息", issues);
        limit(payload.cgroup(), 4 * 1024, "cgroup", issues);
        validateText(payload.anrInfo(), false, 8 * 1024, issues);
        validateText(payload.trace(), true, 96 * 1024, issues);
        var value = payload.diagnostics();
        if (value != null && (value.coreTruncation() != null || value.crashedThread() != null
                || value.otherThreads() != null || value.nativeThreads() != null)) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "ANR 通用诊断包含不支持的分区"));
        }
        diagnostics.validate(value, issues);
        validateUnavailableDiagnostics(value, issues);
        limit(value, MAX_DIAGNOSTICS_BYTES, "通用诊断", issues);
    }

    /** 未采集的通用分区不能携带数值或文本现场。 */
    private void validateUnavailableDiagnostics(com.shanshui.apmserver.crash.api.CrashDiagnostics value,
                                                List<ValidationIssue> issues) {
        if (value == null) return;
        var memory = value.memory();
        if (memory != null && noContent(memory.status()) && (memory.javaHeapUsedBytes() != null
                || memory.javaHeapMaxBytes() != null || memory.javaHeapPssBytes() != null
                || memory.nativeHeapPssBytes() != null || memory.nativeHeapAllocatedBytes() != null
                || memory.totalPssBytes() != null || memory.rssBytes() != null || memory.vssBytes() != null
                || memory.swapBytes() != null || memory.meminfo() != null || memory.statusText() != null || memory.limits() != null)) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "未采集的内存分区不能携带内容"));
        }
        var files = value.openFiles();
        if (files != null && noContent(files.status()) && files.count() != null) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "未采集的文件描述符分区不能携带计数"));
        }
        var process = value.process();
        if (process != null && noContent(process.status()) && (process.pid() != null || process.name() != null
                || process.foreground() != null || process.startedAt() != null || process.sdkInitializedAt() != null)) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "未采集的进程分区不能携带内容"));
        }
        var device = value.device();
        if (device != null && noContent(device.status()) && (device.apiLevel() != null
                || device.abis() != null && !device.abis().isEmpty() || device.manufacturer() != null
                || device.brand() != null || device.buildFingerprint() != null)) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "未采集的设备分区不能携带内容"));
        }
    }

    /** 识别未采集状态。 */
    private boolean noContent(String status) {
        return "unavailable".equals(status) || "skipped".equals(status);
    }

    /** 校验分区状态、来源和内容之间的关系。 */
    private void validateText(AnrPayload.TextPartition part, boolean trace, int maxBytes, List<ValidationIssue> issues) {
        if (part == null) return;
        boolean content = part.text() != null && !part.text().isEmpty();
        if (part.status() == null || !STATUSES.contains(part.status()) || part.truncated() == null
                || (part.reason() != null && !REASONS.contains(part.reason()))
                || ("ok".equals(part.status()) && (part.reason() != null || !content || Boolean.TRUE.equals(part.truncated())))
                || (!"ok".equals(part.status()) && part.reason() == null)
                || ("partial".equals(part.status()) && !content)
                || (("unavailable".equals(part.status()) || "skipped".equals(part.status())) && content)
                || (part.capturedAt() != null && part.capturedAt() < 0)
                || (trace && part.source() != null && !"art_first_write".equals(part.source()))
                || (!trace && part.source() != null)
                || (trace && content && !"art_first_write".equals(part.source()))) {
            issues.add(issue("INVALID_ANR_DIAGNOSTICS", "ANR 文本分区状态不合法"));
        }
        limit(part, maxBytes, trace ? "系统 trace" : "ANR 信息", issues);
    }

    /** 清理分区文本并保留实际采集状态。 */
    private AnrPayload.TextPartition sanitizePartition(AnrPayload.TextPartition part, int maximum) {
        if (part == null) return null;
        return new AnrPayload.TextPartition(part.status(), part.reason(), part.truncated(), part.capturedAt(),
                diagnostics.sanitizeText(part.text(), maximum), part.source());
    }

    /** 检查必填身份字段。 */
    private void require(String value, String field, int maximum, List<ValidationIssue> issues) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            issues.add(issue("INVALID_EVENT_FIELD", field + " 缺失或超过长度上限"));
        }
    }

    /** 使用真实 JSON 序列化结果检查字节，失败时安全拒绝。 */
    private void limit(Object value, int maximum, String label, List<ValidationIssue> issues) {
        if (value == null) return;
        try {
            if (mapper.writeValueAsBytes(value).length > maximum) issues.add(issue("EVENT_TOO_LARGE", label + "超过大小上限"));
        } catch (RuntimeException failure) {
            issues.add(issue("INVALID_EVENT", label + "无法校验"));
        }
    }

    /** 创建不包含客户端输入的校验错误。 */
    private ValidationIssue issue(String code, String message) {
        return new ValidationIssue(code, message);
    }
}
