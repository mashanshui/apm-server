package com.shanshui.apmserver.anr.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shanshui.apmserver.crash.api.CrashDiagnostics;

/** 一次已判定 ANR 的核心信息和独立现场分区。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnrPayload(
        /** Matrix 判定来源。 */ String source,
        /** 判定时的主线程 Java 栈。 */ String mainThreadStack,
        /** 主线程栈是否因预算裁剪。 */ Boolean mainThreadStackTruncated,
        /** 队首消息文本。 */ String message,
        /** Matrix 记录的消息时间差。 */ Long messageWhenMillis,
        /** 判定时可知的前后台状态。 */ Boolean foreground,
        /** 原进程 cgroup 文本。 */ String cgroup,
        /** 系统进程错误状态中的说明。 */ TextPartition anrInfo,
        /** ART 首次命中写入的采集片段。 */ TextPartition trace,
        /** 与 Crash 共享的通用现场。 */ CrashDiagnostics diagnostics) {

    /** 有独立状态和采集时间的文本分区。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextPartition(
            /** 采集状态。 */ String status,
            /** 受控失败或部分采集原因。 */ String reason,
            /** 是否已知裁剪。 */ Boolean truncated,
            /** 原进程采集时间。 */ Long capturedAt,
            /** 脱敏前的有限文本。 */ String text,
            /** trace 的固定来源。 */ String source) { }
}
