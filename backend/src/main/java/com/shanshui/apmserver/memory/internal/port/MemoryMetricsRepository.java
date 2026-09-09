package com.shanshui.apmserver.memory.internal.port;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryTrendAggregate;

import java.util.List;

/** 内存概览和趋势统计端口。 */
public interface MemoryMetricsRepository {

    MemoryMetricStats queryPss(MemoryQueryFilter filter);

    MemoryMetricStats queryVss(MemoryQueryFilter filter);

    MemoryMetricStats queryJavaHeap(MemoryQueryFilter filter);

    List<MemoryTrendAggregate> queryTrend(MemoryQueryFilter filter, String metric, String interval);

    String dataSource();
}
