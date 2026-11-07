package com.shanshui.apmserver.crash.internal.domain;

/** 后台有限候选同时携带不可变事实包名，不从混淆文本推断应用身份。 */
public record CrashWorkItem(
        /** 同一完整当前状态。 */ CrashState state,
        /** 绑定应用的原包名。 */ String packageName) {}
