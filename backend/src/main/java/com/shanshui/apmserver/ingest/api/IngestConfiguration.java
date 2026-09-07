package com.shanshui.apmserver.ingest.api;

/** 公共 HTTP 接收与批次协议只读配置。 */
public interface IngestConfiguration {
    boolean isEnabled();
    int getSupportedSchemaVersion();
    int getMaxRequestBytes();
    int getMaxDecompressedBytes();
}
