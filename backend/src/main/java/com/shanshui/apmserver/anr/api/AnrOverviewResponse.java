package com.shanshui.apmserver.anr.api;

import java.time.Instant;
import java.util.UUID;

/** 当前筛选下的事件和受影响设备数量。 */
public record AnrOverviewResponse(
        /** 授权应用。 */ UUID appId,
        /** 包含的起始时间。 */ Instant from,
        /** 不包含的结束时间。 */ Instant to,
        /** 逻辑事件数。 */ long eventCount,
        /** 去重的匿名设备数。 */ long deviceCount) { }
