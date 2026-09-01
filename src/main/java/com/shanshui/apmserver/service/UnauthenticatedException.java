package com.shanshui.apmserver.service;

public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("请先登录");
    }
}
