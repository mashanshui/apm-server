package com.shanshui.apmserver.anr.internal.persistence;

import com.shanshui.apmserver.anr.api.AnrPayload;
import com.shanshui.apmserver.anr.api.AnrEventSummary;
import com.shanshui.apmserver.anr.internal.domain.AnrCounts;
import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.domain.AnrQueryFilter;
import com.shanshui.apmserver.anr.internal.port.AnrRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.QueryBudget;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ClickHouse ANR 写入适配器，单实例内串行裁决同一事件身份。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseAnrRepository implements AnrRepository {
    /** 不包含诊断正文的存储日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseAnrRepository.class);
    /** ClickHouse UTC 毫秒格式。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    /** 数据库 HTTP 客户端。 */
    private final ClickHouseHttpClient client;
    /** 与接收链共用的 JSON 映射器。 */
    private final ObjectMapper mapper;
    /** 数据库分析查询资源限制。 */
    private final QueryProperties queryProperties;

    /** 使用配置的 ClickHouse 和 JSON 编码器。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ClickHouseAnrRepository(ClickHouseHttpClient client, ObjectMapper mapper, QueryProperties queryProperties) {
        this.client = client;
        this.mapper = mapper;
        this.queryProperties = queryProperties;
    }

    /** 测试使用平台默认查询预算。 */
    public ClickHouseAnrRepository(ClickHouseHttpClient client, ObjectMapper mapper) {
        this(client, mapper, new QueryProperties());
    }

    /** 先查重，使用固定去重令牌写入，并读取确认持久化结果。 */
    @Override
    public synchronized AppendResult append(AnrEvent event) {
        try {
            var metadata = event.metadata();
            if (find(metadata.appId(), metadata.eventId()).isPresent()) return new AppendResult(0, 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", metadata.appId().toString());
            row.put("event_id", metadata.eventId());
            row.put("event_time", format(metadata.occurredAt()));
            row.put("received_time", format(metadata.receivedAt()));
            row.put("first_seen_version", Long.MAX_VALUE - metadata.receivedAt().toEpochMilli());
            row.put("app_version", metadata.appVersion());
            row.put("version_code", metadata.versionCode());
            row.put("device_model", metadata.deviceModel());
            row.put("source", event.payload().source());
            row.put("anonymous_device_id", metadata.anonymousDeviceId());
            row.put("metadata_json", mapper.writeValueAsString(metadata));
            row.put("payload_json", mapper.writeValueAsString(event.payload()));
            String token = token(metadata.appId(), metadata.eventId());
            client.execute("INSERT INTO apm_anr_events SETTINGS insert_deduplication_token='" + token + "' FORMAT JSONEachRow",
                    mapper.writeValueAsString(row) + "\n");
            if (find(metadata.appId(), metadata.eventId()).isEmpty()) throw new EventStoreUnavailableException();
            LOG.info("ANR 事件持久化已确认，appId={}", metadata.appId());
            return new AppendResult(1, 0);
        } catch (EventStoreUnavailableException failure) {
            LOG.warn("ANR 存储未确认");
            throw failure;
        } catch (RuntimeException failure) {
            LOG.warn("ANR 存储失败，类型={}", failure.getClass().getSimpleName());
            throw new EventStoreUnavailableException("ANR 存储暂时不可用", failure);
        }
    }

    /** 只按应用和事件标识读取一条已确认的原始现场。 */
    @Override
    public Optional<AnrEvent> find(UUID appId, String eventId) {
        try {
            String sql = "SELECT metadata_json, payload_json FROM apm_anr_events FINAL WHERE app_id='"
                    + appId + "' AND event_id='" + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow";
            var rows = client.decodeJsonEachRow(client.executeQuery(sql, budget(queryProperties.getDefaultTimeoutMs())), mapper);
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.getFirst();
            return Optional.of(new AnrEvent(mapper.readValue(row.path("metadata_json").asText(), EventMetadata.class),
                    mapper.readValue(row.path("payload_json").asText(), AnrPayload.class)));
        } catch (EventStoreUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new EventStoreUnavailableException("ANR 读取暂时不可用", failure);
        }
    }

    /** 在数据库过滤并统计逻辑事件和匿名设备。 */
    @Override
    public AnrCounts overview(AnrQueryFilter filter) {
        String sql = "SELECT count() AS event_count, uniqExact(anonymous_device_id) AS device_count "
                + "FROM apm_anr_events FINAL WHERE " + where(filter)
                + " SETTINGS optimize_move_to_prewhere_if_final=0 FORMAT JSONEachRow";
        var rows = client.decodeJsonEachRow(client.executeQuery(sql, budget(filter.timeoutMs())), mapper);
        if (rows.size() != 1) throw new EventStoreUnavailableException("ANR 统计结果无效");
        return new AnrCounts(rows.getFirst().path("event_count").asLong(), rows.getFirst().path("device_count").asLong());
    }

    /** 在数据库完成筛选、排序和分页，投影不包含原始现场。 */
    @Override
    public List<AnrEventSummary> list(AnrQueryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT event_id, event_time, app_version, version_code, device_model, source "
                + "FROM apm_anr_events FINAL WHERE ").append(where(filter));
        if (filter.afterTime() != null) {
            String time = dateLiteral(filter.afterTime());
            sql.append(" AND (event_time < ").append(time).append(" OR (event_time = ").append(time)
                    .append(" AND event_id < '").append(escape(filter.afterId())).append("'))");
        }
        sql.append(" ORDER BY event_time DESC, event_id DESC LIMIT ").append(filter.limit() + 1)
                .append(" SETTINGS optimize_move_to_prewhere_if_final=0 FORMAT JSONEachRow");
        var rows = client.decodeJsonEachRow(client.executeQuery(sql.toString(), budget(filter.timeoutMs())), mapper);
        return rows.stream().map(row -> new AnrEventSummary(row.path("event_id").asText(),
                parseTime(row.path("event_time").asText()), row.path("app_version").asText(),
                row.path("version_code").asInt(), row.path("device_model").asText(), row.path("source").asText())).toList();
    }

    /** 后台只读取指定应用版本的有限身份，不扫描诊断正文。 */
    @Override
    public List<String> candidateIds(UUID appId, String appVersion, Instant afterTime, String afterEventId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("ANR 候选批次超限");
        StringBuilder sql = new StringBuilder("SELECT event_id FROM apm_anr_events FINAL WHERE app_id='")
                .append(appId).append("' AND app_version='").append(escape(appVersion))
                .append("' AND event_time >= ").append(dateLiteral(afterTime));
        if (afterEventId != null) sql.append(" AND event_id > '").append(escape(afterEventId)).append("'");
        sql.append(" ORDER BY event_id LIMIT ").append(limit)
                .append(" SETTINGS optimize_move_to_prewhere_if_final=0 FORMAT JSONEachRow");
        return client.decodeJsonEachRow(client.executeQuery(sql.toString(), budget(queryProperties.getDefaultTimeoutMs())), mapper)
                .stream().map(row -> row.path("event_id").asText()).toList();
    }

    /** 拼接已经规范化的应用、时间和可选筛选。 */
    private String where(AnrQueryFilter filter) {
        StringBuilder where = new StringBuilder("app_id='").append(filter.appId()).append("' AND event_time >= ")
                .append(dateLiteral(filter.from())).append(" AND event_time < ").append(dateLiteral(filter.to()));
        if (filter.appVersion() != null) where.append(" AND app_version='").append(escape(filter.appVersion())).append("'");
        if (filter.source() != null) where.append(" AND source='").append(escape(filter.source())).append("'");
        return where.toString();
    }

    /** 复用平台统一行数、字节、内存与响应限制。 */
    private QueryBudget budget(long timeoutMs) {
        return new QueryBudget(timeoutMs, queryProperties.getMaxRowsToRead(), queryProperties.getMaxBytesToRead(),
                queryProperties.getMaxMemoryUsage(), queryProperties.getMaxResponseBytes());
    }

    /** 使用 UTC 毫秒构造数据库时间常量。 */
    private String dateLiteral(Instant value) {
        return "toDateTime64('" + format(value) + "', 3, 'UTC')";
    }

    /** 解析 ClickHouse UTC 毫秒结果。 */
    private Instant parseTime(String value) {
        try {
            if (value.indexOf('T') >= 0) return Instant.parse(value);
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"))
                    .toInstant(ZoneOffset.UTC);
        } catch (RuntimeException failure) {
            throw new EventStoreUnavailableException("ANR 事件时间无效", failure);
        }
    }

    /** 规范化数据库时间。 */
    private String format(Instant value) {
        return TIME.format(value);
    }

    /** 转义事件标识中的 SQL 字符。 */
    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** 以应用和事件身份生成固定且不泄露原值的写入令牌。 */
    private String token(UUID appId, String eventId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((appId + ":" + eventId).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", failure);
        }
    }
}
