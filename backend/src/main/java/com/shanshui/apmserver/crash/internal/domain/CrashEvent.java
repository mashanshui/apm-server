package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 当前事实关联的一份完整逻辑状态，不额外保存原始副本。 */
public record CrashEvent(
        /** 不变事件事实。 */ EventMetadata metadata,
        /** 不变 Crash 类型。 */ String crashKind,
        /** 不变致命资格。 */ boolean fatal,
        /** 同版本堆栈、归组和来源。 */ CrashState state) implements CrashStoredSignal {
    /** 当前异常类型。 */
    public String exceptionType() { return state.exceptionType(); }
    /** 当前归组。 */
    public String fingerprint() { return state.fingerprint(); }
    /** 当前指纹算法。 */
    public String fingerprintVersion() { return state.fingerprintVersion(); }
    /** 持久化处理状态。 */
    public String symbolicationStatus() { return state.status(); }
}
