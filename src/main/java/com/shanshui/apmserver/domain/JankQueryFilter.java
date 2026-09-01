package com.shanshui.apmserver.domain;

import java.time.Instant;

/** 卡顿查询的已解析、白名单筛选条件；不允许把请求字段直接拼接到 SQL。 */
public record JankQueryFilter(
        java.util.UUID appId,
        Instant from,
        Instant to,
        String appVersion,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String scene,
        String algorithmVersion,
        String fingerprint,
        int limit,
        String cursor,
        long timeoutMs) {
}
