package com.shanshui.apmserver.identity.api;

public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("请先登录");
    }
}
