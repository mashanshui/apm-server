package com.shanshui.apmserver.crash.api;

/** 同一已发布状态的分析材料，读取不调用还原或当前文件注册表。 */
public record CrashAnalysisSnapshot(
        /** 完整当前详情，分析域显式裁剪设备身份。 */ CrashEventDetailResponse detail) {}
