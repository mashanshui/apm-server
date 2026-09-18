package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 生成卡顿聚合仓库使用的受限 SQL；维度只能从固定映射中选择。 */
public final class ClickHouseJankQuerySql {

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
        DIMENSION_COLUMNS.put("fingerprint", "fingerprint");
    }

    private ClickHouseJankQuerySql() {
    }

    public static String selectEvents(JankQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT app_id, package_name, event_id, event_time, received_time, "
                + "schema_version, session_id, process_id, anonymous_device_id, app_version, version_code, build_id, channel, "
                + "environment, os_version, device_model, network_type, scene, algorithm_version, "
                + "message_duration_ns, threshold_ns, sampling_interval_ns, estimated_duration_ns, "
                + "estimated_unattributed_duration_ns, covered_duration_ns, uncovered_duration_ns, "
                + "expected_sample_count, parsed_sample_count, missing_sample_count, "
                + "fingerprint, fingerprint_version, jank_payload_json, jank_analysis_json "
                + "FROM apm_jank_event FINAL WHERE app_id = '")
                .append(escape(filter.appId().toString())).append("' AND event_time >= '")
                .append(format(filter.from())).append("' AND event_time < '").append(format(filter.to())).append("'");
        appendDimension(sql, "appVersion", filter.appVersion());
        appendDimension(sql, "channel", filter.channel());
        appendDimension(sql, "environment", filter.environment());
        appendDimension(sql, "osVersion", filter.osVersion());
        appendDimension(sql, "deviceModel", filter.deviceModel());
        appendDimension(sql, "scene", filter.scene());
        appendDimension(sql, "algorithmVersion", filter.algorithmVersion());
        appendDimension(sql, "fingerprint", filter.fingerprint());
        long seconds = Math.max(1L, (filter.timeoutMs() + 999L) / 1000L);
        sql.append(" ORDER BY event_time, event_id LIMIT ").append(filter.limit())
                .append(" SETTINGS max_execution_time = ").append(seconds).append(" FORMAT JSONEachRow");
        return sql.toString();
    }

    private static void appendDimension(StringBuilder sql, String requestName, String value) {
        if (value != null) {
            sql.append(" AND ").append(DIMENSION_COLUMNS.get(requestName)).append(" = '")
                    .append(escape(value)).append("'");
        }
    }

    private static String format(Instant instant) {
        return CLICKHOUSE_TIME.format(instant);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
