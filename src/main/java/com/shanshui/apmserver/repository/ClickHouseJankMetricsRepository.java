package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.FpsMetricAggregate;
import com.shanshui.apmserver.domain.MetricDimensionAggregate;
import com.shanshui.apmserver.domain.MetricQueryFilter;
import com.shanshui.apmserver.domain.MetricTrendAggregate;
import com.shanshui.apmserver.domain.SuspensionMetricAggregate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/** ClickHouse 指标聚合适配器；非 Spring 测试可通过 EventRepository 使用内存回退。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseJankMetricsRepository implements JankMetricsRepository {

    private final ClickHouseEventRepository clickHouseRepository;
    private final JankMetricsRepository fallbackRepository;

    @Autowired
    public ClickHouseJankMetricsRepository(ClickHouseEventRepository clickHouseRepository) {
        this.clickHouseRepository = clickHouseRepository;
        this.fallbackRepository = null;
    }

    /** 兼容不启动 Spring 的测试或替身。 */
    public ClickHouseJankMetricsRepository(EventRepository eventRepository) {
        this.clickHouseRepository = eventRepository instanceof ClickHouseEventRepository clickHouse
                ? clickHouse : null;
        this.fallbackRepository = new InMemoryJankMetricsRepository(eventRepository);
    }

    @Override
    public List<FpsMetricAggregate> queryFps(MetricQueryFilter filter) {
        return clickHouseRepository != null ? clickHouseRepository.queryFpsMetrics(filter)
                : fallbackRepository.queryFps(filter);
    }

    @Override
    public List<SuspensionMetricAggregate> querySuspension(MetricQueryFilter filter) {
        return clickHouseRepository != null ? clickHouseRepository.querySuspensionMetrics(filter)
                : fallbackRepository.querySuspension(filter);
    }

    @Override
    public List<MetricTrendAggregate> queryFpsTrend(MetricQueryFilter filter, String interval) {
        return clickHouseRepository != null ? clickHouseRepository.queryFpsTrend(filter, interval)
                : fallbackRepository.queryFpsTrend(filter, interval);
    }

    @Override
    public List<MetricTrendAggregate> querySuspensionTrend(MetricQueryFilter filter) {
        return clickHouseRepository != null ? clickHouseRepository.querySuspensionTrend(filter)
                : fallbackRepository.querySuspensionTrend(filter);
    }

    @Override
    public List<MetricDimensionAggregate> queryDimensions(MetricQueryFilter filter, String metric, String dimension) {
        return clickHouseRepository != null ? clickHouseRepository.queryMetricDimensions(filter, metric, dimension)
                : fallbackRepository.queryDimensions(filter, metric, dimension);
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }
}
