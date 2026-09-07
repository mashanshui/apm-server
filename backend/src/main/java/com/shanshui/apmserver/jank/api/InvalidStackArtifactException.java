package com.shanshui.apmserver.jank.api;

public class InvalidStackArtifactException extends RuntimeException {

    private final String code;

    public InvalidStackArtifactException(String code, String message) {
        super(message);
        this.code = code;
    }

    public InvalidStackArtifactException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
