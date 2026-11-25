package com.shanshui.apmserver.anr.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** 单个 ANR 栈分区的 Java 还原状态和固定来源。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnrJavaView(
        /** 等待、失败、部分成功、成功或不适用。 */ String status,
        /** 安全原因类别。 */ String reason,
        /** 可用时的派生文本。 */ String text,
        /** 实际使用的符号文件。 */ UUID symbolFileId,
        /** 固定的文件修订。 */ Integer symbolFileRevision,
        /** 固定文件的摘要。 */ String symbolFileSha256,
        /** 本次处理时间。 */ Instant processedAt,
        /** 临时失败后的下次尝试时间。 */ Instant retryAt) { }
