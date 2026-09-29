package com.shanshui.apmserver.identity.api;

/** 应用已达到有效查询 Token 数量上限。 */
public class QueryTokenLimitException extends RuntimeException {

    /** 返回固定错误，避免响应中暴露凭据记录。 */
    public QueryTokenLimitException() { super("应用的有效查询 Token 已达到上限"); }
}
