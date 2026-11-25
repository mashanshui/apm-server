package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.anr.api.AnrEventDetailResponse;
import com.shanshui.apmserver.anr.api.AnrEventListResponse;
import com.shanshui.apmserver.anr.api.AnrEventSummary;
import com.shanshui.apmserver.anr.api.AnrOverviewResponse;
import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.domain.AnrQueryFilter;
import com.shanshui.apmserver.anr.internal.port.AnrRepository;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** 规范化 ANR 查询，并将过滤、统计及分页交给存储。 */
@Service
public class AnrQueryService {
    /** 不包含诊断正文的查询日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrQueryService.class);
    /** 存储查询端口。 */
    private final AnrRepository repository;
    /** 平台统一资源预算。 */
    private final QueryProperties properties;
    /** 游标 JSON 编码器。 */
    private final ObjectMapper mapper;
    /** 原始事实之外的分区还原状态。 */
    private final AnrSymbolicationStore symbolication;

    /** 装配存储、查询预算和游标编码器。 */
    public AnrQueryService(AnrRepository repository, QueryProperties properties, ObjectMapper mapper,
                           AnrSymbolicationStore symbolication) {
        this.repository = repository;
        this.properties = properties;
        this.mapper = mapper;
        this.symbolication = symbolication;
    }

    /** 按筛选获取事件与设备计数。 */
    public AnrOverviewResponse overview(UUID appId, String from, String to, String appVersion, String source) {
        var filter = filter(appId, from, to, appVersion, source, null, null, null);
        var counts = repository.overview(filter);
        LOG.debug("ANR 概览查询完成，appId={} eventCount={}", appId, counts.eventCount());
        return new AnrOverviewResponse(appId, filter.from(), filter.to(), counts.eventCount(), counts.deviceCount());
    }

    /** 获取稳定排序的一页摘要，并绑定下一页游标。 */
    public AnrEventListResponse events(UUID appId, String from, String to, String appVersion, String source,
                                       Integer limit, String cursor, Long timeoutMs) {
        var filter = filter(appId, from, to, appVersion, source, limit, cursor, timeoutMs);
        var rows = repository.list(filter);
        boolean hasMore = rows.size() > filter.limit();
        var page = hasMore ? rows.subList(0, filter.limit()) : rows;
        String next = hasMore ? encode(filter, page.getLast()) : null;
        LOG.debug("ANR 列表查询完成，appId={} count={} hasMore={}", appId, page.size(), hasMore);
        return new AnrEventListResponse(appId, filter.from(), filter.to(), java.util.List.copyOf(page), next);
    }

    /** 单独读取事件的原始身份和现场。 */
    public AnrEventDetailResponse event(UUID appId, String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 128) {
            throw new QueryValidationException("INVALID_EVENT_ID", "事件标识无效", 400);
        }
        var event = repository.find(appId, eventId)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "ANR 事件不存在", 404));
        LOG.debug("ANR 详情查询完成，appId={}", appId);
        var main = symbolication.find(appId, eventId, "main").orElseGet(() -> initial(event.payload().mainThreadStack()));
        var trace = symbolication.find(appId, eventId, "trace").orElseGet(() ->
                initial(event.payload().trace() == null ? null : event.payload().trace().text()));
        return new AnrEventDetailResponse(event.metadata(), event.payload(), main, trace);
    }

    /** 尚无后台记录时保留可查询原文和真实等待状态。 */
    private AnrJavaView initial(String text) {
        boolean applicable = text != null && !text.isBlank();
        return new AnrJavaView(applicable ? "pending" : "not_applicable", applicable ? "not_processed" : null,
                null, null, null, null, null, null);
    }

    /** 恢复游标默认时间并校验全部筛选、分页和资源边界。 */
    private AnrQueryFilter filter(UUID appId, String fromText, String toText, String versionText, String sourceText,
                                  Integer limit, String encoded, Long timeoutMs) {
        Cursor cursor = encoded == null || encoded.isBlank() ? null : decode(encoded);
        Instant to = parseTime(toText, "to", cursor == null ? Instant.now() : cursor.to()).truncatedTo(ChronoUnit.MILLIS);
        Instant from = parseTime(fromText, "from", cursor == null ? to.minus(24, ChronoUnit.HOURS) : cursor.from())
                .truncatedTo(ChronoUnit.MILLIS);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "时间范围必须大于零且不超过 30 天", 400);
        }
        String version = clean(versionText, 128);
        String source = clean(sourceText, 32);
        if (source != null && !"message_timeout".equals(source) && !"process_error_state".equals(source)) {
            throw new QueryValidationException("INVALID_ANR_SOURCE", "ANR 判定来源无效", 400);
        }
        int pageSize = limit == null ? properties.getDefaultLimit() : limit;
        if (pageSize < 1 || pageSize > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "分页数量超出允许范围", 400);
        }
        long timeout = timeoutMs == null ? properties.getDefaultTimeoutMs() : timeoutMs;
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "查询超时超出允许范围", 400);
        }
        if (cursor != null && (!appId.equals(cursor.appId()) || !from.equals(cursor.from()) || !to.equals(cursor.to())
                || !Objects.equals(version, cursor.appVersion()) || !Objects.equals(source, cursor.source())
                || cursor.time() == null || cursor.time().isBefore(from) || !cursor.time().isBefore(to)
                || cursor.eventId() == null || cursor.eventId().isBlank() || cursor.eventId().length() > 128)) {
            throw invalidCursor();
        }
        return new AnrQueryFilter(appId, from, to, version, source, pageSize,
                cursor == null ? null : cursor.time(), cursor == null ? null : cursor.eventId(), timeout);
    }

    /** 将上页末尾和所有筛选绑定到游标。 */
    private String encode(AnrQueryFilter filter, AnrEventSummary event) {
        var cursor = new Cursor(1, filter.appId(), filter.from(), filter.to(), filter.appVersion(), filter.source(),
                event.occurredAt(), event.eventId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(cursor));
    }

    /** 解码受限长度的游标，并拒绝未知版本。 */
    private Cursor decode(String value) {
        if (value.length() > 2048) throw invalidCursor();
        try {
            Cursor cursor = mapper.readValue(Base64.getUrlDecoder().decode(value), Cursor.class);
            if (cursor == null || cursor.version() != 1 || cursor.appId() == null || cursor.from() == null || cursor.to() == null) {
                throw invalidCursor();
            }
            return cursor;
        } catch (RuntimeException failure) {
            throw invalidCursor();
        }
    }

    /** 解析带时区的 ISO 时间。 */
    private Instant parseTime(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException failure) {
            throw new QueryValidationException("INVALID_TIME", field + " 必须是 ISO-8601 时间", 400);
        }
    }

    /** 去除空白筛选并限制长度。 */
    private String clean(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maximum) throw new QueryValidationException("FILTER_TOO_LONG", "查询筛选过长", 400);
        return result;
    }

    /** 创建统一的无效游标错误。 */
    private QueryValidationException invalidCursor() {
        return new QueryValidationException("INVALID_CURSOR", "游标无效或与当前筛选不匹配", 400);
    }

    /** 绑定应用、时间、筛选和排序元组的游标。 */
    private record Cursor(int version, UUID appId, Instant from, Instant to, String appVersion,
                          String source, Instant time, String eventId) { }
}
