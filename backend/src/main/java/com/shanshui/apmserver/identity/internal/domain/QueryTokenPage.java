package com.shanshui.apmserver.identity.internal.domain;

import java.util.List;

/** 应用范围内的 Token 元数据分页。 */
public record QueryTokenPage(List<QueryTokenMetadata> items, int page, int size,
                             long totalItems, int totalPages) {
}
