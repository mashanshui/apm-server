package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ClickHouse 卡顿查询适配器入口。卡顿事件仓储负责 FINAL 事实/详情读取，
 * 该层保留独立的卡顿筛选边界，避免查询服务退化为 Crash 的 findAll 全量扫描。
 * 后续容量优化可在此处把筛选下推到 apm_jank_issue_hourly 和 apm_jank_event。
 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseJankAggregationRepository implements JankAggregationRepository {

    private final ClickHouseHttpClient client;
    private final ClickHouseJankEventRepository eventRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ClickHouseJankAggregationRepository(ClickHouseHttpClient client,
                                                ClickHouseJankEventRepository eventRepository) {
        this.client = client;
        this.eventRepository = eventRepository;
    }

    @Override
    public List<JankEvent> find(JankQueryFilter filter) {
        return eventRepository.parseJankRows(client.execute(ClickHouseJankQuerySql.selectEvents(filter)));
    }

    @Override
    public Optional<JankEvent> findByEventId(java.util.UUID appId, String eventId) {
        return eventRepository.findByEventId(appId, eventId)
                .filter(JankEvent.class::isInstance).map(JankEvent.class::cast);
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }

}
