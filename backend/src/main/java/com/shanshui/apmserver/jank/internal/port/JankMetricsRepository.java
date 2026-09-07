package com.shanshui.apmserver.jank.internal.port;

import com.shanshui.apmserver.jank.api.FpsMetricAggregate;
import com.shanshui.apmserver.jank.api.MetricDimensionAggregate;
import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;
import com.shanshui.apmserver.jank.api.MetricTrendAggregate;
import com.shanshui.apmserver.jank.api.SuspensionMetricAggregate;

import java.util.List;

/** 场景 FPS 与设备日挂起率的聚合查询边界。 */
public interface JankMetricsRepository {

    List<FpsMetricAggregate> queryFps(MetricQueryFilter filter);

    List<SuspensionMetricAggregate> querySuspension(MetricQueryFilter filter);

    List<MetricTrendAggregate> queryFpsTrend(MetricQueryFilter filter, String interval);

    List<MetricTrendAggregate> querySuspensionTrend(MetricQueryFilter filter);

    List<MetricDimensionAggregate> queryDimensions(MetricQueryFilter filter, String metric, String dimension);

    String dataSource();
}
