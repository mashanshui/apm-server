package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 生成 FPS/设备日挂起率聚合 SQL；动态列只能通过固定白名单解析。 */
public final class ClickHouseJankMetricsQuerySql {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final Map<String, String> DIMENSION_COLUMNS = new LinkedHashMap<>();

    static {
        DIMENSION_COLUMNS.put("appVersion", "app_version");
        DIMENSION_COLUMNS.put("channel", "channel");
        DIMENSION_COLUMNS.put("environment", "environment");
        DIMENSION_COLUMNS.put("osVersion", "os_version");
        DIMENSION_COLUMNS.put("deviceModel", "device_model");
        DIMENSION_COLUMNS.put("scene", "scene");
        DIMENSION_COLUMNS.put("algorithmVersion", "algorithm_version");
    }

    private ClickHouseJankMetricsQuerySql() {
    }

    public static String selectFps(MetricQueryFilter filter) {
        String valid = "active_duration_ms > 0 AND ui_refresh_frame_count > 0"
                + " AND isFinite(refresh_rate_hz) AND refresh_rate_hz > 0"
                + " AND isFinite(normalized_fps60) AND normalized_fps60 >= 0";
        StringBuilder sql = new StringBuilder("SELECT algorithm_version, count() AS total_records, countIf(")
                .append(valid).append(") AS valid_records, avgIf(toFloat64(normalized_fps60), ")
                .append(valid).append(") AS average_fps, ")
                .append("quantileExactIf(0.50)(normalized_fps60, ").append(valid).append(") AS p50_fps, ")
                .append("quantileExactIf(0.10)(normalized_fps60, ").append(valid).append(") AS p90_fps, ")
                .append("quantileExactIf(0.01)(normalized_fps60, ").append(valid).append(") AS p99_fps ")
                .append("FROM apm_frame_scene_summary FINAL WHERE ");
        appendBaseWhere(sql, filter, true);
        appendFilter(sql, "algorithm_version", filter.algorithmVersion());
        appendLimit(sql, filter);
        return sql.toString();
    }

    public static String selectSuspension(MetricQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT algorithm_version, sum(segment_records) AS total_records, ")
                .append("countIf(foreground_duration_ms > 0) AS valid_device_day_records, ")
                .append("avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_seconds_per_hour, ")
                .append("quantileExactIf(0.50)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p50_seconds_per_hour, ")
                .append("quantileExactIf(0.90)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p90_seconds_per_hour, ")
                .append("quantileExactIf(0.99)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p99_seconds_per_hour ")
                .append("FROM (");
        appendSuspensionDeviceDaySelect(sql, filter, null);
        sql.append(") AS device_days GROUP BY algorithm_version ORDER BY algorithm_version");
        appendLimitAndSettings(sql, filter, true);
        return sql.toString();
    }

    public static String selectFpsTrend(MetricQueryFilter filter, String interval) {
        String bucket = switch (interval) {
            case "hour" -> "toStartOfHour(event_time, 'UTC')";
            case "day" -> "toStartOfDay(event_time, 'UTC')";
            default -> throw new IllegalArgumentException("不支持的 FPS 趋势粒度: " + interval);
        };
        String bucketEnd = "hour".equals(interval) ? "addHours(bucket_start, 1)" : "addDays(bucket_start, 1)";
        String valid = "active_duration_ms > 0 AND ui_refresh_frame_count > 0"
                + " AND isFinite(refresh_rate_hz) AND refresh_rate_hz > 0"
                + " AND isFinite(normalized_fps60) AND normalized_fps60 >= 0";
        StringBuilder sql = new StringBuilder("SELECT ").append(bucket).append(" AS bucket_start, ")
                .append(bucketEnd).append(" AS bucket_end, algorithm_version, count() AS total_records, countIf(")
                .append(valid).append(") AS valid_records, avgIf(toFloat64(normalized_fps60), ")
                .append(valid).append(") AS average_value, ")
                .append("quantileExactIf(0.50)(normalized_fps60, ").append(valid).append(") AS p50_value, ")
                .append("quantileExactIf(0.10)(normalized_fps60, ").append(valid).append(") AS p90_value, ")
                .append("quantileExactIf(0.01)(normalized_fps60, ").append(valid).append(") AS p99_value ")
                .append("FROM apm_frame_scene_summary FINAL WHERE ");
        appendBaseWhere(sql, filter, true);
        appendFilter(sql, "algorithm_version", filter.algorithmVersion());
        sql.append(" GROUP BY bucket_start, algorithm_version ORDER BY bucket_start, algorithm_version");
        appendLimitAndSettings(sql, filter);
        return sql.toString();
    }

    public static String selectSuspensionTrend(MetricQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT toDateTime(utc_date, 'UTC') AS bucket_start, ")
                .append("addDays(bucket_start, 1) AS bucket_end, algorithm_version, ")
                .append("sum(segment_records) AS total_records, ")
                .append("countIf(foreground_duration_ms > 0) AS valid_records, ")
                .append("avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_value, ")
                .append("quantileExactIf(0.50)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p50_value, ")
                .append("quantileExactIf(0.90)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p90_value, ")
                .append("quantileExactIf(0.99)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p99_value ")
                .append("FROM (");
        appendSuspensionDeviceDaySelect(sql, filter, null);
        sql.append(") AS device_days GROUP BY utc_date, algorithm_version ORDER BY bucket_start, algorithm_version");
        appendLimitAndSettings(sql, filter, true);
        return sql.toString();
    }

    public static String selectDimensions(MetricQueryFilter filter, String metric, String dimension) {
        if (!"fps".equals(metric) && !"suspension_rate".equals(metric)) {
            throw new IllegalArgumentException("不支持的聚合指标: " + metric);
        }
        String column = dimensionColumn(dimension);
        if ("suspension_rate".equals(metric) && "scene".equals(dimension)) {
            throw new IllegalArgumentException("挂起率不支持 scene 维度");
        }
        if ("fps".equals(metric)) {
            return selectFpsDimensions(filter, dimension, column);
        }
        return selectSuspensionDimensions(filter, dimension, column);
    }

    public static boolean isSupportedDimension(String dimension) {
        return DIMENSION_COLUMNS.containsKey(dimension);
    }

    public static String dimensionColumn(String dimension) {
        String column = DIMENSION_COLUMNS.get(dimension);
        if (column == null) {
            throw new IllegalArgumentException("不支持的聚合维度: " + dimension);
        }
        return column;
    }

    private static String selectFpsDimensions(MetricQueryFilter filter, String dimension, String column) {
        String valid = "active_duration_ms > 0 AND ui_refresh_frame_count > 0"
                + " AND isFinite(refresh_rate_hz) AND refresh_rate_hz > 0"
                + " AND isFinite(normalized_fps60) AND normalized_fps60 >= 0";
        StringBuilder sql = new StringBuilder("SELECT ").append(column).append(" AS dimension_value, algorithm_version, ")
                .append("count() AS total_records, countIf(").append(valid).append(") AS valid_records, ")
                .append("avgIf(toFloat64(normalized_fps60), ").append(valid).append(") AS average_fps, ")
                .append("quantileExactIf(0.50)(normalized_fps60, ").append(valid).append(") AS p50_fps, ")
                .append("quantileExactIf(0.10)(normalized_fps60, ").append(valid).append(") AS p90_fps, ")
                .append("quantileExactIf(0.01)(normalized_fps60, ").append(valid).append(") AS p99_fps ")
                .append("FROM apm_frame_scene_summary FINAL WHERE ");
        appendBaseWhere(sql, filter, true);
        appendFilter(sql, "scene", filter.scene());
        appendFilter(sql, "algorithm_version", filter.algorithmVersion());
        sql.append(" GROUP BY ").append(column).append(", algorithm_version ORDER BY dimension_value, algorithm_version");
        appendLimitAndSettings(sql, filter);
        return sql.toString();
    }

    private static String selectSuspensionDimensions(MetricQueryFilter filter, String dimension, String column) {
        StringBuilder sql = new StringBuilder("SELECT dimension_value, algorithm_version, sum(segment_records) AS total_records, ")
                .append("countIf(foreground_duration_ms > 0) AS valid_device_day_records, ")
                .append("avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_seconds_per_hour, ")
                .append("quantileExactIf(0.50)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p50_seconds_per_hour, ")
                .append("quantileExactIf(0.90)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p90_seconds_per_hour, ")
                .append("quantileExactIf(0.99)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p99_seconds_per_hour ")
                .append("FROM (");
        appendSuspensionDeviceDaySelect(sql, filter, column);
        sql.append(") AS device_days GROUP BY dimension_value, algorithm_version ORDER BY dimension_value, algorithm_version");
        appendLimitAndSettings(sql, filter, true);
        return sql.toString();
    }

    private static void appendSuspensionDeviceDaySelect(StringBuilder sql, MetricQueryFilter filter, String dimensionColumn) {
        sql.append("SELECT algorithm_version, anonymous_device_id, toDate(event_time) AS utc_date, ")
                .append("app_version, channel, environment, os_version, device_model, ");
        if (dimensionColumn != null) {
            sql.append(dimensionColumn).append(" AS dimension_value, ");
        } else {
            sql.append("'' AS dimension_value, ");
        }
        sql.append("count() AS segment_records, sum(foreground_duration_ms) AS foreground_duration_ms, ")
                .append("sum(suspension_duration_ms) AS suspension_duration_ms, ")
                .append("sum(suspension_count) AS suspension_count, ")
                .append("if(sum(foreground_duration_ms) > 0, ")
                .append("sum(suspension_duration_ms) / 1000.0 / (sum(foreground_duration_ms) / 3600000.0), 0.0) AS suspension_seconds_per_hour ")
                .append("FROM apm_device_suspension_segment FINAL WHERE ");
        appendBaseWhere(sql, filter, false);
        appendFilter(sql, "algorithm_version", filter.algorithmVersion());
        sql.append(" GROUP BY algorithm_version, anonymous_device_id, utc_date, app_version, channel, environment, os_version, device_model");
        if (dimensionColumn != null) {
            sql.append(", dimension_value");
        }
    }

    private static void appendBaseWhere(StringBuilder sql, MetricQueryFilter filter, boolean includeSceneColumn) {
        sql.append("app_id = '").append(escape(filter.appId().toString())).append("' AND event_time >= '")
                .append(format(filter.from())).append("' AND event_time < '").append(format(filter.to())).append("'");
        appendFilter(sql, "app_version", filter.appVersion());
        appendFilter(sql, "channel", filter.channel());
        appendFilter(sql, "environment", filter.environment());
        appendFilter(sql, "os_version", filter.osVersion());
        appendFilter(sql, "device_model", filter.deviceModel());
        if (includeSceneColumn) {
            appendFilter(sql, "scene", filter.scene());
        }
    }

    private static void appendFilter(StringBuilder sql, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = '").append(escape(value)).append("'");
        }
    }

    private static void appendLimit(StringBuilder sql, MetricQueryFilter filter) {
        sql.append(" GROUP BY algorithm_version ORDER BY algorithm_version");
        appendLimitAndSettings(sql, filter);
    }

    private static void appendLimitAndSettings(StringBuilder sql, MetricQueryFilter filter) {
        appendLimitAndSettings(sql, filter, false);
    }

    /**
     * ClickHouse 默认会把外层查询中的别名替换回内层聚合表达式。
     * 挂起率先做设备日二阶段聚合，必须按列名解析外层字段，避免出现嵌套聚合。
     */
    private static void appendLimitAndSettings(StringBuilder sql, MetricQueryFilter filter,
                                                boolean preferColumnNameToAlias) {
        long seconds = Math.max(1L, (filter.timeoutMs() + 999L) / 1000L);
        sql.append(" LIMIT ").append(filter.limit())
                .append(" SETTINGS max_execution_time = ").append(seconds);
        if (preferColumnNameToAlias) {
            sql.append(", prefer_column_name_to_alias = 1");
        }
        sql.append(" FORMAT JSONEachRow");
    }

    private static String format(Instant instant) {
        return CLICKHOUSE_TIME.format(instant);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
