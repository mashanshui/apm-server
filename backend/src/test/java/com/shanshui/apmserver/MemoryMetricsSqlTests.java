package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.persistence.ClickHouseMemoryMetricsQuerySql;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 ClickHouse 内存查询只使用固定指标列、最终去重视图和 UTC 时间边界。 */
class MemoryMetricsSqlTests {

    private static final UUID APP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final MemoryQueryFilter FILTER = new MemoryQueryFilter(APP_ID,
            Instant.parse("2026-09-08T00:30:00Z"), Instant.parse("2026-09-08T03:15:00Z"),
            "3.2.0", "16", "Pixel-8", "com.example.app", "home", false, 500, 2_000);

    @Test
    void summaryUsesFinalAndIndependentNullableAggregates() {
        String sql = ClickHouseMemoryMetricsQuerySql.selectSummary(FILTER);
        assertThat(sql).contains("FROM apm_memory_sample FINAL")
                .contains("event_time >= '2026-09-08 00:30:00.000'")
                .contains("event_time < '2026-09-08 03:15:00.000'")
                .contains("app_version = '3.2.0'")
                .contains("foreground = 0")
                .contains("quantileExactInclusiveIf(0.95)")
                .contains("java_heap_used_bytes");
    }

    @Test
    void trendUsesUtcBucketsAndDoesNotApplyEventLimit() {
        String sql = ClickHouseMemoryMetricsQuerySql.selectTrend(FILTER, "pss", "hour");
        assertThat(sql).contains("toStartOfHour(event_time, 'UTC')")
                .contains("GROUP BY bucket_start")
                .contains("prefer_column_name_to_alias = 1")
                .doesNotContain(" LIMIT ");
    }

    @Test
    void metricColumnRejectsUnlistedDimensions() {
        assertThatThrownBy(() -> ClickHouseMemoryMetricsQuerySql.metricColumn("fd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClickHouseMemoryMetricsQuerySql.selectTrend(FILTER, "pss", "week"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
