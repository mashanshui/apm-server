package com.shanshui.apmserver.anr.internal.domain;

import com.shanshui.apmserver.anr.api.AnrPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 已校验并脱敏的不可变 ANR 事件。 */
public record AnrEvent(
        /** 原始事件身份和发生时间。 */ EventMetadata metadata,
        /** 独立 ANR 现场。 */ AnrPayload payload) { }
