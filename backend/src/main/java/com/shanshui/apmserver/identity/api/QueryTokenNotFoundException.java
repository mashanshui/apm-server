package com.shanshui.apmserver.identity.api;

/** 目标应用下不存在指定查询 Token。 */
public class QueryTokenNotFoundException extends RuntimeException {

    /** 跨应用与不存在使用同一错误。 */
    public QueryTokenNotFoundException() { super("查询 Token 不存在"); }
}
