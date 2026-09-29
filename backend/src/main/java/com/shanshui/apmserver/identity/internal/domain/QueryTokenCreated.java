package com.shanshui.apmserver.identity.internal.domain;

/** 唯一一次包含完整 Token 的创建响应。 */
public record QueryTokenCreated(QueryTokenMetadata metadata, String token) {
}
