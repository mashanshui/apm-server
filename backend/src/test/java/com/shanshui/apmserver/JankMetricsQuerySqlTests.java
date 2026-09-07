package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;
import com.shanshui.apmserver.jank.internal.persistence.ClickHouseJankMetricsQuerySql;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankMetricsQuerySqlTests {

    private final MetricQueryFilter filter = new MetricQueryFilter(TestAppIds.id("app-a"),
            Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-16T00:00:00Z"),
            "1.0", "official", "production", "16", "Pixel-8", "checkout", "fps-v1", 20, 1500);

    @Test
    void fpsSqlHasFinalBoundariesAndDescendingPercentiles() {
        String sql = ClickHouseJankMetricsQuerySql.selectFps(filter);
        assertTrue(sql.contains("FROM apm_frame_scene_summary FINAL"));
        assertTrue(sql.contains("app_id = '" + TestAppIds.id("app-a") + "'"));
        assertTrue(sql.contains("event_time >= '2026-08-15 00:00:00.000'"));
        assertTrue(sql.contains("event_time < '2026-08-16 00:00:00.000'"));
        assertTrue(sql.contains("scene = 'checkout'"));
        assertTrue(sql.contains("algorithm_version = 'fps-v1'"));
        assertTrue(sql.contains("quantileExactIf(0.50)(normalized_fps60"));
        assertTrue(sql.contains("quantileExactIf(0.10)(normalized_fps60"));
        assertTrue(sql.contains("quantileExactIf(0.01)(normalized_fps60"));
        assertTrue(sql.contains("LIMIT 20"));
        assertTrue(sql.contains("max_execution_time = 2"));
    }

    @Test
    void suspensionSqlMergesUtcDeviceDaysBeforeQuantiles() {
        String sql = ClickHouseJankMetricsQuerySql.selectSuspension(filter);
        assertTrue(sql.contains("FROM apm_device_suspension_segment FINAL"));
        assertTrue(sql.contains("toDate(event_time) AS utc_date"));
        assertTrue(sql.contains("GROUP BY algorithm_version, anonymous_device_id, utc_date"));
        assertTrue(sql.contains("sum(suspension_duration_ms) / 1000.0"));
        assertTrue(sql.contains("valid_device_day_records"));
        assertTrue(sql.contains("prefer_column_name_to_alias = 1"));
    }

    @Test
    void dimensionColumnIsWhitelistOnlyAndSuspensionOmitsScene() {
        String fps = ClickHouseJankMetricsQuerySql.selectDimensions(filter, "fps", "deviceModel");
        assertTrue(fps.contains("device_model AS dimension_value"));
        assertTrue(ClickHouseJankMetricsQuerySql.isSupportedDimension("scene"));
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseJankMetricsQuerySql.dimensionColumn("arbitraryColumn"));
    }

    @Test
    void fpsTrendSqlUsesUtcBucketFinalFiltersLimitAndDescendingQuantiles() {
        String hourly = ClickHouseJankMetricsQuerySql.selectFpsTrend(filter, "hour");
        assertTrue(hourly.contains("toStartOfHour(event_time, 'UTC') AS bucket_start"));
        assertTrue(hourly.contains("addHours(bucket_start, 1) AS bucket_end"));
        assertTrue(hourly.contains("FROM apm_frame_scene_summary FINAL"));
        assertTrue(hourly.contains("GROUP BY bucket_start, algorithm_version"));
        assertTrue(hourly.contains("ORDER BY bucket_start, algorithm_version"));
        assertTrue(hourly.contains("quantileExactIf(0.50)(normalized_fps60"));
        assertTrue(hourly.contains("quantileExactIf(0.10)(normalized_fps60"));
        assertTrue(hourly.contains("quantileExactIf(0.01)(normalized_fps60"));
        assertTrue(hourly.contains("LIMIT 20"));
        assertTrue(hourly.contains("max_execution_time = 2"));

        String daily = ClickHouseJankMetricsQuerySql.selectFpsTrend(filter, "day");
        assertTrue(daily.contains("toStartOfDay(event_time, 'UTC') AS bucket_start"));
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseJankMetricsQuerySql.selectFpsTrend(filter, "week"));
    }

    @Test
    void suspensionTrendSqlUsesTwoStageUtcDeviceDayAggregation() {
        String sql = ClickHouseJankMetricsQuerySql.selectSuspensionTrend(filter);
        assertTrue(sql.contains("toDate(event_time) AS utc_date"));
        assertTrue(sql.contains("GROUP BY algorithm_version, anonymous_device_id, utc_date"));
        assertTrue(sql.contains("GROUP BY utc_date, algorithm_version"));
        assertTrue(sql.contains("countIf(foreground_duration_ms > 0) AS valid_records"));
        assertTrue(sql.contains("prefer_column_name_to_alias = 1"));
        assertTrue(sql.contains("LIMIT 20"));
    }
}
