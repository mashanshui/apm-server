package com.shanshui.apmserver.symbol.api;

import java.util.List;
import java.util.UUID;

/** 符号表游标分页结果。 */
public record SymbolFilePage(
        UUID appId,
        List<SymbolFileMetadata> items,
        String nextCursor) {
}
