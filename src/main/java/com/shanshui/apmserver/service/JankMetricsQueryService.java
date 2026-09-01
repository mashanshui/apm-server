package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.QueryProperties;
import com.shanshui.apmserver.config.StorageProperties;
import com.shanshui.apmserver.domain.FpsMetricAggregate;
import com.shanshui.apmserver.domain.FpsMetricStats;
import com.shanshui.apmserver.domain.FpsMetricsResponse;
import com.shanshui.apmserver.domain.MetricDimensionAggregate;
import com.shanshui.apmserver.domain.MetricDimensionPoint;
import com.shanshui.apmserver.domain.MetricDimensionsResponse;
import com.shanshui.apmserver.domain.MetricQueryFilter;
import com.shanshui.apmserver.domain.MetricTrendAggregate;
import com.shanshui.apmserver.domain.MetricTrendPoint;
import com.shanshui.apmserver.domain.MetricTrendResponse;
import com.shanshui.apmserver.domain.SuspensionMetricAggregate;
import com.shanshui.apmserver.domain.SuspensionRateResponse;
import com.shanshui.apmserver.domain.SuspensionRateStats;
import com.shanshui.apmserver.repository.EventRepository;
import com.shanshui.apmserver.repository.InMemoryJankMetricsRepository;
import com.shanshui.apmserver.repository.JankMetricsRepository;
import com.shanshui.apmserver.web.QueryParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** FPS 与设备日挂起率查询服务，统一执行查询范围、限额和维度白名单校验。 */
@Service
public class JankMetricsQueryService {

    private final JankMetricsRepository repository;
    private final QueryProperties properties;

    @Autowired
    public JankMetricsQueryService(JankMetricsRepository repository, QueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 供不启动 Spring 的固定数据集测试使用。 */
    public JankMetricsQueryService(EventRepository repository, QueryProperties properties) {
        this(new InMemoryJankMetricsRepository(repository), properties);
    }

    /** 与既有 Crash/Jank 查询服务保持一致的无 Spring 测试构造签名。 */
    public JankMetricsQueryService(EventRepository repository, QueryProperties properties,
                                   StorageProperties ignoredStorage, CrashQualityMetrics ignoredMetrics) {
        this(repository, properties);
    }

    public FpsMetricsResponse fps(java.util.UUID appId, String from, String to, QueryParams params) {
        MetricQueryFilter filter = filter(appId, from, to, params, false);
        List<FpsMetricAggregate> aggregates = repository.queryFps(filter);
        List<FpsMetricStats> metrics = aggregates.stream().map(FpsMetricStats::from).toList();
        return new FpsMetricsResponse(appId, filter.from(), filter.to(), metrics, fpsStatus(aggregates),
                repository.dataSource());
    }

    public SuspensionRateResponse suspensionRate(java.util.UUID appId, String from, String to, QueryParams params) {
        MetricQueryFilter filter = filter(appId, from, to, params, true);
        List<SuspensionMetricAggregate> aggregates = repository.querySuspension(filter);
        List<SuspensionRateStats> metrics = aggregates.stream().map(SuspensionRateStats::from).toList();
        return new SuspensionRateResponse(appId, filter.from(), filter.to(), metrics,
                suspensionStatus(aggregates), repository.dataSource());
    }

    public MetricDimensionsResponse dimensions(java.util.UUID appId, String metric, String dimension,
                                               String from, String to, QueryParams params) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedDimension = clean(dimension);
        if (normalizedDimension == null || !com.shanshui.apmserver.repository.ClickHouseJankMetricsQuerySql
                .isSupportedDimension(normalizedDimension)) {
            throw new QueryValidationException("INVALID_DIMENSION", "dimension 不在允许的白名单中", 400);
        }
        if ("suspension_rate".equals(normalizedMetric) && "scene".equals(normalizedDimension)) {
            throw new QueryValidationException("INVALID_DIMENSION", "挂起率不支持 scene 维度", 400);
        }
        MetricQueryFilter filter = filter(appId, from, to, params,
                "suspension_rate".equals(normalizedMetric));
        List<MetricDimensionAggregate> aggregates = repository.queryDimensions(filter, normalizedMetric, normalizedDimension);
        List<MetricDimensionPoint> points = aggregates.stream().map(MetricDimensionPoint::from).toList();
        return new MetricDimensionsResponse(appId, filter.from(), filter.to(), normalizedMetric, normalizedDimension,
                points, dimensionStatus(normalizedMetric, aggregates), repository.dataSource());
    }

    public MetricTrendResponse trend(java.util.UUID appId, String metric, String interval,
                                     String from, String to, QueryParams params) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedInterval = normalizeInterval(interval);
        boolean suspension = "suspension_rate".equals(normalizedMetric);
        if (suspension && "hour".equals(normalizedInterval)) {
            throw new QueryValidationException("INVALID_INTERVAL", "设备日挂起率仅支持 UTC day 粒度", 400);
        }
        MetricQueryFilter filter = filter(appId, from, to, params, suspension);
        List<MetricTrendAggregate> aggregates = suspension
                ? repository.querySuspensionTrend(filter)
                : repository.queryFpsTrend(filter, normalizedInterval);
        List<MetricTrendPoint> points = aggregates.stream()
                .map(aggregate -> MetricTrendPoint.from(normalizedMetric, aggregate))
                .toList();
        return new MetricTrendResponse(appId, filter.from(), filter.to(), normalizedMetric,
                normalizedInterval, points, trendStatus(normalizedMetric, aggregates), repository.dataSource());
    }

    private MetricQueryFilter filter(java.util.UUID appId, String fromText, String toText, QueryParams params,
                                     boolean suspension) {
        QueryParams values = params == null ? new QueryParams() : params;
        Instant to = parseInstant(toText, "to", Instant.now());
        Instant from = parseInstant(fromText, "from", to.minus(24, ChronoUnit.HOURS));
        if (!from.isBefore(to)) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "from 必须早于 to", 400);
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(properties.getMaxRangeDays())) > 0) {
            throw new QueryValidationException("TIME_RANGE_TOO_LARGE", "查询时间范围超过上限", 400);
        }
        int limit = values.getLimit() == null ? properties.getDefaultLimit() : values.getLimit();
        if (limit < 1 || limit > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 超出允许范围", 400);
        }
        long timeout = values.getTimeoutMs() == null ? properties.getDefaultTimeoutMs() : values.getTimeoutMs();
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "timeoutMs 超出允许范围", 400);
        }
        if (values.getCursor() != null && !values.getCursor().isBlank()) {
            throw new QueryValidationException("INVALID_CURSOR", "指标查询暂不支持 cursor，请使用 limit 限制分组数量", 400);
        }
        if (values.getFingerprint() != null && !values.getFingerprint().isBlank()) {
            throw new QueryValidationException("INVALID_FILTER", "指标查询不支持 fingerprint 筛选", 400);
        }
        String scene = clean(values.getScene());
        if (suspension && scene != null) {
            throw new QueryValidationException("INVALID_FILTER", "设备日挂起率不支持 scene 筛选", 400);
        }
        return new MetricQueryFilter(appId, from, to, clean(values.getAppVersion()), clean(values.getChannel()),
                clean(values.getEnvironment()), clean(values.getOsVersion()), clean(values.getDeviceModel()), scene,
                clean(values.getAlgorithmVersion()), limit, timeout);
    }

    private String normalizeMetric(String metric) {
        String value = clean(metric);
        if (!"fps".equals(value) && !"suspension_rate".equals(value)) {
            throw new QueryValidationException("INVALID_METRIC", "metric 只支持 fps 或 suspension_rate", 400);
        }
        return value;
    }

    private String normalizeInterval(String interval) {
        String value = clean(interval);
        if (!"hour".equals(value) && !"day".equals(value)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        return value;
    }

    private String fpsStatus(List<FpsMetricAggregate> values) {
        if (values.isEmpty()) {
            return "no_data";
        }
        boolean valid = values.stream().anyMatch(value -> value.validRecords() > 0);
        return valid ? "ok" : "no_valid_data";
    }

    private String suspensionStatus(List<SuspensionMetricAggregate> values) {
        if (values.isEmpty()) {
            return "no_data";
        }
        boolean valid = values.stream().anyMatch(value -> value.validDeviceDayRecords() > 0);
        return valid ? "ok" : "denominator_insufficient";
    }

    private String dimensionStatus(String metric, List<MetricDimensionAggregate> values) {
        if (values.isEmpty()) {
            return "no_data";
        }
        if ("fps".equals(metric)) {
            return values.stream().anyMatch(value -> value.validRecords() > 0) ? "ok" : "no_valid_data";
        }
        return values.stream().anyMatch(value -> value.validDeviceDayRecords() > 0)
                ? "ok" : "denominator_insufficient";
    }

    private String trendStatus(String metric, List<MetricTrendAggregate> values) {
        if (values.isEmpty()) {
            return "no_data";
        }
        if (values.stream().anyMatch(value -> value.validRecords() > 0)) {
            return "ok";
        }
        return "fps".equals(metric) ? "no_valid_data" : "denominator_insufficient";
    }

    private Instant parseInstant(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new QueryValidationException("INVALID_" + field.toUpperCase(),
                    field + " 必须是 ISO-8601 时间", 400);
        }
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > 256) {
            throw new QueryValidationException("FILTER_TOO_LONG", "查询筛选条件过长", 400);
        }
        return result;
    }
}
