package com.shanshui.apmserver.memory.api;

/** 单项观测峰值及首次达到此值时的上下文。 */
public record MemoryPeak(
        /** 峰值字节数。 */ Long bytes,
        /** 峰值采样时刻。 */ Long occurredAt,
        /** 峰值时前台状态。 */ Boolean foreground,
        /** 峰值时 Activity，未知为空。 */ String scene) { }
