package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.api.FpsMetricAggregate;
import com.shanshui.apmserver.jank.api.MetricDimensionAggregate;
import com.shanshui.apmserver.jank.api.MetricTrendAggregate;
import com.shanshui.apmserver.jank.api.SuspensionMetricAggregate;
import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;
import com.shanshui.apmserver.jank.internal.port.JankMetricsRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** FPS、挂起率、趋势和多维查询专属 ClickHouse 适配器。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseJankMetricsRepository implements JankMetricsRepository {

    private final ClickHouseHttpClient client;
    private final ObjectMapper objectMapper;

    public ClickHouseJankMetricsRepository(ClickHouseHttpClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FpsMetricAggregate> queryFps(MetricQueryFilter filter) {
        List<FpsMetricAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(ClickHouseJankMetricsQuerySql.selectFps(filter))) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_records").asLong();
            result.add(new FpsMetricAggregate(text(row, "algorithm_version"), total, valid,
                    value(row, "average_fps", valid), value(row, "p50_fps", valid),
                    value(row, "p90_fps", valid), value(row, "p99_fps", valid),
                    valid == 0 ? (total == 0 ? "no_data" : "no_valid_data") : "ok"));
        }
        return List.copyOf(result);
    }

    @Override
    public List<SuspensionMetricAggregate> querySuspension(MetricQueryFilter filter) {
        List<SuspensionMetricAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(ClickHouseJankMetricsQuerySql.selectSuspension(filter))) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_device_day_records").asLong();
            result.add(new SuspensionMetricAggregate(text(row, "algorithm_version"), total, valid,
                    value(row, "average_seconds_per_hour", valid), value(row, "p50_seconds_per_hour", valid),
                    value(row, "p90_seconds_per_hour", valid), value(row, "p99_seconds_per_hour", valid),
                    valid == 0 ? (total == 0 ? "no_data" : "denominator_insufficient") : "ok"));
        }
        return List.copyOf(result);
    }

    @Override
    public List<MetricTrendAggregate> queryFpsTrend(MetricQueryFilter filter, String interval) {
        return trendRows(ClickHouseJankMetricsQuerySql.selectFpsTrend(filter, interval), "no_valid_data");
    }

    @Override
    public List<MetricTrendAggregate> querySuspensionTrend(MetricQueryFilter filter) {
        return trendRows(ClickHouseJankMetricsQuerySql.selectSuspensionTrend(filter), "denominator_insufficient");
    }

    @Override
    public List<MetricDimensionAggregate> queryDimensions(MetricQueryFilter filter, String metric, String dimension) {
        List<MetricDimensionAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(ClickHouseJankMetricsQuerySql.selectDimensions(filter, metric, dimension))) {
            long total = row.path("total_records").asLong();
            boolean fps = "fps".equals(metric);
            long valid = fps ? row.path("valid_records").asLong() : row.path("valid_device_day_records").asLong();
            result.add(new MetricDimensionAggregate(metric, dimension, text(row, "dimension_value"),
                    text(row, "algorithm_version"), total, valid, fps ? 0 : valid,
                    fps ? value(row, "average_fps", valid) : null, fps ? value(row, "p50_fps", valid) : null,
                    fps ? value(row, "p90_fps", valid) : null, fps ? value(row, "p99_fps", valid) : null,
                    fps ? null : value(row, "average_seconds_per_hour", valid),
                    fps ? null : value(row, "p50_seconds_per_hour", valid),
                    fps ? null : value(row, "p90_seconds_per_hour", valid),
                    fps ? null : value(row, "p99_seconds_per_hour", valid),
                    valid == 0 ? (total == 0 ? "no_data" : fps ? "no_valid_data" : "denominator_insufficient") : "ok"));
        }
        return List.copyOf(result);
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }

    private List<MetricTrendAggregate> trendRows(String sql, String emptyStatus) {
        List<MetricTrendAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(sql)) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_records").asLong();
            result.add(new MetricTrendAggregate(instant(row, "bucket_start"), instant(row, "bucket_end"),
                    text(row, "algorithm_version"), total, valid, value(row, "average_value", valid),
                    value(row, "p50_value", valid), value(row, "p90_value", valid), value(row, "p99_value", valid),
                    valid == 0 ? (total == 0 ? "no_data" : emptyStatus) : "ok"));
        }
        return List.copyOf(result);
    }

    private List<JsonNode> rows(String sql) {
        return client.decodeJsonEachRow(client.execute(sql), objectMapper);
    }

    private String text(JsonNode row, String name) {
        JsonNode value = row.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Double value(JsonNode row, String name, long valid) {
        if (valid == 0) return null;
        JsonNode node = row.get(name);
        if (node == null || node.isNull() || !node.isNumber()) return null;
        double value = node.asDouble();
        return Double.isFinite(value) ? value : null;
    }

    private Instant instant(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) {
            throw new EventStoreUnavailableException("ClickHouse 指标趋势缺少时间桶字段: " + field);
        }
        try {
            return value.indexOf('T') >= 0 ? Instant.parse(value) : LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("ClickHouse 指标趋势时间桶格式无效: " + field, ex);
        }
    }
}
