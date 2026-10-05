package com.shanshui.apmserver.memory.internal.port;

import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MemoryLeakReportRepository {
    Optional<MemoryLeakReport> findByEventId(UUID appId, UUID eventId);
    void append(MemoryLeakReport report);
    List<MemoryLeakReport> findAll(MemoryLeakQueryFilter filter);
    /** 全范围总计与最终有序问题页。 */
    com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse issues(MemoryLeakQueryFilter filter, int page,
                                                                    int pageSize,String sort,String order);
    /** 全范围聚合后的非空趋势桶。 */
    List<com.shanshui.apmserver.memory.api.MemoryLeakTrendPoint> trend(MemoryLeakQueryFilter filter,long bucketSeconds);
    /** 返回当前报告事实引用的附件路径；存储不可用时应抛出存储异常。 */
    Set<String> findAttachmentPaths();
    String dataSource();
}
