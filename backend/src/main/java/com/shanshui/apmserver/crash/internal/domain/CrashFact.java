package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 当前数据集中的不可变 Crash 或启动事实。 */
public record CrashFact(
        /** 冻结的原始身份、时间与维度。 */ EventMetadata metadata,
        /** Crash 类型；启动事件为空。 */ String crashKind,
        /** 致命资格不依赖是否还有原始堆栈。 */ boolean fatal,
        /** 不含其他线程原始帧的不可变诊断 JSON。 */ String diagnosticsPayload,
        /** 首次采集内容摘要；历史记录为空。 */ String captureSha256) {
    /** 构造缺少诊断的事实，用于既有启动和历史数据。 */
    public CrashFact(EventMetadata metadata, String crashKind, boolean fatal) {
        this(metadata, crashKind, fatal, "", "");
    }

    /** 空字段统一为数据库默认值。 */
    public CrashFact {
        diagnosticsPayload = diagnosticsPayload == null ? "" : diagnosticsPayload;
        captureSha256 = captureSha256 == null ? "" : captureSha256;
    }
}
