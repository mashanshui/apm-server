package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.persistence.ClickHouseJankQuerySql;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JankQuerySqlTests {

    @Test
    void everyEventQueryHasAppTimeLimitAndTimeoutBoundaries() {
        String sql = ClickHouseJankQuerySql.selectEvents(new JankQueryFilter(
                TestAppIds.id("app-a"), Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-16T00:00:00Z"),
                "1.0", null, null, null, null, "checkout", "jank-v1", null, 20, null, 1500));
        assertTrue(sql.contains("FROM apm_jank_event FINAL"));
        assertTrue(sql.contains("process_id"));
        assertTrue(sql.contains("app_id = '" + TestAppIds.id("app-a") + "'"));
        assertTrue(sql.contains("event_time >= '2026-08-15 00:00:00.000'"));
        assertTrue(sql.contains("event_time < '2026-08-16 00:00:00.000'"));
        assertTrue(sql.contains("app_version = '1.0'"));
        assertTrue(sql.contains("scene = 'checkout'"));
        assertTrue(sql.contains("LIMIT 20"));
        assertTrue(sql.contains("max_execution_time = 2"));
    }
}
