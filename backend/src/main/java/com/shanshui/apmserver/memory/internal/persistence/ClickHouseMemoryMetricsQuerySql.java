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

    /** 在数据库按各项峰值上下文独立统计运行分布。 */
    public static String selectSummary(MemoryQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(summaryColumns("pss_bytes", "pss", filter))
                .append(", ").append(summaryColumns("vss_bytes", "vss", filter))
                .append(", ").append(summaryColumns("java_heap_used_bytes", "java_heap", filter))
                .append(" FROM apm_memory_peak_summary FINAL WHERE ");
        appendWhere(sql, filter);
        appendSettings(sql, filter);
        return sql.toString();
    }

    /** 按运行观测截止时间生成有界 UTC 趋势。 */
    public static String selectTrend(MemoryQueryFilter filter, String metric, String interval) {
        String column = metricColumn(metric);
        String bucket = switch (interval) {
            case "hour" -> "toStartOfHour(event_time, 'UTC')";
            case "day" -> "toStartOfDay(event_time, 'UTC')";
            default -> throw new IllegalArgumentException("不支持的内存趋势粒度: " + interval);
        };
        String bucketEnd = "hour".equals(interval) ? "addHours(bucket_start, 1)" : "addDays(bucket_start, 1)";
        String valid = valid(column, "java_heap".equals(metric) ? "java_heap" : metric, filter);
        StringBuilder sql = new StringBuilder("SELECT ").append(bucket).append(" AS bucket_start, ")
                .append(bucketEnd).append(" AS bucket_end, countIf(").append(valid).append(") AS run_count, ")
                .append("avgIf(toFloat64(").append(column).append("), ").append(valid).append(") AS average_bytes, ")
                .append("quantileExactInclusiveIf(0.50)(toFloat64(").append(column).append("), ").append(valid).append(") AS p50_bytes, ")
                .append("quantileExactInclusiveIf(0.90)(toFloat64(").append(column).append("), ").append(valid).append(") AS p90_bytes, ")
                .append("quantileExactInclusiveIf(0.95)(toFloat64(").append(column).append("), ").append(valid).append(") AS p95_bytes, ")
                .append("quantileExactInclusiveIf(0.99)(toFloat64(").append(column).append("), ").append(valid).append(") AS p99_bytes ")
                .append("FROM apm_memory_peak_summary FINAL WHERE ");
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

    /** 为单项峰值生成有效运行数及精确统计表达式。 */
    private static String summaryColumns(String column, String prefix, MemoryQueryFilter filter) {
        String valid = valid(column, prefix, filter);
        return "countIf(" + valid + ") AS " + prefix + "_run_count, "
                + "avgIf(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_average_bytes, "
                + "quantileExactInclusiveIf(0.50)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p50_bytes, "
                + "quantileExactInclusiveIf(0.90)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p90_bytes, "
                + "quantileExactInclusiveIf(0.95)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p95_bytes, "
                + "quantileExactInclusiveIf(0.99)(toFloat64(" + column + "), " + valid + ") AS " + prefix + "_p99_bytes";
    }

    /** 追加所有指标共用的原运行元数据和截止时间条件。 */
    private static void appendWhere(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append("app_id = '").append(escape(filter.appId().toString())).append("' AND event_time >= '")
                .append(format(filter.from())).append("' AND event_time < '").append(format(filter.to())).append("'");
        // FINAL 不一定将非排序列的时间条件推入分区索引，显式限定相交月份。
        java.time.format.DateTimeFormatter month = java.time.format.DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
        sql.append(" AND toYYYYMM(event_time) >= ").append(month.format(filter.from()))
                .append(" AND toYYYYMM(event_time) <= ").append(month.format(filter.to().minusNanos(1)));
        appendFilter(sql, "app_version", filter.appVersion());
        appendFilter(sql, "os_version", filter.osVersion());
        appendFilter(sql, "device_model", filter.deviceModel());
        appendFilter(sql, "process_name", filter.processName());

    }

    /** 各指标以自己的峰值上下文构造条件，不能放进三个指标共用的 WHERE。 */
    private static String valid(String column, String prefix, MemoryQueryFilter filter) {
        StringBuilder condition = new StringBuilder("isNotNull(" + column + ")");
        appendFilter(condition, prefix + "_scene", filter.scene());
        if (filter.foreground() != null) condition.append(" AND ").append(prefix).append("_foreground = ")
                .append(filter.foreground() ? "1" : "0");
        return "(" + condition + ")";
    }

    private static void appendFilter(StringBuilder sql, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = '").append(escape(value)).append("'");
        }
    }

    private static void appendSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                // 同一合法eventId的截止时间不可变，重复不会跨月份分区；允许FINAL先裁剪月份。
                .append(", do_not_merge_across_partitions_select_final = 1 FORMAT JSONEachRow");
    }

    private static void appendLimitAndSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" LIMIT ").append(filter.limit())
                .append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                .append(", prefer_column_name_to_alias = 1, do_not_merge_across_partitions_select_final = 1 FORMAT JSONEachRow");
    }

    private static void appendTrendSettings(StringBuilder sql, MemoryQueryFilter filter) {
        sql.append(" SETTINGS max_execution_time = ").append(seconds(filter.timeoutMs()))
                .append(", prefer_column_name_to_alias = 1, do_not_merge_across_partitions_select_final = 1 FORMAT JSONEachRow");
    }

    private static double seconds(long timeoutMs) {
        return Math.max(0.001D, timeoutMs / 1000.0D);
    }

    private static String format(Instant value) {
        return CLICKHOUSE_TIME.format(value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
