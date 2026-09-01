package com.shanshui.apmserver.service;

public class AppAuthenticationUnavailableException extends RuntimeException {

    public AppAuthenticationUnavailableException(Throwable cause) {
        super("App Key 鉴权服务暂时不可用", cause);
    }
}
