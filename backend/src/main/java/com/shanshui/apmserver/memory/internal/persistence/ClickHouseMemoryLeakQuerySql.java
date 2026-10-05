package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 报告事实先去重，再展开匹配路径；正文不参与列表与趋势。 */
public final class ClickHouseMemoryLeakQuerySql {
    /** SQL 生成器无需实例。 */
    private ClickHouseMemoryLeakQuerySql() {}

    /** 一条查询同时返回完整分母和最终问题页；空末页仍有总计。 */
    public static String issues(MemoryLeakQueryFilter filter,int page,int size,String sort,String order) {
        // 排序仅允许固定标量列，调用端不能注入 SQL 标识符。
        String column = switch(sort) { case "occurrences" -> "occurrences"; case "affectedDevices" -> "devices";
            case "lastOccurredAt" -> "last_ms"; default -> throw new IllegalArgumentException("sort 无效"); };
        if (!"asc".equals(order) && !"desc".equals(order)) throw new IllegalArgumentException("order 无效");
        long offset = (long)(page-1)*size;
        String aggregation = "SELECT signature,count() AS occurrences,uniqExact(anonymous_device_id) AS devices,"
                + "max(toUnixTimestamp64Milli(event_time)) AS last_ms,"
                + "argMin(matched_path,tuple(-toUnixTimestamp64Milli(event_time),toString(event_id))) AS representative,"
                + "arraySort(groupUniqArray(app_version)) AS versions FROM matched GROUP BY signature";
        return "WITH " + matched(filter) + ", aggregated AS (" + aggregation + "), "
                + "totals AS (SELECT (SELECT count() FROM aggregated) AS total,count() AS total_occurrences,"
                + "uniqExact(anonymous_device_id) AS total_devices FROM matched) "
                + "SELECT *, (SELECT groupArray(tuple(signature,occurrences,devices,last_ms,representative,versions)) "
                + "FROM (SELECT * FROM aggregated ORDER BY " + column + " " + order + ",signature ASC LIMIT "
                + size + " OFFSET " + offset + ")) AS items FROM totals FORMAT JSONEachRow";
    }

    /** 每报告每 signature 仅计一次，设备在各桶内去重。 */
    public static String trend(MemoryLeakQueryFilter filter,long seconds) {
        return "WITH " + matched(filter) + " SELECT intDiv(toUnixTimestamp(event_time)," + seconds
                + ")*" + seconds + "*1000 AS bucket_ms,count() AS occurrences,uniqExact(anonymous_device_id) AS devices "
                + "FROM matched GROUP BY bucket_ms ORDER BY bucket_ms FORMAT JSONEachRow";
    }

    /** 先 FINAL 还原完整逻辑报告，匹配后按事件与 signature 保留首条匹配路径。 */
    private static String matched(MemoryLeakQueryFilter filter) {
        StringBuilder where = new StringBuilder("app_id='").append(filter.appId()).append("' AND event_time>=")
                .append(time(filter.from())).append(" AND event_time<").append(time(filter.to()));
        exact(where,"app_version",filter.appVersion()); exact(where,"device_model",filter.deviceModel());
        exact(where,"process_name",filter.processName()); exact(where,"scene",filter.scene());
        exact(where,"manufacturer",filter.manufacturer()); exact(where,"dump_reason",filter.dumpReason());
        exact(where,"anonymous_device_id",filter.anonymousDeviceId());
        if (filter.sdkInt()!=null) where.append(" AND sdk_int=").append(filter.sdkInt());
        String pathPredicate = "signature!='' AND notEmpty(JSONExtractArrayRaw(path,'path'))";
        if (filter.signature()!=null) pathPredicate += " AND signature='"+escape(filter.signature())+"'";
        if (filter.keyword()!=null) {
            // position 是大小写敏感的字面匹配，百分号、下划线与反斜线没有通配符含义。
            String keyword="'"+escape(filter.keyword())+"'";
            pathPredicate += " AND (position(signature,"+keyword+")>0 OR position(JSONExtractString(path,'gcRoot'),"+keyword+")>0"
                    + " OR position(JSONExtractString(path,'leakReason'),"+keyword+")>0"
                    + " OR arrayExists(node -> position(JSONExtractString(node,'reference'),"+keyword+")>0"
                    + " OR position(JSONExtractString(node,'referenceType'),"+keyword+")>0"
                    + " OR position(JSONExtractString(node,'declaredClass'),"+keyword+")>0,JSONExtractArrayRaw(path,'path')))";
        }
        return "expanded AS (SELECT event_id,event_time,anonymous_device_id,app_version,entry.1 AS path,entry.2 AS path_index,"
                + "JSONExtractString(entry.1,'signature') AS signature FROM apm_memory_report FINAL "
                + "ARRAY JOIN arrayZip(JSONExtractArrayRaw(gc_paths_json),arrayEnumerate(JSONExtractArrayRaw(gc_paths_json))) AS entry "
                + "WHERE " + where + "), matched AS (SELECT event_id,event_time,anonymous_device_id,app_version,signature,"
                + "argMin(path,path_index) AS matched_path FROM expanded WHERE " + pathPredicate
                + " GROUP BY event_id,event_time,anonymous_device_id,app_version,signature)";
    }
    /** 固定维度的字面过滤。 */
    private static void exact(StringBuilder where,String column,String value) {
        if (value!=null) where.append(" AND ").append(column).append("='").append(escape(value)).append("'");
    }
    /** 保留默认绝对时间窗的纳秒边界。 */
    private static String time(Instant value) {
        return "toDateTime64('"+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC).format(value)+"',9,'UTC')";
    }
    /** ClickHouse 字符串字面量转义，禁止把关键词解释为模式。 */
    private static String escape(String value) { return value.replace("\\","\\\\").replace("'","\\'"); }
}
