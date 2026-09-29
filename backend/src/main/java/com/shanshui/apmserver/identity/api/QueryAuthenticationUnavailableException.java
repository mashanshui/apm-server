package com.shanshui.apmserver.identity.api;

/** 持久化不可用不应误报为凭据无效。 */
public class QueryAuthenticationUnavailableException extends RuntimeException {

    /** 保留内部原因供受控诊断，API 响应不泄漏数据库错误。 */
    public QueryAuthenticationUnavailableException(Throwable cause) { super("查询认证暂时不可用", cause); }
}
