package com.shanshui.apmserver.identity.internal.domain;

/** 管理员创建 Token 的唯一可配置输入。 */
public record QueryTokenCreateRequest(String name, Integer expiresInDays) {
}
