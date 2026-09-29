package com.shanshui.apmserver.identity.api;

import java.util.UUID;

/** 仅按已认证 Token 推导的应用 ID 读取基本信息。 */
public interface QueryApplicationLookup {

    /** 读取当前应用，且不暴露 App Key 或成员信息。 */
    QueryApplicationInfo get(UUID appId);
}
