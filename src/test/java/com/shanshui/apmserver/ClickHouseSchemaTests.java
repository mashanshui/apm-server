package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseSchemaTests {

    @Test
    void schemaIsIdempotentAndContainsRawDetailAndAggregates() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/001_crash_schema.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_event_raw"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_crash_detail"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_event_hourly"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_crash_issue_hourly"));
            assertTrue(sql.contains("PARTITION BY toYYYYMM(event_time)"));
            assertTrue(sql.contains("ORDER BY (project_id, event_type, event_time, event_id)"));
            assertTrue(sql.contains("TTL event_time + INTERVAL 30 DAY"));
            assertTrue(sql.contains("uniqCombined64State(event_id)"));
            assertTrue(sql.contains("CREATE MATERIALIZED VIEW IF NOT EXISTS"));
        }
    }
}
