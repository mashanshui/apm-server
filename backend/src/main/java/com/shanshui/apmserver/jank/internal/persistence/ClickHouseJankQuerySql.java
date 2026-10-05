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

    /** 总览精确聚合，不裁剪统计输入。 */
    public static String overview(JankQueryFilter filter) {
        return "SELECT " + statsProjection() + " FROM " + fact(filter) + " FORMAT JSONEachRow";
    }

    /** 趋势每个非空 UTC 桶独立计算精确统计。 */
    public static String trend(JankQueryFilter filter, String interval) {
        // interval 已在服务白名单校验，这里依然只选择两条固定表达式。
        String bucket = "hour".equals(interval) ? "toStartOfHour(event_time, 'UTC')" : "toStartOfDay(event_time, 'UTC')";
        return "SELECT toUnixTimestamp64Milli(toDateTime64(" + bucket + ",3,'UTC')) AS bucket_ms, "
                + statsProjection() + " FROM " + fact(filter) + " GROUP BY bucket_ms ORDER BY bucket_ms FORMAT JSONEachRow";
    }

    /** Issue 聚合后才应用排序游标和页大小。 */
    public static String issues(JankQueryFilter filter, com.shanshui.apmserver.jank.internal.application.JankCursor.State cursor) {
        // 代表字段来自同一最早事件；groupArray 受数据库内存预算约束。
        String aggregate = "SELECT fingerprint, count() AS event_count, uniqExactIf(session_id,session_id!='') AS sessions, "
                + "uniqExactIf(anonymous_device_id,anonymous_device_id!='') AS devices, "
                + "toUnixTimestamp64Milli(min(event_time)) AS first_ms, toUnixTimestamp64Milli(max(event_time)) AS last_ms, "
                + "argMin(tuple(fingerprint_version,scene,algorithm_version),tuple(event_time,event_id)) AS representative, "
                + percentiles("message_duration_ns","exact") + ", " + percentiles("estimated_duration_ns","estimated")
                + " FROM " + fact(filter) + " AND fingerprint!='' GROUP BY fingerprint";
        String after = cursor == null ? "" : " WHERE event_count < " + cursor.count()
                + " OR (event_count = " + cursor.count() + " AND (last_ms < " + cursor.time().toEpochMilli()
                + " OR (last_ms = " + cursor.time().toEpochMilli() + " AND fingerprint > '" + escape(cursor.id()) + "')))";
        return "SELECT * FROM (" + aggregate + ")" + after
                + " ORDER BY event_count DESC,last_ms DESC,fingerprint ASC LIMIT " + (filter.limit()+1) + " FORMAT JSONEachRow";
    }

    /** 事件页仅投影公开摘要标量，不加载 JSON 采样证据。 */
    public static String eventSummaries(JankQueryFilter filter, com.shanshui.apmserver.jank.internal.application.JankCursor.State cursor) {
        String after = cursor == null ? "" : " AND (event_time < " + timestamp(cursor.time())
                + " OR (event_time = " + timestamp(cursor.time()) + " AND event_id > '" + escape(cursor.id()) + "'))";
        return "SELECT event_id,toUnixTimestamp64Milli(event_time) AS event_ms,app_version,version_code,build_id,"
                + "channel,environment,os_version,device_model,session_id,anonymous_device_id,scene,algorithm_version,"
                + "fingerprint,fingerprint_version,message_duration_ns,estimated_duration_ns,"
                + "estimated_unattributed_duration_ns,covered_duration_ns,uncovered_duration_ns FROM " + fact(filter)
                + after + " ORDER BY event_time DESC,event_id ASC LIMIT " + (filter.limit()+1) + " FORMAT JSONEachRow";
    }

    /** 只选择统计必需列，数组仅留在数据库计算过程。 */
    private static String statsProjection() {
        return "count() AS event_count,uniqExactIf(session_id,session_id!='') AS sessions,"
                + "uniqExactIf(anonymous_device_id,anonymous_device_id!='') AS devices,"
                + "countIf(fingerprint!='') AS groupable," + percentiles("message_duration_ns","exact");
    }

    /** 采用 ceil(N*p) 升序秩，零值合法，空集合显式 null。 */
    private static String percentiles(String column, String prefix) {
        // 数组只存在数据库端，不在最终响应中输出。
        String sorted = "arraySort(groupArray(" + column + "))";
        StringBuilder projection = new StringBuilder();
        String[] probabilities = {"0.50","0.90","0.99"};
        String[] suffixes = {"50","90","99"};
        for (int i=0; i<probabilities.length; i++) {
            if (i>0) projection.append(",");
            projection.append("if(empty(").append(sorted).append("),NULL,")
                    .append(sorted).append("[greatest(1,toUInt64(ceil(length(").append(sorted)
                    .append(")*").append(probabilities[i]).append(")))]/1000000.0) AS ")
                    .append(prefix).append(suffixes[i]);
        }
        return projection.toString();
    }

    /** 固定表名、应用与全部白名单筛选下推；FINAL 保证逻辑事件去重。 */
    private static String fact(JankQueryFilter filter) {
        StringBuilder sql = new StringBuilder("apm_jank_event FINAL WHERE app_id='").append(filter.appId())
                .append("' AND event_time >= ").append(timestamp(filter.from()))
                .append(" AND event_time < ").append(timestamp(filter.to()));
        appendDimension(sql,"appVersion",filter.appVersion());
        appendDimension(sql,"channel",filter.channel());
        appendDimension(sql,"environment",filter.environment());
        appendDimension(sql,"osVersion",filter.osVersion());
        appendDimension(sql,"deviceModel",filter.deviceModel());
        appendDimension(sql,"scene",filter.scene());
        appendDimension(sql,"algorithmVersion",filter.algorithmVersion());
        appendDimension(sql,"fingerprint",filter.fingerprint());
        return sql.toString();
    }

    /** 明确时间精度，默认窗中的纳秒不被 SQL 字面量截断。 */
    private static String timestamp(Instant value) {
        return "toDateTime64('" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS")
                .withZone(ZoneOffset.UTC).format(value) + "',9,'UTC')";
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
