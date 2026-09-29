package com.shanshui.apmserver.identity.api;

/** 缺失、未知、撤销、到期的凭据使用同一认证错误。 */
public class InvalidQueryTokenException extends RuntimeException {

    /** 不包含来访凭据或其前缀。 */
    public InvalidQueryTokenException() { super("应用查询 Token 无效"); }
}
