package com.shanshui.apmserver.service;

public class InvalidAppKeyException extends RuntimeException {

    public InvalidAppKeyException() {
        super("App Key 无效或应用不可用");
    }
}
