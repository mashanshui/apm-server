package com.shanshui.apmserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.stack-parser")
public class StackParserProperties {

    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;

    private long maxArtifactBytes = DEFAULT_MAX_ARTIFACT_BYTES;
    private int maxConcurrentParses = 2;
    private String mappingRoot = "";

    public long getMaxArtifactBytes() {
        return maxArtifactBytes;
    }

    public void setMaxArtifactBytes(long maxArtifactBytes) {
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public int getMaxConcurrentParses() {
        return maxConcurrentParses;
    }

    public void setMaxConcurrentParses(int maxConcurrentParses) {
        this.maxConcurrentParses = maxConcurrentParses;
    }

    public String getMappingRoot() {
        return mappingRoot;
    }

    public void setMappingRoot(String mappingRoot) {
        this.mappingRoot = mappingRoot;
    }
}
