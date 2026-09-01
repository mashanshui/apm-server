package com.shanshui.apmserver.service;

public class AppCredentialDecryptionException extends RuntimeException {

    public AppCredentialDecryptionException(Throwable cause) {
        super("App Key 暂时无法读取", cause);
    }
}
