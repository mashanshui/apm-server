package com.shanshui.apmserver.anr.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** 已规范化并绑定应用的 ANR 查询条件。 */
public record AnrQueryFilter(
        /** 应用。 */ UUID appId,
        /** 包含的起始时间。 */ Instant from,
        /** 不包含的结束时间。 */ Instant to,
        /** 可选版本。 */ String appVersion,
        /** 可选判定来源。 */ String source,
        /** 每页数量。 */ int limit,
        /** 上页末尾发生时间。 */ Instant afterTime,
        /** 上页末尾事件标识。 */ String afterId,
        /** 数据库查询超时。 */ long timeoutMs) { }
