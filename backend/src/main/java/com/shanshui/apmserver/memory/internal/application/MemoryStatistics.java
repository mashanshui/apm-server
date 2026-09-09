package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 内存指标统一统计实现。每个指标独立丢弃 null，并按 h=(n-1)*p 做线性插值。
 */
public final class MemoryStatistics {

    private MemoryStatistics() {
    }

    public static MemoryMetricStats of(Collection<Long> values) {
        List<Double> sorted = values == null ? List.of() : values.stream()
                .filter(value -> value != null)
                .map(Long::doubleValue)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return new MemoryMetricStats(0, null, null, null, null, null, "no_data");
        }
        double sum = 0;
        for (double value : sorted) {
            sum += value;
        }
        return new MemoryMetricStats(sorted.size(), sum / sorted.size(), percentile(sorted, 0.50),
                percentile(sorted, 0.90), percentile(sorted, 0.95), percentile(sorted, 0.99), "ok");
    }

    public static Double percentile(List<Double> sortedValues, double probability) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return null;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double h = (sortedValues.size() - 1) * probability;
        int lower = (int) Math.floor(h);
        int upper = (int) Math.ceil(h);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = h - lower;
        return sortedValues.get(lower) + (sortedValues.get(upper) - sortedValues.get(lower)) * fraction;
    }

    public static List<Double> asSorted(Collection<Long> values) {
        if (values == null) {
            return List.of();
        }
        List<Double> sorted = new ArrayList<>();
        for (Long value : values) {
            if (value != null) {
                sorted.add(value.doubleValue());
            }
        }
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }
}
