package com.shanshui.apmserver.crash.internal.port;

import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
import com.shanshui.apmserver.crash.internal.domain.CrashPage;
import com.shanshui.apmserver.crash.api.CrashStats;
import com.shanshui.apmserver.crash.api.CrashTrendPoint;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.internal.application.CrashReferenceQueries;

import java.util.List;
import java.util.Optional;

/** Crash 概览、趋势、Issue、事件页及详情查询端口。 */
public interface CrashQueryPort {

    /** 内存实现复用的参考查询；ClickHouse 实现覆盖各聚合方法。 */
    List<CrashStoredSignal> findAll(java.util.UUID appId);

    /** 读取精确概览统计。 */
    default CrashStats overview(CrashQueryFilter filter) {
        return CrashReferenceQueries.overview(findAll(filter.appId()), filter);
    }

    /** 按 UTC 小时或天读取精确趋势。 */
    default List<CrashTrendPoint> trend(CrashQueryFilter filter, String interval) {
        return CrashReferenceQueries.trend(findAll(filter.appId()), filter, interval);
    }

    /** 按聚合排序读取 Issue 页。 */
    default CrashPage<CrashIssueSummary> issues(CrashQueryFilter filter) {
        return CrashReferenceQueries.issues(findAll(filter.appId()), filter);
    }

    /** 按事件时间和 ID 读取事件摘要页。 */
    default CrashPage<CrashEventSummary> events(CrashQueryFilter filter, String fingerprint) {
        return CrashReferenceQueries.events(findAll(filter.appId()), filter, fingerprint);
    }

    /** 只对详情读取完整异常链。 */
    Optional<CrashStoredSignal> findByEventId(java.util.UUID appId, String eventId);
}
