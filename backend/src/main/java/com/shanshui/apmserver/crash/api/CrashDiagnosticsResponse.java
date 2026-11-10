package com.shanshui.apmserver.crash.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 组合不可变诊断上下文和同一状态版本的其他线程当前栈。 */
public record CrashDiagnosticsResponse(
        /** 主异常裁剪说明。 */ CrashDiagnostics.CoreTruncation coreTruncation,
        /** 崩溃线程身份。 */ CrashDiagnostics.CrashedThread crashedThread,
        /** 其他线程及各自当前处理状态。 */ OtherThreads otherThreads,
        /** 日志缓冲区。 */ CrashDiagnostics.Logcat logcat,
        /** 内存快照。 */ CrashDiagnostics.Memory memory,
        /** 文件描述符。 */ CrashDiagnostics.OpenFiles openFiles,
        /** 进程信息。 */ CrashDiagnostics.Process process,
        /** 设备信息。 */ CrashDiagnostics.Device device,
        /** Native 线程及同版本原始回溯。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        CrashDiagnostics.NativeThreads nativeThreads) {

    /** 保留采集状态与顺序的其他线程集合。 */
    public record OtherThreads(
            /** 采集状态。 */ String status,
            /** 安全采集原因。 */ String reason,
            /** 是否裁剪。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 可知的线程总数。 */ Integer observedCount,
            /** 可知的遗漏数。 */ Integer omittedCount,
            /** 同一版本的线程详情。 */ List<OtherThread> threads) { }

    /** 单线程元数据和独立的当前栈与符号表来源。 */
    public record OtherThread(
            /** 十进制 Java 线程标识。 */ String javaId,
            /** 已脱敏线程名。 */ String name,
            /** Java 线程状态。 */ String state,
            /** Java 线程优先级。 */ Integer priority,
            /** 是否守护线程。 */ Boolean daemon,
            /** 是否主线程。 */ Boolean mainThread,
            /** 此线程帧是否在源端裁剪。 */ Boolean truncated,
            /** 当前唯一堆栈。 */ CrashThreadStack.Stack stack,
            /** 独立处理状态。 */ String symbolicationStatus,
            /** 安全处理原因。 */ String symbolicationReason,
            /** 实际符号表身份。 */ UUID symbolFileId,
            /** 实际符号表版本。 */ Integer symbolFileRevision,
            /** 实际符号表摘要。 */ String symbolFileSha256,
            /** 实际处理时间。 */ Instant processedAt,
            /** 可可靠取得的系统 TID。 */
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer systemTid,
            /** Native 事件中与崩溃线程的关系。 */
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String relationToCrash) { }
}
