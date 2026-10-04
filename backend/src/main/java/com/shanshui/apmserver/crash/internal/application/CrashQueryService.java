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
import org.springframework.beans.factory.annotation.Autowired;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;

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
    /** 当前应用 mapping 注册表。 */
    private final SymbolRegistry symbolRegistry;

    @Autowired
    public CrashQueryService(CrashQueryPort repository,
                             QueryProperties properties,
                             StorageProperties storageProperties,
                             CrashMetrics metrics,
                             SymbolRegistry symbolRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.metrics = metrics;
        this.symbolRegistry = symbolRegistry;
    }

    /** 保留纯查询单元测试的旧构造入口，未装配符号注册表时只返回原始详情。 */
    public CrashQueryService(CrashQueryPort repository,
                             QueryProperties properties,
                             StorageProperties storageProperties,
                             CrashMetrics metrics) {
        this(repository, properties, storageProperties, metrics, null);
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

    public CrashEventDetailResponse event(java.util.UUID appId, String eventId) {
        return analysisSnapshot(appId, eventId).detail();
    }

    /** 准备任务时不启动还原，先让分析域绑定构建版本。 */
    @Override
    public CrashEventDetailResponse rawEvent(java.util.UUID appId, String eventId) {
        // 仍由 Crash 自己的端口按应用与事件过滤，不向调用方暴露存储实现。
        CrashStoredSignal event = repository.findByEventId(appId, eventId).filter(CrashStoredSignal::isCrash)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "Crash 事件不存在", 404));
        return rawOnlyDetail(event);
    }

    /** 只读取指定事件，并从同一个 mapping 租约取得还原文本与摘要。 */
    @Override
    public CrashAnalysisSnapshot analysisSnapshot(java.util.UUID appId, String eventId) {
        CrashStoredSignal event = repository.findByEventId(appId, eventId)
                .filter(CrashStoredSignal::isCrash)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "Crash 事件不存在", 404));
        SymbolFileLease lease = null;
        SymbolicationResult result = SymbolicationResult.missing();
        try {
            try {
                if (symbolRegistry == null) {
                    return new CrashAnalysisSnapshot(rawOnlyDetail(event), null);
                }
                if (event.buildId() == null || event.buildId().isBlank()) {
                    return new CrashAnalysisSnapshot(rawOnlyDetail(event), null);
                }
                var acquired = symbolRegistry.acquire(appId, event.buildId());
                if (acquired.isPresent()) {
                    lease = acquired.get();
                    result = symbolRegistry.retrace(lease, CrashStackTraceFormatter.lines(event.crash()));
                }
            } catch (SymbolStoreUnavailableException ex) {
                result = SymbolicationResult.failed("mapping_unavailable");
            } catch (RuntimeException ex) {
                result = SymbolicationResult.failed("retrace_failed");
            }
            return new CrashAnalysisSnapshot(new CrashEventDetailResponse(
                    event.appId(), event.eventId(), event.packageName(), event.occurredAt(), event.receivedAt(),
                    event.sessionId(), event.processId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(),
                    event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                    event.networkType(), event.crashExceptionType(), event.crashFingerprint(),
                    event.fingerprintVersion(), status(result), result.text(),
                    lease == null ? null : lease.symbolId(), lease == null ? null : lease.revision(),
                    result.reason(), event.crash()), lease == null ? null : lease.sha256());
        } finally {
            if (lease != null) {
                try {
                    lease.close();
                } catch (java.io.IOException ignored) {
                    // 文件读取租约只维护进程内计数，释放失败不改变详情响应。
                }
            }
        }
    }

    /** 构造未启用符号注册表时的原始详情响应。 */
    private CrashEventDetailResponse rawOnlyDetail(CrashStoredSignal event) {
        return new CrashEventDetailResponse(
                event.appId(), event.eventId(), event.packageName(), event.occurredAt(), event.receivedAt(),
                event.sessionId(), event.processId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.crashExceptionType(), event.crashFingerprint(), event.fingerprintVersion(),
                "raw_only", null, null, null, "mapping_missing", event.crash());
    }

    /** 将公共符号状态转换为 HTTP 契约使用的小写值。 */
    private String status(SymbolicationResult result) {
        return switch (result.status()) {
            case SYMBOLICATED -> "symbolicated";
            case RAW_ONLY -> "raw_only";
            case FAILED -> "failed";
        };
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
