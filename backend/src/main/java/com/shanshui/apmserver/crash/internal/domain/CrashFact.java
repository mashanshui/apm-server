package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 当前数据集中的不可变 Crash 或启动事实。 */
public record CrashFact(
        /** 冻结的原始身份、时间与维度。 */ EventMetadata metadata,
        /** Crash 类型；启动事件为空。 */ String crashKind,
        /** 致命资格不依赖是否还有原始堆栈。 */ boolean fatal) {
}
