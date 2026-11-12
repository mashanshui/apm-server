package com.shanshui.apmserver.symbol.api;

import java.time.Instant;
import java.util.UUID;

/** 对网页公开的符号表元数据，不包含文件路径和文件内容。 */
public record SymbolFileMetadata(
        /** 符号登记身份。 */ UUID symbolId,
        /** 所属应用。 */ UUID appId,
        /** 文件类型。 */ String type,
        /** 登记版本；旧登记没有版本时为空。 */ String appVersion,
        /** mapping 的默认或构建专用范围；旧登记和 SO 不适用。 */ String mappingScope,
        /** 应用构建；版本默认 mapping 和 SO 为空。 */ String buildId,
        /** SO 的 ABI，mapping 不适用。 */ String abi,
        /** SO 的完整 ELF Build ID，mapping 不适用。 */ String elfBuildId,
        /** SO 是否包含已定义的函数符号。 */ Boolean hasFunctionSymbols,
        /** SO 是否包含调试信息。 */ Boolean hasDebugInfo,
        /** SO 是否包含调试行信息。 */ Boolean hasDebugLine,
        /** 文件修订号。 */ int revision,
        /** 上传的文件名。 */ String originalFilename,
        /** 文件字节数。 */ long sizeBytes,
        /** 文件内容摘要。 */ String sha256,
        /** 最近上传者。 */ UUID uploadedBy,
        /** 创建时间。 */ Instant uploadedAt,
        /** 最近修改时间。 */ Instant updatedAt) {
}
