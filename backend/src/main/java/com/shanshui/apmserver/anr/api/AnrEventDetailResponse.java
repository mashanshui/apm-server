package com.shanshui.apmserver.anr.api;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 独立事件的原始身份与脱敏现场。 */
public record AnrEventDetailResponse(
        /** 原事件元数据。 */ EventMetadata metadata,
        /** 不可变的 ANR 现场。 */ AnrPayload anr,
        /** 主线程 Java 还原状态。 */ AnrJavaView mainThreadJava,
        /** 系统 trace 中 Java 帧的还原状态。 */ AnrJavaView traceJava) { }
