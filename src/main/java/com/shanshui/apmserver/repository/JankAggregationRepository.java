package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.JankQueryFilter;
import com.shanshui.apmserver.domain.StoredEvent;

import java.util.List;
import java.util.Optional;

/** 卡顿查询专用仓库；生产实现负责把应用、时间、行数、超时和维度白名单下推到 ClickHouse。 */
public interface JankAggregationRepository {

    List<StoredEvent> find(JankQueryFilter filter);

    Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId);

    String dataSource();
}
