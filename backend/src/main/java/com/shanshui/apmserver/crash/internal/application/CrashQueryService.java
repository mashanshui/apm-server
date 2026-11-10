package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashMetrics;
import com.shanshui.apmserver.crash.api.CrashQueries;
import com.shanshui.apmserver.crash.api.CrashAnalysisSnapshot;
import com.shanshui.apmserver.platform.api.QueryParams;
import com.shanshui.apmserver.platform.api.QueryValidationException;

import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashEventListResponse;
import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.api.CrashIssueResponse;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.api.CrashOverviewResponse;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
import com.shanshui.apmserver.crash.api.CrashStats;
import com.shanshui.apmserver.crash.api.CrashTrendPoint;
import com.shanshui.apmserver.crash.api.CrashTrendResponse;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashQueryPort;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

@Service
public class CrashQueryService implements CrashQueries {

    private final CrashQueryPort repository;
    private final QueryProperties properties;
    private final StorageProperties storageProperties;
    private final CrashMetrics metrics;
    /** 只读取已发布状态，详情请求不持有任何符号文件租约。 */
    public CrashQueryService(CrashQueryPort repository, QueryProperties properties,
                             StorageProperties storageProperties, CrashMetrics metrics) {
        this.repository = repository;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.metrics = metrics;
    }

    public CrashOverviewResponse overview(java.util.UUID appId, String from, String to, CrashQueryCommand params) {
        CrashQueryFilter filter = filter(appId, from, to, params);
        CrashStats stats = repository.overview(filter);
        if (stats.startedSessions() == 0) metrics.denominatorInsufficient();
        return new CrashOverviewResponse(appId, filter.from(), filter.to(), stats, dataSource());
    }

    /** 公共查询契约只暴露平台参数，不泄露 Crash 内部命令类型。 */
    @Override
    public CrashOverviewResponse overview(java.util.UUID appId, String from, String to, QueryParams params) {
        return overview(appId, from, to, command(params));
    }

    @Override
    public CrashTrendResponse trend(java.util.UUID appId, String from, String to, String interval, QueryParams params) {
        return trend(appId, from, to, interval, command(params));
    }

    @Override
    public CrashIssueResponse issues(java.util.UUID appId, String from, String to, QueryParams params) {
        return issues(appId, from, to, command(params));
    }

    @Override
    public CrashEventListResponse events(java.util.UUID appId, String fingerprint,
                                         String from, String to, QueryParams params) {
        return events(appId, fingerprint, from, to, command(params));
    }

    /** 将共享 HTTP 参数转换为本域不可变命令。 */
    private CrashQueryCommand command(QueryParams params) {
        return new CrashQueryCommand(params.getAppVersion(), params.getChannel(), params.getEnvironment(),
                params.getOsVersion(), params.getDeviceModel(), params.getFingerprint(), params.getLimit(),
                params.getCursor(), params.getTimeoutMs());
    }

    public CrashTrendResponse trend(java.util.UUID appId, String from, String to, String interval, CrashQueryCommand params) {
        if (!"hour".equals(interval) && !"day".equals(interval)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        CrashQueryFilter filter = filter(appId, from, to, params);
        List<CrashTrendPoint> points = repository.trend(filter, interval);
        points.stream().filter(point -> point.stats().startedSessions() == 0)
                .forEach(ignored -> metrics.denominatorInsufficient());
        return new CrashTrendResponse(appId, filter.from(), filter.to(), interval, points, dataSource());
    }

    public CrashIssueResponse issues(java.util.UUID appId, String from, String to, CrashQueryCommand params) {
        CrashQueryFilter filter = filter(appId, from, to, params, CrashCursor.Kind.ISSUES);
        var page = repository.issues(filter);
        return new CrashIssueResponse(appId, filter.from(), filter.to(), page.items(), page.nextCursor(), dataSource());
    }

    public CrashEventListResponse events(java.util.UUID appId, String fingerprint,
                                         String from, String to, CrashQueryCommand params) {
        CrashQueryCommand effective = params == null ? CrashQueryCommand.empty() : params;
        effective = effective.withFingerprint(fingerprint);
        CrashQueryFilter filter = filter(appId, from, to, effective, CrashCursor.Kind.EVENTS);
        var page = repository.events(filter, fingerprint);
        return new CrashEventListResponse(appId, fingerprint, filter.from(), filter.to(),
                page.items(), page.nextCursor(), dataSource());
    }

    /** 按事件身份读取单份已发布材料，不执行 R8。 */
    public CrashEventDetailResponse event(java.util.UUID appId, String eventId) {
        // 事实与状态在存储侧关联，孤立状态和旧表事件不会形成详情。
        var event = repository.findByEventId(appId, eventId).filter(CrashStoredSignal::isCrash)
                .map(com.shanshui.apmserver.crash.internal.domain.CrashEvent.class::cast)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "Crash 事件不存在", 404));
        // 从同一状态行取得所有当前字段，不能组合不同版本的堆栈和指纹。
        var state = event.state();
        var stackMapper = new tools.jackson.databind.ObjectMapper();
        Object content;
        try {
            content = switch (state.stackFormat()) {
                case "retrace_text" -> state.stackPayload();
                case "raw_chain" -> stackMapper.readValue(state.stackPayload(), com.shanshui.apmserver.crash.api.CrashPayload.class);
                case "raw_native" -> stackMapper.readerFor(com.shanshui.apmserver.crash.api.NativeCrash.class)
                        .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(state.stackPayload());
                default -> throw new IllegalStateException("未知栈格式");
            };
        } catch (RuntimeException invalid) {
            // 损坏或未知字段不得伪装成有效原始现场，也不在日志中回显材料。
            throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("Crash 原始栈不完整");
        }
        if ("native".equals(event.crashKind()) != "raw_native".equals(state.stackFormat())) {
            throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("Crash 类型与栈格式不一致");
        }
        return new CrashEventDetailResponse(event.appId(), event.eventId(), event.packageName(), event.occurredAt(), event.receivedAt(),
                event.sessionId(), event.processId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(), event.buildId(),
                event.channel(), event.environment(), event.osVersion(), event.deviceModel(), event.networkType(), state.exceptionType(),
                state.fingerprint(), state.fingerprintVersion(), state.status(), new com.shanshui.apmserver.crash.api.CrashStack(state.stackFormat(), content),
                state.symbolId(), state.symbolRevision(), state.reason(), event.crashKind(), event.fatal(), state.groupingKind(), state.symbolSha256(), state.processedAt(),
                diagnostics(event.crashKind(), event.diagnosticsPayload(), state.threadStacksPayload()));
    }

    /** 按 Java ID 组合不可变线程元数据和同一状态版本的当前堆栈。 */
    private com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse diagnostics(String kind, String payload, String stackPayload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var value = mapper.readValue(payload, com.shanshui.apmserver.crash.api.CrashDiagnostics.class);
            var threads = value.otherThreads();
            var nativeThreads = value.nativeThreads();
            var javaStates = new java.util.ArrayList<com.shanshui.apmserver.crash.api.CrashThreadStack>();
            var nativeStates = new java.util.ArrayList<com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks.NativeThreadStack>();
            if (stackPayload != null && !stackPayload.isEmpty()) {
                if ("native".equals(kind)) {
                    com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks stored = mapper
                            .readerFor(com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks.class)
                            .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(stackPayload);
                    if (stored.javaThreads() == null || stored.nativeThreads() == null) throw new IllegalStateException("辅助线程状态缺失");
                    javaStates.addAll(stored.javaThreads());
                    nativeStates.addAll(stored.nativeThreads());
                } else {
                    javaStates.addAll(java.util.Arrays.asList(mapper.readValue(stackPayload,
                            com.shanshui.apmserver.crash.api.CrashThreadStack[].class)));
                }
            }
            com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse.OtherThreads combined = null;
            if (threads != null) {
                if (stackPayload == null || stackPayload.isEmpty() || threads.threads() == null) {
                    throw new IllegalStateException("线程诊断不完整");
                }
                var byId = new java.util.LinkedHashMap<String, com.shanshui.apmserver.crash.api.CrashThreadStack>();
                for (var state : javaStates) if (byId.put(state.javaId(), state) != null) throw new IllegalStateException("线程身份重复");
                var results = new java.util.ArrayList<com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse.OtherThread>();
                for (var thread : threads.threads()) {
                    var state = byId.remove(thread.javaId());
                    if (state == null) throw new IllegalStateException("线程状态缺失");
                    results.add(new com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse.OtherThread(thread.javaId(),
                            thread.name(), thread.state(), thread.priority(), thread.daemon(), thread.mainThread(), thread.truncated(),
                            state.stack(), state.symbolicationStatus(), state.symbolicationReason(), state.symbolFileId(),
                            state.symbolFileRevision(), state.symbolFileSha256(), state.processedAt(),
                            thread.systemTid(), thread.relationToCrash()));
                }
                if (!byId.isEmpty()) throw new IllegalStateException("多余线程状态");
                combined = new com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse.OtherThreads(threads.status(),
                        threads.reason(), threads.truncated(), threads.capturedAt(), threads.observedCount(), threads.omittedCount(), results);
            } else if (!javaStates.isEmpty()) {
                throw new IllegalStateException("缺少线程元数据");
            }
            com.shanshui.apmserver.crash.api.CrashDiagnostics.NativeThreads combinedNative = null;
            if (nativeThreads != null) {
                if (!"native".equals(kind) || stackPayload == null || stackPayload.isEmpty() || nativeThreads.threads() == null) {
                    throw new IllegalStateException("Native 线程诊断不完整");
                }
                var byTid = new java.util.LinkedHashMap<Integer, com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks.NativeThreadStack>();
                for (var state : nativeStates) if (state.systemTid() == null || byTid.put(state.systemTid(), state) != null) {
                    throw new IllegalStateException("Native 线程身份重复");
                }
                var results = new java.util.ArrayList<com.shanshui.apmserver.crash.api.CrashDiagnostics.NativeThread>();
                for (var thread : nativeThreads.threads()) {
                    var state = byTid.remove(thread.systemTid());
                    if (state == null || state.backtrace() == null) throw new IllegalStateException("Native 线程状态缺失");
                    results.add(new com.shanshui.apmserver.crash.api.CrashDiagnostics.NativeThread(thread.systemTid(),
                            thread.name(), thread.mainThread(), state.backtrace()));
                }
                if (!byTid.isEmpty()) throw new IllegalStateException("多余 Native 线程状态");
                combinedNative = new com.shanshui.apmserver.crash.api.CrashDiagnostics.NativeThreads(nativeThreads.status(),
                        nativeThreads.reason(), nativeThreads.truncated(), nativeThreads.capturedAt(),
                        nativeThreads.observedCount(), nativeThreads.omittedCount(), results);
            } else if (!nativeStates.isEmpty()) {
                throw new IllegalStateException("缺少 Native 线程元数据");
            }
            return new com.shanshui.apmserver.crash.api.CrashDiagnosticsResponse(value.coreTruncation(), value.crashedThread(),
                    combined, value.logcat(), value.memory(), value.openFiles(), value.process(), value.device(), combinedNative);
        } catch (RuntimeException invalid) {
            // 不输出原始诊断，损坏状态作为存储不可用处理。
            throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("Crash 诊断状态不完整");
        }
    }

    /** 分析证据只冻结当前查询结果，不重新查找当前 mapping。 */
    @Override
    public CrashAnalysisSnapshot analysisSnapshot(java.util.UUID appId, String eventId) {
        return new CrashAnalysisSnapshot(event(appId, eventId));
    }

    public CrashQueryFilter filter(java.util.UUID appId, String fromText, String toText, CrashQueryCommand params) {
        return filter(appId, fromText, toText, params, null);
    }

    /** 列表请求的后续页沿用首次绝对时间窗，并重新绑定全部筛选。 */
    private CrashQueryFilter filter(java.util.UUID appId, String fromText, String toText,
                                    CrashQueryCommand params, CrashCursor.Kind cursorKind) {
        CrashQueryCommand values = params == null ? CrashQueryCommand.empty() : params;
        String cursor = values.cursor() == null || values.cursor().isBlank() ? null : values.cursor().trim();
        if (cursor != null && cursorKind == null) {
            throw new QueryValidationException("INVALID_CURSOR", "此查询不支持游标", 400);
        }
        CrashCursor.State cursorState = cursor == null ? null : CrashCursor.decode(cursor, cursorKind);
        Instant to = parseInstant(toText, "to", cursorState == null ? Instant.now() : cursorState.to())
                .truncatedTo(ChronoUnit.MILLIS);
        Instant from = parseInstant(fromText, "from", cursorState == null ? to.minus(24, ChronoUnit.HOURS)
                : cursorState.from()).truncatedTo(ChronoUnit.MILLIS);
        if (!from.isBefore(to)) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "from 必须早于 to", 400);
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(properties.getMaxRangeDays())) > 0) {
            throw new QueryValidationException("TIME_RANGE_TOO_LARGE", "查询时间范围超过上限", 400);
        }
        int limit = values.limit() == null ? properties.getDefaultLimit() : values.limit();
        if (limit < 1 || limit > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 超出允许范围", 400);
        }
        long timeout = values.timeoutMs() == null ? properties.getDefaultTimeoutMs() : values.timeoutMs();
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "timeoutMs 超出允许范围", 400);
        }
        CrashQueryFilter filter = new CrashQueryFilter(appId, from, to,
                clean(values.appVersion()), clean(values.channel()), clean(values.environment()),
                clean(values.osVersion()), clean(values.deviceModel()), clean(values.fingerprint()),
                limit, cursor, timeout);
        if (cursorState != null) CrashCursor.validate(cursor, cursorKind, filter);
        return filter;
    }

    private Instant parseInstant(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new QueryValidationException("INVALID_" + field.toUpperCase(), field + " 必须是 ISO-8601 时间", 400);
        }
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 256) {
            throw new QueryValidationException("FILTER_TOO_LONG", "查询筛选条件过长", 400);
        }
        return value.trim();
    }

    private String dataSource() {
        return storageProperties.getMode();
    }
}
