package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.JankQueryFilter;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ClickHouse 卡顿查询适配器入口。EventRepository 已负责 FINAL 事实/详情读取，
 * 该层保留独立的卡顿筛选边界，避免查询服务退化为 Crash 的 findAll 全量扫描。
 * 后续容量优化可在此处把筛选下推到 apm_jank_issue_hourly 和 apm_jank_event。
 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseJankAggregationRepository implements JankAggregationRepository {

    private final ClickHouseEventRepository eventRepository;
    private final EventRepository fallbackRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ClickHouseJankAggregationRepository(ClickHouseEventRepository eventRepository) {
        this.eventRepository = eventRepository;
        this.fallbackRepository = eventRepository;
    }

    /** 兼容轻量替身，生产 Spring 注入上面的 ClickHouse 实现。 */
    public ClickHouseJankAggregationRepository(EventRepository eventRepository) {
        this.eventRepository = eventRepository instanceof ClickHouseEventRepository clickHouse
                ? clickHouse : null;
        this.fallbackRepository = eventRepository;
    }

    @Override
    public List<StoredEvent> find(JankQueryFilter filter) {
        if (eventRepository != null) {
            return eventRepository.findJankEvents(filter);
        }
        return fallbackRepository.findAll(filter.appId()).stream()
                .filter(StoredEvent::isJank)
                .filter(event -> !event.occurredAt().isBefore(filter.from()) && event.occurredAt().isBefore(filter.to()))
                .filter(event -> matches(filter.appVersion(), event.appVersion()))
                .filter(event -> matches(filter.channel(), event.channel()))
                .filter(event -> matches(filter.environment(), event.environment()))
                .filter(event -> matches(filter.osVersion(), event.osVersion()))
                .filter(event -> matches(filter.deviceModel(), event.deviceModel()))
                .filter(event -> filter.scene() == null || filter.scene().equals(event.jank().scene()))
                .filter(event -> filter.algorithmVersion() == null || filter.algorithmVersion().equals(event.jank().algorithmVersion()))
                .filter(event -> filter.fingerprint() == null || filter.fingerprint().equals(event.crashFingerprint()))
                .toList();
    }

    @Override
    public Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId) {
        return fallbackRepository.findByEventId(appId, eventId).filter(StoredEvent::isJank);
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
