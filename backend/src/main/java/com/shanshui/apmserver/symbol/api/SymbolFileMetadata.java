package com.shanshui.apmserver.symbol.api;

import java.time.Instant;
import java.util.UUID;

/** 对网页公开的符号表元数据，不包含文件路径和文件内容。 */
public record SymbolFileMetadata(
        UUID symbolId,
        UUID appId,
        String buildId,
        int revision,
        String originalFilename,
        long sizeBytes,
        String sha256,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant updatedAt) {
}
