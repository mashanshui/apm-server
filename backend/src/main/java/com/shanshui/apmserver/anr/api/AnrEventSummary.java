package com.shanshui.apmserver.anr.api;

import java.time.Instant;

/** 列表所需的有限事件摘要，不包含诊断正文。 */
public record AnrEventSummary(
        /** 原事件标识。 */ String eventId,
        /** 原发生时间。 */ Instant occurredAt,
        /** 应用版本。 */ String appVersion,
        /** 版本编号。 */ Integer versionCode,
        /** 设备型号。 */ String deviceModel,
        /** Matrix 判定来源。 */ String source) { }
