package com.shanshui.apmserver.service;

public class InvalidProjectKeyException extends RuntimeException {

    public InvalidProjectKeyException() {
        super("项目上报 Key 无效或项目已停用");
    }
}
