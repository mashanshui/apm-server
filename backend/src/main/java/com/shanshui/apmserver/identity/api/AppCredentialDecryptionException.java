package com.shanshui.apmserver.identity.api;

public class AppCredentialDecryptionException extends RuntimeException {

    public AppCredentialDecryptionException(Throwable cause) {
        super("App Key 暂时无法读取", cause);
    }
}
