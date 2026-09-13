package com.shanshui.apmserver.memory.api;

/** 内存泄漏报告的可配置边界；上传和附件路径均由服务端控制。 */
public interface MemoryLeakReportConfiguration {
    long getMaxMetadataBytes();
    long getMaxReportBytes();
    long getMaxAttachmentBytes();
    long getMaxRequestBytes();
    int getMaxDepth();
    int getMaxArrayItems();
    int getMaxPathNodes();
    int getMaxStringBytes();
    long getRetentionDays();
    long getAttachmentRetentionDays();
    long getOrphanGraceHours();
    String getArtifactDirectory();
}
