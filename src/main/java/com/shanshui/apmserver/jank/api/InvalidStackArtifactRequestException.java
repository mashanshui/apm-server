package com.shanshui.apmserver.jank.api;

public class InvalidStackArtifactRequestException extends RuntimeException {

    private final String code;

    public InvalidStackArtifactRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
