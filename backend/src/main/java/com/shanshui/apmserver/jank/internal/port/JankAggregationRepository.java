package com.shanshui.apmserver.jank.internal.port;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;

import java.util.List;
import java.util.Optional;

/** 卡顿查询专用仓库；生产实现负责把应用、时间、行数、超时和维度白名单下推到 ClickHouse。 */
public interface JankAggregationRepository {

    List<JankEvent> find(JankQueryFilter filter);

    Optional<JankEvent> findByEventId(java.util.UUID appId, String eventId);

    String dataSource();
}
