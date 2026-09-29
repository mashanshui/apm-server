package com.shanshui.apmserver.identity.api;

/** 每个 Agent 请求都必须重新检查查询凭据。 */
public interface QueryTokenAuthentication {

    /** 验证原始 Token 并返回唯一应用身份。 */
    AuthenticatedQueryToken authenticate(String token);
}
