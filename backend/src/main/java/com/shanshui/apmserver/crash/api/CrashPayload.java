package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrashPayload(
        /** Crash 类型。 */ String kind,
        /** 是否为致命异常。 */ Boolean fatal,
        /** 只属于崩溃线程的异常链。 */ List<ThrowableNode> throwableChain,
        /** 可选现场；缺失时序列化主异常不会引入额外字段。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) CrashDiagnostics diagnostics,
        /** Native 原始材料，与 JVM 异常链互斥。 */ NativeCrash nativeCrash) {

    /** 构造带可选诊断的 JVM 异常。 */
    public CrashPayload(String kind, Boolean fatal, List<ThrowableNode> throwableChain, CrashDiagnostics diagnostics) {
        this(kind, fatal, throwableChain, diagnostics, null);
    }

    /** 构造不含诊断的核心异常。 */
    public CrashPayload(String kind, Boolean fatal, List<ThrowableNode> throwableChain) {
        this(kind, fatal, throwableChain, null, null);
    }
}
