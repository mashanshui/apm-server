package com.shanshui.apmserver.service;

public class AppCredentialCreationException extends RuntimeException {

    public AppCredentialCreationException(Throwable cause) {
        super("App Key 创建失败", cause);
    }
}
