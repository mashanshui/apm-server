package com.shanshui.apmserver.jank.internal.port;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;

import com.shanshui.apmserver.jank.api.*;
import com.shanshui.apmserver.jank.internal.application.JankCursor;
import java.util.List;
import java.util.Optional;

/** 卡顿查询专用仓库；生产实现负责把应用、时间、行数、超时和维度白名单下推到 ClickHouse。 */
public interface JankAggregationRepository {

    /** 完整范围总览，不使用列表 limit 截断输入。 */
    JankStats overview(JankQueryFilter filter);
    /** 完整范围非空 UTC 趋势桶。 */
    List<JankTrendPoint> trend(JankQueryFilter filter, String interval);
    /** 聚合后按游标分页，最多 limit+1 个问题。 */
    List<JankIssueSummary> issues(JankQueryFilter filter, JankCursor.State cursor);
    /** 标量事件摘要页，不读取完整载荷。 */
    List<JankEventSummary> events(JankQueryFilter filter, JankCursor.State cursor);

    Optional<JankEvent> findByEventId(java.util.UUID appId, String eventId);

    String dataSource();
}
