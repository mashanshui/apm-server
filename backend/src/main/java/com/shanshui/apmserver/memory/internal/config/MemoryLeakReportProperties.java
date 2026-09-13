package com.shanshui.apmserver.memory.internal.config;

import com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.memory-leak-report")
public class MemoryLeakReportProperties implements MemoryLeakReportConfiguration {
    private long maxMetadataBytes = 2L * 1024 * 1024;
    private long maxReportBytes = 2L * 1024 * 1024;
    private long maxAttachmentBytes = 256L * 1024 * 1024;
    private long maxRequestBytes = 260L * 1024 * 1024;
    private int maxDepth = 32;
    private int maxArrayItems = 1000;
    private int maxPathNodes = 256;
    private int maxStringBytes = 16 * 1024;
    private long retentionDays = 90;
    private long attachmentRetentionDays = 7;
    private long orphanGraceHours = 24;
    private String artifactDirectory = "backend/build/memory-report-artifacts";

    public long getMaxMetadataBytes() { return maxMetadataBytes; }
    public void setMaxMetadataBytes(long value) { maxMetadataBytes = value; }
    public long getMaxReportBytes() { return maxReportBytes; }
    public void setMaxReportBytes(long value) { maxReportBytes = value; }
    public long getMaxAttachmentBytes() { return maxAttachmentBytes; }
    public void setMaxAttachmentBytes(long value) { maxAttachmentBytes = value; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long value) { maxRequestBytes = value; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int value) { maxDepth = value; }
    public int getMaxArrayItems() { return maxArrayItems; }
    public void setMaxArrayItems(int value) { maxArrayItems = value; }
    public int getMaxPathNodes() { return maxPathNodes; }
    public void setMaxPathNodes(int value) { maxPathNodes = value; }
    public int getMaxStringBytes() { return maxStringBytes; }
    public void setMaxStringBytes(int value) { maxStringBytes = value; }
    public long getRetentionDays() { return retentionDays; }
    public void setRetentionDays(long value) { retentionDays = value; }
    public long getAttachmentRetentionDays() { return attachmentRetentionDays; }
    public void setAttachmentRetentionDays(long value) { attachmentRetentionDays = value; }
    public long getOrphanGraceHours() { return orphanGraceHours; }
    public void setOrphanGraceHours(long value) { orphanGraceHours = value; }
    public String getArtifactDirectory() { return artifactDirectory; }
    public void setArtifactDirectory(String value) { artifactDirectory = value; }
}
