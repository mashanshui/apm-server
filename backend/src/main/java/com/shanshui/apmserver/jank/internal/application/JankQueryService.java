package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.platform.api.QueryValidationException;

import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.QueryParams;
import com.shanshui.apmserver.jank.api.JankQueries;
import com.shanshui.apmserver.jank.api.JankEventDetailResponse;
import com.shanshui.apmserver.jank.api.JankEventListResponse;
import com.shanshui.apmserver.jank.api.JankEventSummary;
import com.shanshui.apmserver.jank.api.JankIssueResponse;
import com.shanshui.apmserver.jank.api.JankIssueSummary;
import com.shanshui.apmserver.jank.api.JankOverviewResponse;
import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.api.JankTrendPoint;
import com.shanshui.apmserver.jank.api.JankTrendResponse;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.jank.internal.domain.JankQueryCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class JankQueryService implements JankQueries {

    private final JankAggregationRepository repository;
    private final QueryProperties properties;

    @Autowired
    public JankQueryService(JankAggregationRepository repository, QueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public JankOverviewResponse overview(java.util.UUID appId, String from, String to, JankQueryCommand params) {
        JankQueryFilter filter = filter(appId, from, to, params);
        rejectCursor(filter);
        return new JankOverviewResponse(appId, filter.from(), filter.to(), repository.overview(filter),
                repository.dataSource());
    }

    /** 公共 HTTP 查询参数在域内转换，供网页和 Agent 共用业务口径。 */
    @Override
    public JankOverviewResponse overview(java.util.UUID appId, String from, String to, QueryParams params) {
        return overview(appId, from, to, command(params));
    }

    @Override
    public JankTrendResponse trend(java.util.UUID appId, String from, String to, String interval, QueryParams params) {
        return trend(appId, from, to, interval, command(params));
    }

    @Override
    public JankIssueResponse issues(java.util.UUID appId, String from, String to, QueryParams params) {
        return issues(appId, from, to, command(params));
    }

    @Override
    public JankEventListResponse events(java.util.UUID appId, String fingerprint,
                                       String from, String to, QueryParams params) {
        return events(appId, fingerprint, from, to, command(params));
    }

    private JankQueryCommand command(QueryParams params) {
        return new JankQueryCommand(params.getAppVersion(), params.getChannel(), params.getEnvironment(),
                params.getOsVersion(), params.getDeviceModel(), params.getFingerprint(), params.getScene(),
                params.getAlgorithmVersion(), params.getLimit(), params.getCursor(), params.getTimeoutMs());
    }

    public JankTrendResponse trend(java.util.UUID appId, String from, String to, String interval, JankQueryCommand params) {
        if (!"hour".equals(interval) && !"day".equals(interval)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        JankQueryFilter filter = filter(appId, from, to, params);
        rejectCursor(filter);
        // 空数据状态与列表入口保持一致。
        List<JankTrendPoint> points = repository.trend(filter, interval);
        return new JankTrendResponse(appId, filter.from(), filter.to(), interval,
                points, points.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankIssueResponse issues(java.util.UUID appId, String from, String to, JankQueryCommand params) {
        JankQueryFilter filter = filter(appId, from, to, params, JankCursor.Kind.ISSUES);
        // 仓储只返回最终有序的 limit+1 聚合行。
        List<JankIssueSummary> rows = repository.issues(filter,
                filter.cursor() == null ? null : JankCursor.validate(filter.cursor(), JankCursor.Kind.ISSUES, filter));
        boolean more = rows.size() > filter.limit();
        List<JankIssueSummary> page = rows.subList(0, Math.min(rows.size(), filter.limit()));
        String nextCursor = more ? JankCursor.issue(filter, page.getLast()) : null;
        return new JankIssueResponse(appId, filter.from(), filter.to(), page, nextCursor,
                page.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankEventListResponse events(java.util.UUID appId, String fingerprint,
                                        String from, String to, JankQueryCommand params) {
        JankQueryCommand effective = params == null ? JankQueryCommand.empty() : params;
        effective = effective.withFingerprint(fingerprint);
        JankQueryFilter filter = filter(appId, from, to, effective, JankCursor.Kind.EVENTS);
        // 摘要独立于载荷详情，仅取有界标量页。
        List<JankEventSummary> rows = repository.events(filter,
                filter.cursor() == null ? null : JankCursor.validate(filter.cursor(), JankCursor.Kind.EVENTS, filter));
        boolean more = rows.size() > filter.limit();
        List<JankEventSummary> page = rows.subList(0, Math.min(rows.size(), filter.limit()));
        String nextCursor = more ? JankCursor.event(filter, page.getLast()) : null;
        return new JankEventListResponse(appId, fingerprint, filter.from(), filter.to(), page, nextCursor,
                page.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankEventDetailResponse event(java.util.UUID appId, String eventId) {
        JankEvent event = repository.findByEventId(appId, eventId)
                .filter(JankEvent::isJank)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "卡顿事件不存在", 404));
        return new JankEventDetailResponse(event.appId(), event.eventId(), event.packageName(), event.occurredAt(),
                event.receivedAt(), event.sessionId(), event.processId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.jank().scene(), event.jank().algorithmVersion(), event.crashFingerprint(),
                event.fingerprintVersion(), event.jank(), event.jankAnalysis());
    }

    public JankQueryFilter filter(java.util.UUID appId, String fromText, String toText, JankQueryCommand params) {
        return filter(appId, fromText, toText, params, null);
    }

    /** 列表续页恢复原绝对时间窗，并在规范化之后验证完整筛选摘要。 */
    private JankQueryFilter filter(java.util.UUID appId, String fromText, String toText,
                                   JankQueryCommand params, JankCursor.Kind kind) {
        JankQueryCommand values = params == null ? JankQueryCommand.empty() : params;
        // 只有列表可以携带游标；解码后依然执行普通筛选与范围校验。
        JankCursor.State cursor = values.cursor() == null ? null
                : kind == null ? invalidCursor() : JankCursor.decode(values.cursor(), kind);
        Instant to = parseInstant(toText, "to", cursor == null ? Instant.now() : cursor.to());
        Instant from = parseInstant(fromText, "from", cursor == null ? to.minus(24, ChronoUnit.HOURS) : cursor.from());
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
        JankQueryFilter result = new JankQueryFilter(appId, from, to, clean(values.appVersion()), clean(values.channel()),
                clean(values.environment()), clean(values.osVersion()), clean(values.deviceModel()),
                clean(values.scene()), clean(values.algorithmVersion()), clean(values.fingerprint()),
                limit, values.cursor(), timeout);
        if (cursor != null) JankCursor.validate(values.cursor(), kind, result);
        return result;
    }

    /** 非列表入口拒绝游标，不能忽略输入后返回部分或第一页。 */
    private void rejectCursor(JankQueryFilter filter) {
        if (filter.cursor() != null) invalidCursor();
    }

    /** 统一失败码供网页重新查询。 */
    private JankCursor.State invalidCursor() {
        throw new QueryValidationException("INVALID_CURSOR", "该入口不接受游标，请从第一页重新查询", 400);
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
}
