package com.shanshui.apmserver.crash.api;

/** 同一次详情还原的分析材料，不作为日常查询缓存。 */
public record CrashAnalysisSnapshot(
        /** 完整事件详情；分析域自行裁剪及脱敏。 */ CrashEventDetailResponse detail,
        /** 本次租约固定 mapping 的摘要，没有 mapping 时为 null。 */ String mappingSha256) {
}
