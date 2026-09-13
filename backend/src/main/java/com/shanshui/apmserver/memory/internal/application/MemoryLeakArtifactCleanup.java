package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 定期清理附件临时文件和过期文件；无法读取报告存储时跳过引用判定。 */
@Component
public class MemoryLeakArtifactCleanup {
    private final MemoryLeakReportRepository repository;
    private final MemoryLeakArtifactStore artifactStore;

    public MemoryLeakArtifactCleanup(MemoryLeakReportRepository repository, MemoryLeakArtifactStore artifactStore) {
        this.repository = repository;
        this.artifactStore = artifactStore;
    }

    @Scheduled(fixedDelayString = "${APM_MEMORY_REPORT_CLEANUP_INTERVAL_MS:3600000}", initialDelayString = "${APM_MEMORY_REPORT_CLEANUP_INITIAL_DELAY_MS:60000}")
    public void cleanup() {
        try {
            artifactStore.cleanup(repository.findAttachmentPaths(), Instant.now());
        } catch (EventStoreUnavailableException ignored) {
            // 数据库不可用时不判断孤立附件，避免误删仍被报告引用的文件。
        }
    }
}
