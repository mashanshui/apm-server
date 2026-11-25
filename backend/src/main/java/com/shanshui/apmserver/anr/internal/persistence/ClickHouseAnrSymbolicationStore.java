package com.shanshui.apmserver.anr.internal.persistence;

import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.QueryBudget;
import com.shanshui.apmserver.platform.api.QueryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/** ClickHouse 中按分区保存的有界派生视图。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseAnrSymbolicationStore implements AnrSymbolicationStore {
    /** UTC 毫秒存储格式。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    /** ClickHouse 客户端。 */
    private final ClickHouseHttpClient client;
    /** JSON 编码器。 */
    private final ObjectMapper mapper;
    /** 查询资源边界。 */
    private final QueryProperties properties;

    /** 绑定数据库客户端和预算。 */
    public ClickHouseAnrSymbolicationStore(ClickHouseHttpClient client, ObjectMapper mapper, QueryProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 按应用、事件和分区读取唯一当前视图。 */
    @Override
    public Optional<AnrJavaView> find(UUID appId, String eventId, String section) {
        String sql = "SELECT view_json FROM apm_anr_symbolication FINAL WHERE app_id='" + appId
                + "' AND event_id='" + escape(eventId) + "' AND section='" + escape(section) + "' LIMIT 1 FORMAT JSONEachRow";
        var budget = new QueryBudget(properties.getDefaultTimeoutMs(), properties.getMaxRowsToRead(),
                properties.getMaxBytesToRead(), properties.getMaxMemoryUsage(), properties.getMaxResponseBytes());
        var rows = client.decodeJsonEachRow(client.executeQuery(sql, budget), mapper);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(rows.getFirst().path("view_json").asText(), AnrJavaView.class));
        } catch (RuntimeException failure) {
            throw new EventStoreUnavailableException("ANR 还原状态读取失败", failure);
        }
    }

    /** 以稳定分区与版本令牌提交完整视图，确认由调用方读回。 */
    @Override
    public void write(UUID appId, String eventId, Instant occurredAt, String section, long version, AnrJavaView view) {
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("app_id", appId.toString());
            row.put("event_id", eventId);
            row.put("section", section);
            row.put("event_time", TIME.format(occurredAt));
            row.put("state_version", version);
            row.put("view_json", mapper.writeValueAsString(view));
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((appId + ":" + eventId + ":" + section + ":" + version).getBytes(StandardCharsets.UTF_8));
            String token = java.util.HexFormat.of().formatHex(hash);
            client.execute("INSERT INTO apm_anr_symbolication SETTINGS insert_deduplication_token='" + token
                    + "' FORMAT JSONEachRow", mapper.writeValueAsString(row));
        } catch (EventStoreUnavailableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new EventStoreUnavailableException("ANR 还原状态写入失败", failure);
        }
    }

    /** 转义 SQL 字符串。 */
    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
