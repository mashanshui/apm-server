package com.shanshui.apmserver.anr.internal.port;

import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.domain.AnrCounts;
import com.shanshui.apmserver.anr.internal.domain.AnrQueryFilter;
import com.shanshui.apmserver.anr.api.AnrEventSummary;
import com.shanshui.apmserver.telemetry.api.AppendResult;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** ANR 事件持久化边界。 */
public interface AnrRepository {
    /** 首次写入成功后固定内容，已有事件返回重复。 */
    AppendResult append(AnrEvent event);

    /** 按应用与事件标识读取已保存内容。 */
    Optional<AnrEvent> find(UUID appId, String eventId);

    /** 在存储侧按相同筛选统计事件与设备。 */
    AnrCounts overview(AnrQueryFilter filter);

    /** 在存储侧排序并读取最多一页加一条摘要。 */
    List<AnrEventSummary> list(AnrQueryFilter filter);

    /** 按应用、版本和保留期有限扫描后台候选身份。 */
    List<String> candidateIds(UUID appId, String appVersion, Instant afterTime, String afterEventId, int limit);
}
