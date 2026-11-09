package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 当前事实关联的一份完整逻辑状态，不额外保存原始副本。 */
public record CrashEvent(
        /** 不变事件事实。 */ EventMetadata metadata,
        /** 不变 Crash 类型。 */ String crashKind,
        /** 不变致命资格。 */ boolean fatal,
        /** 同版本堆栈、归组和来源。 */ CrashState state,
        /** 不可变的非堆栈诊断上下文。 */ String diagnosticsPayload) implements CrashStoredSignal {
    /** 构造不含诊断的既有事件。 */
    public CrashEvent(EventMetadata metadata, String crashKind, boolean fatal, CrashState state) {
        this(metadata, crashKind, fatal, state, "");
    }
    /** 空诊断使用数据库默认语义。 */
    public CrashEvent { diagnosticsPayload = diagnosticsPayload == null ? "" : diagnosticsPayload; }
    /** 当前异常类型。 */
    public String exceptionType() { return state.exceptionType(); }
    /** 当前归组。 */
    public String fingerprint() { return state.fingerprint(); }
    /** 当前指纹算法。 */
    public String fingerprintVersion() { return state.fingerprintVersion(); }
    /** 持久化处理状态。 */
    public String symbolicationStatus() { return state.status(); }
}
