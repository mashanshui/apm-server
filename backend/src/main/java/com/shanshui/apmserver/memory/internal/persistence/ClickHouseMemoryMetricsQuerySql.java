package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 生成内存概览和趋势 SQL；metric 只通过固定白名单映射列名，并使用线性插值分位数。 */
public final class ClickHouseMemoryMetricsQuerySql {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private ClickHouseMemoryMetricsQuerySql() {
    }

    public static String selectSummary(MemoryQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(summaryColumns("pss_bytes", "pss"))
                .append(", ").append(summaryColumns("vss_bytes", "vss"))
                .append(", ").append(summaryColumns("java_heap_used_bytes", "java_heap"))
                .append(" FROM apm_memory_sample FINAL WHERE ");
        appendWhere(sql, filter);
        appendSettings(sql, filter);
        return sql.toString();
    }

    public static String selectTrend(MemoryQueryFilter filter, String metric, String interval) {
        String column = metricColumn(metric);
        String bucket = switch (interval) {
            case "hour" -> "toStartOfHour(event_time, 'UTC')";
            case "day" -> "toStartOfDay(event_time, 'UTC')";
            default -> throw new IllegalArgumentException("不支持的内存趋势粒度: " + interval);
        };
        String bucketEnd = "hour".equals(interval) ? "addHours(bucket_start, 1)" : "addDays(bucket_start, 1)";
        String valid = "isNotNull(" + column + ")";
        StringBuilder sql = new StringBuilder("SELECT ").append(bucket).append(" AS bucket_start, ")
                .append(bucketEnd).append(" AS bucket_end, countIf(").append(valid).append(") AS sample_count, ")
                .append("avgIf(toFloat64(").append(column).append("), ").append(valid).append(") AS average_bytes, ")
                .append("quantileExactInclusiveIf(0.50)(toFloat64(").append(column).append("), ").append(valid).append(") AS p50_bytes, ")
                .append("quantileExactInclusiveIf(0.90)(toFloat64(").append(column).append("), ").append(valid).append(") AS p90_bytes, ")
                .append("quantileExactInclusiveIf(0.95)(toFloat64(").append(column).append("), ").append(valid).append(") AS p95_bytes, ")
                .append("quantileExactInclusiveIf(0.99)(toFloat64(").append(column).append("), ").append(valid).append(") AS p99_bytes ")
                .append("FROM apm_memory_sample FINAL WHERE ");
        appendWhere(sql, filter);
        sql.append(" GROUP BY bucket_start ORDER BY bucket_start");
        appendTrendSettings(sql, filter);
        return sql.toString();
    }

    public static String metricColumn(String metric) {
        return switch (metric) {
            case "pss" -> "pss_bytes";
            case "vss" -> "vss_bytes";
            case "java_heap" -> "java_heap_used_bytes";
            default -> throw new IllegalArgumentException("不支持的内存指标: " + metric);
        };
    }

    private static String summaryColumns(String column, String prefix) {
        String valid = "isNotNull(" + column + ")";
        return "countIf(" + valid + ") AS " + prefix + "_sample_count, "
                + "avgIf(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_average_bytes, "
                + "quantileExactInclusiveIf(0.50)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p50_bytes, "
                + "quantileExactInclusiveIf(0.90)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p90_bytes, "
                + "quantileExactInclusiveIf(0.95)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p95_bytes, "
                + "quantileExactInclusiveIf(0.99)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p99_bytes";
    }

    private static void appendWhere(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append("app_id = '").append(escape(filter.appId().toString())).append("' AND event_time >= '")
                .append(format(filter.from())).append("' AND event_time < '").append(format(filter.to())).append("'");
        appendFilter(sql, "app_version", filter.appVersion());
        appendFilter(sql, "os_version", filter.osVersion());
        appendFilter(sql, "device_model", filter.deviceModel());
        appendFilter(sql, "process_name", filter.processName());
        appendFilter(sql, "scene", filter.scene());
        if (filter.foreground() != null) {
            sql.append(" AND foreground = ").append(filter.foreground() ? "1" : "0");
        }
    }

    private static void appendFilter(StringBuilder sql, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = '").append(escape(value)).append("'");
        }
    }

    private static void appendSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                .append(" FORMAT JSONEachRow");
    }

    private static void appendLimitAndSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" LIMIT ").append(filter.limit())
                .append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                .append(", prefer_column_name_to_alias = 1 FORMAT JSONEachRow");
    }

    private static void appendTrendSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                .append(", prefer_column_name_to_alias = 1 FORMAT JSONEachRow");
    }

    private static long seconds(long timeoutMs) {
        return Math.max(1L, (timeoutMs + 999L) / 1000L);
    }

    private static String format(Instant value) {
        return CLICKHOUSE_TIME.format(value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
