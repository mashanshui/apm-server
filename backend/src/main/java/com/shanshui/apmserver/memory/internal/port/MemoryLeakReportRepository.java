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
    /** 返回当前报告事实引用的附件路径；存储不可用时应抛出存储异常。 */
    Set<String> findAttachmentPaths();
    String dataSource();
}
