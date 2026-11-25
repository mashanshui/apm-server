package com.shanshui.apmserver.anr.internal.persistence;

import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 固定首次可用结果，并在跨库确认丢失时重放同一份内容。 */
@Repository
public class AnrSymbolicationPublications {
    /** 可用结果的状态版本高于全部临时失败版本。 */
    public static final long FINAL_VERSION = 9_000_000_000_000_000L;
    /** 最多保留的未确认条数。 */
    private static final int MAX_PENDING = 100;
    /** 未确认载荷总字节上限。 */
    private static final long MAX_PENDING_BYTES = 16L * 1024 * 1024;
    /** 不记录还原正文的发布日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrSymbolicationPublications.class);
    /** 管理库访问。 */
    private final JdbcTemplate jdbc;
    /** 有界短事务。 */
    private final TransactionTemplate transactions;
    /** 派生状态目标存储。 */
    private final AnrSymbolicationStore store;
    /** 确定编码器。 */
    private final ObjectMapper mapper;

    /** 绑定管理库事务和派生状态存储。 */
    public AnrSymbolicationPublications(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                       AnrSymbolicationStore store, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.store = store;
        this.mapper = mapper;
    }

    /** 在第一次成功决定时固定完整字节，之后只允许复用该结果。 */
    public Optional<Publication> register(UUID appId, String eventId, Instant occurredAt, String section, AnrJavaView view) {
        if (!List.of("main", "trace").contains(section)
                || !List.of("symbolicated", "partial", "not_applicable").contains(view.status())) {
            throw new IllegalArgumentException("ANR 发布分区或状态无效");
        }
        String payload = mapper.writeValueAsString(view);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 262144) throw new EventStoreUnavailableException("ANR 还原结果超过发布上限");
        return transactions.execute(status -> {
            Optional<Publication> existing = find(appId, eventId, section);
            if (existing.isPresent()) return existing;
            if (!occurredAt.plus(Duration.ofDays(30)).isAfter(Instant.now())) return Optional.empty();
            // 与其他 ANR 发布者串行检查总积压，避免并发时超出预算。
            jdbc.execute("SELECT pg_advisory_xact_lock(1605315302)");
            existing = find(appId, eventId, section);
            if (existing.isPresent()) return existing;
            Long count = jdbc.queryForObject("SELECT count(*) FROM anr_symbolication_publication WHERE published_at IS NULL", Long.class);
            Long pendingBytes = jdbc.queryForObject("SELECT coalesce(sum(octet_length(pending_payload)),0) FROM anr_symbolication_publication WHERE published_at IS NULL", Long.class);
            if (count >= MAX_PENDING || pendingBytes + bytes > MAX_PENDING_BYTES) {
                throw new EventStoreUnavailableException("ANR 还原发布积压达到上限");
            }
            jdbc.update("""
                    INSERT INTO anr_symbolication_publication(app_id,event_id,section,event_time,expires_at,result_sha256,pending_payload)
                    VALUES (?,?,?,?,?,?,?) ON CONFLICT (app_id,event_id,section) DO NOTHING
                    """, appId, eventId, section, java.sql.Timestamp.from(occurredAt),
                    java.sql.Timestamp.from(occurredAt.plus(Duration.ofDays(30))), digest(payload), payload);
            LOG.info("ANR 还原结果已登记，分区={} 字节={}", section, bytes);
            return find(appId, eventId, section);
        });
    }

    /** 恢复有限数量尚未确认的固定结果。 */
    public List<Publication> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("ANR 发布批次超限");
        return jdbc.query("SELECT * FROM anr_symbolication_publication WHERE published_at IS NULL AND expires_at>clock_timestamp() ORDER BY created_at,app_id,event_id,section LIMIT ?",
                (row, number) -> read(row), limit);
    }

    /** 按登记字节写入并读回相同视图，再删除管理库中的临时正文。 */
    public void publish(Publication publication) {
        if (publication.payload() == null) return;
        if (!digest(publication.payload()).equals(publication.sha256())) {
            throw new IllegalStateException("ANR 待发布结果摘要不一致");
        }
        AnrJavaView view = mapper.readValue(publication.payload(), AnrJavaView.class);
        store.write(publication.appId(), publication.eventId(), publication.occurredAt(), publication.section(), FINAL_VERSION, view);
        if (!store.find(publication.appId(), publication.eventId(), publication.section()).filter(view::equals).isPresent()) {
            throw new EventStoreUnavailableException("ANR 还原结果尚未读回确认");
        }
        jdbc.update("UPDATE anr_symbolication_publication SET pending_payload=NULL,published_at=clock_timestamp() "
                        + "WHERE app_id=? AND event_id=? AND section=? AND result_sha256=? AND published_at IS NULL",
                publication.appId(), publication.eventId(), publication.section(), publication.sha256());
        LOG.info("ANR 还原结果已确认，分区={}", publication.section());
    }

    /** 按事件过期时间有限清理已登记结果。 */
    public int cleanupExpired() {
        return jdbc.update("""
                WITH expired AS (SELECT app_id,event_id,section FROM anr_symbolication_publication
                  WHERE expires_at < clock_timestamp()-interval '5 minutes' ORDER BY expires_at LIMIT 1000)
                DELETE FROM anr_symbolication_publication AS p USING expired AS e
                WHERE p.app_id=e.app_id AND p.event_id=e.event_id AND p.section=e.section
                """);
    }

    /** 查询某分区已登记的首次结果。 */
    private Optional<Publication> find(UUID appId, String eventId, String section) {
        return jdbc.query("SELECT * FROM anr_symbolication_publication WHERE app_id=? AND event_id=? AND section=?",
                (row, number) -> read(row), appId, eventId, section).stream().findFirst();
    }

    /** 从数据库行读取固定发布材料。 */
    private Publication read(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Publication(row.getObject("app_id", UUID.class), row.getString("event_id"), row.getString("section"),
                row.getTimestamp("event_time").toInstant(), row.getString("result_sha256"), row.getString("pending_payload"));
    }

    /** 计算确定的内容摘要，不在日志中输出原文。 */
    private String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", failure);
        }
    }

    /** 未确认发布的固定身份、时间和字节。 */
    public record Publication(UUID appId, String eventId, String section, Instant occurredAt,
                              String sha256, String payload) { }
}
