package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 同一状态版本中一个其他线程的当前堆栈和独立处理状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrashThreadStack(
        /** 十进制 Java 线程标识。 */ String javaId,
        /** pending、failed、symbolicated 或 not_applicable。 */ String symbolicationStatus,
        /** 安全失败原因。 */ String symbolicationReason,
        /** 当前唯一堆栈。 */ Stack stack,
        /** 实际处理的符号表身份。 */ UUID symbolFileId,
        /** 实际文件版本。 */ Integer symbolFileRevision,
        /** 实际文件摘要。 */ String symbolFileSha256,
        /** 处理时间。 */ Instant processedAt) {
    /** 不接受未知状态或错误的原始/还原堆栈组合。 */
    public CrashThreadStack {
        if (javaId == null || !javaId.matches("[1-9][0-9]{0,18}") || symbolicationStatus == null
                || !Set.of("pending", "failed", "symbolicated", "not_applicable").contains(symbolicationStatus)
                || stack == null) throw new IllegalArgumentException("线程当前堆栈不合法");
        if ("symbolicated".equals(symbolicationStatus)) {
            if (!"retrace_text".equals(stack.format()) || symbolFileId == null || symbolFileRevision == null
                    || symbolFileRevision < 1 || symbolFileSha256 == null || !symbolFileSha256.matches("[a-f0-9]{64}")
                    || processedAt == null) throw new IllegalArgumentException("线程还原结果缺少来源");
        } else if (!"raw_frames".equals(stack.format())) {
            throw new IllegalArgumentException("未还原线程必须保留原始帧");
        } else if ("not_applicable".equals(symbolicationStatus) && !((List<?>) stack.content()).isEmpty()) {
            throw new IllegalArgumentException("仅空帧线程不适用还原");
        } else if (("pending".equals(symbolicationStatus) || "failed".equals(symbolicationStatus))
                && ((List<?>) stack.content()).isEmpty()) {
            throw new IllegalArgumentException("空帧线程必须标记为不适用还原");
        }
    }

    /** 原始帧数组或完整还原文本，不能同时保存两份。 */
    public record Stack(
            /** raw_frames 或 retrace_text。 */ String format,
            /** 原始帧数组或完整文本。 */ Object content) {
        /** 限定当前支持的格式与内容类型。 */
        public Stack {
            if (!("raw_frames".equals(format) && content instanceof List<?>)
                    && !("retrace_text".equals(format) && content instanceof String text && !text.isBlank())) {
                throw new IllegalArgumentException("线程堆栈格式不合法");
            }
        }
    }
}
