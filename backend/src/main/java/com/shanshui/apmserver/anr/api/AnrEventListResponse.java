package com.shanshui.apmserver.anr.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 绑定时间和筛选条件的有界 ANR 列表。 */
public record AnrEventListResponse(
        /** 授权应用。 */ UUID appId,
        /** 包含的起始时间。 */ Instant from,
        /** 不包含的结束时间。 */ Instant to,
        /** 当前页摘要。 */ List<AnrEventSummary> events,
        /** 下一页游标。 */ String nextCursor) { }
