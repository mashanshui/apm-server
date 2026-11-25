package com.shanshui.apmserver.anr.internal.domain;

/** 数据库计算出的逻辑事件数和设备数。 */
public record AnrCounts(long eventCount, long deviceCount) { }
