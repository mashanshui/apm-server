package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseSchemaTests {

    @Test
    void legacyCrashSchemaRemainsAvailableForControlledV4Rebuild() throws Exception {
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

    @Test
    void legacyJankSchemaRemainsVersionedAndSeparatesFactDetailAndMetrics() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/002_jank_schema.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_jank_event"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_jank_detail"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_jank_issue_hourly"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_frame_scene_summary"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_device_suspension_segment"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_device_suspension_daily"));
            assertTrue(sql.contains("ReplacingMergeTree(received_time)"));
            assertTrue(sql.contains("PARTITION BY toYYYYMM(event_time)"));
            assertTrue(sql.contains("TTL event_time + INTERVAL 30 DAY"));
            assertTrue(sql.contains("quantilesTDigestState(0.5, 0.9, 0.99)"));
            assertTrue(sql.contains("scene, algorithm_version)"));
            assertTrue(sql.contains("CREATE MATERIALIZED VIEW IF NOT EXISTS apm.apm_jank_issue_hourly_mv"));
        }
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/003_jank_sampling_quality.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS parsed_sample_count UInt32"));
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS missing_sample_count UInt32"));
        }
    }

    @Test
    void applicationIdentitySchemaRebuildsEmptyTablesWithUuidAndPackageName() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/004_application_identity_schema.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("throwIf"));
            assertTrue(sql.contains("CREATE TABLE apm.apm_event_raw"));
            assertTrue(sql.contains("UUID"));
            assertTrue(sql.contains("package_name"));
            assertTrue(sql.contains("CREATE MATERIALIZED VIEW apm.apm_event_hourly_mv"));
            assertTrue(sql.contains("CREATE MATERIALIZED VIEW apm.apm_jank_issue_hourly_mv"));
            assertTrue(!sql.contains("project_id"));
        }
    }

    @Test
    void memoryMetricsSchemaKeepsNullableSamplesAndStableDedupKey() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/005_memory_metrics.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS apm.apm_memory_sample"));
            assertTrue(sql.contains("pss_bytes Nullable(UInt64)"));
            assertTrue(sql.contains("vss_bytes Nullable(UInt64)"));
            assertTrue(sql.contains("java_heap_used_bytes Nullable(UInt64)"));
            assertTrue(sql.contains("ENGINE = ReplacingMergeTree(received_time)"));
            assertTrue(sql.contains("PARTITION BY toYYYYMM(event_time)"));
            assertTrue(sql.contains("ORDER BY (app_id, event_id)"));
            assertTrue(sql.contains("TTL event_time + INTERVAL 90 DAY"));
        }
    }

    @Test
    void processIdentityMigrationAddsOnlyStringColumnsWithoutChangingDeduplication() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/clickhouse/007_event_process_identity.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String table : new String[]{"apm_event_raw", "apm_jank_event", "apm_frame_scene_summary",
                    "apm_device_suspension_segment", "apm_memory_sample", "apm_memory_report"}) {
                assertTrue(sql.contains("ALTER TABLE apm." + table));
            }
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS process_id String DEFAULT ''"));
            assertTrue(!sql.contains("ORDER BY"));
            assertTrue(!sql.contains("DROP TABLE"));
        }
    }
}
