package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.jank.internal.application.JankCursor;
import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.api.*;
import com.shanshui.apmserver.platform.api.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** ClickHouse 返回完整聚合或最终标量页，完整证据只由独立详情操作读取。 */
@Repository
@ConditionalOnProperty(name="apm.storage.mode", havingValue="clickhouse")
public class ClickHouseJankAggregationRepository implements JankAggregationRepository {
    /** 平台客户端同时限制数据库与响应资源。 */
    private final ClickHouseHttpClient client;
    /** 详情沿用原事件仓储，不参与列表统计。 */
    private final ClickHouseJankEventRepository eventRepository;
    /** 统一服务端扫描、内存及响应预算。 */
    private final QueryProperties properties;
    /** 解码 JSONEachRow 标量。 */
    private final ObjectMapper mapper;

    /** 注入领域与平台依赖。 */
    public ClickHouseJankAggregationRepository(ClickHouseHttpClient client, ClickHouseJankEventRepository events,
                                               QueryProperties properties, ObjectMapper mapper) {
        this.client=client; this.eventRepository=events; this.properties=properties; this.mapper=mapper;
    }
    /** 全范围单行聚合。 */
    @Override public JankStats overview(JankQueryFilter filter) {
        return stats(rows(ClickHouseJankQuerySql.overview(filter),filter).getFirst());
    }
    /** 返回非空 UTC 桶，每桶统计覆盖全部匹配事件。 */
    @Override public List<JankTrendPoint> trend(JankQueryFilter filter, String interval) {
        return rows(ClickHouseJankQuerySql.trend(filter,interval),filter).stream().map(row -> {
            // ClickHouse 毫秒桶起点转换回统一 Instant。
            Instant start=instant(row,"bucket_ms");
            return new JankTrendPoint(start,start.plus(1,"hour".equals(interval)?ChronoUnit.HOURS:ChronoUnit.DAYS),stats(row));
        }).toList();
    }
    /** Issue 的完整统计已经在数据库完成，Java 只映射 limit+1 行。 */
    @Override public List<JankIssueSummary> issues(JankQueryFilter filter, JankCursor.State cursor) {
        return rows(ClickHouseJankQuerySql.issues(filter,cursor),filter).stream().map(row ->
                new JankIssueSummary(text(row,"fingerprint"),row.path("representative").get(0).asText(),
                        row.path("representative").get(1).asText(),row.path("representative").get(2).asText(),
                        row.path("event_count").asLong(),row.path("sessions").asLong(),row.path("devices").asLong(),
                        instant(row,"first_ms"),instant(row,"last_ms"),percentiles(row,"exact"),percentiles(row,"estimated")))
                .toList();
    }
    /** 摘要从标量列恢复，缺失字段不强制转换为零。 */
    @Override public List<JankEventSummary> events(JankQueryFilter filter, JankCursor.State cursor) {
        return rows(ClickHouseJankQuerySql.eventSummaries(filter,cursor),filter).stream().map(row ->
                new JankEventSummary(text(row,"event_id"),instant(row,"event_ms"),text(row,"app_version"),
                        row.path("version_code").asInt(),text(row,"build_id"),text(row,"channel"),text(row,"environment"),
                        text(row,"os_version"),text(row,"device_model"),text(row,"session_id"),text(row,"anonymous_device_id"),
                        text(row,"scene"),text(row,"algorithm_version"),text(row,"fingerprint"),text(row,"fingerprint_version"),
                        millis(row,"message_duration_ns"),millis(row,"estimated_duration_ns"),
                        millis(row,"estimated_unattributed_duration_ns"),millis(row,"covered_duration_ns"),millis(row,"uncovered_duration_ns")))
                .toList();
    }
    /** 完整载荷仅单事件详情读取。 */
    @Override public Optional<JankEvent> findByEventId(java.util.UUID appId,String eventId) {
        return eventRepository.findByEventId(appId,eventId).filter(JankEvent.class::isInstance).map(JankEvent.class::cast);
    }
    /** 公开响应沿用原数据源标记。 */
    @Override public String dataSource() { return "clickhouse"; }

    /** 每个用途只执行一次有界查询，不把失败伪造成空行。 */
    private List<JsonNode> rows(String sql,JankQueryFilter filter) {
        return client.decodeJsonEachRow(client.executeQuery(sql,new QueryBudget(filter.timeoutMs(),
                properties.getMaxRowsToRead(),properties.getMaxBytesToRead(),properties.getMaxMemoryUsage(),
                properties.getMaxResponseBytes())),mapper);
    }
    /** 空聚合保留 null 分位数与 no_data。 */
    private JankStats stats(JsonNode row) {
        return new JankStats(row.path("event_count").asLong(),row.path("sessions").asLong(),row.path("devices").asLong(),
                row.path("groupable").asLong(),percentiles(row,"exact"),row.path("event_count").asLong()==0?"no_data":"ok");
    }
    /** 精确分位数已由数据库按秩计算。 */
    private JankDurationPercentiles percentiles(JsonNode row,String prefix) {
        return new JankDurationPercentiles(number(row,prefix+"50"),number(row,prefix+"90"),number(row,prefix+"99"));
    }
    /** 保留 null，不把无数据映射为零。 */
    private Double number(JsonNode row,String field) { return row.path(field).isNull()||row.path(field).isMissingNode()?null:row.path(field).asDouble(); }
    /** 纳秒标量仅在展示协议转换为毫秒。 */
    private Double millis(JsonNode row,String field) { Double value=number(row,field); return value==null?null:value/1_000_000.0; }
    /** 可空标识的空字符串还原为 null。 */
    private String text(JsonNode row,String field) { String value=row.path(field).asText(); return value.isEmpty()?null:value; }
    /** 读取数据库毫秒时间。 */
    private Instant instant(JsonNode row,String field) { return Instant.ofEpochMilli(row.path(field).asLong()); }
}
