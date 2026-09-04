package com.shanshui.apmserver.identity.api;

public class InvalidAppInputException extends RuntimeException {

    private final String field;
    private final String code;

    public InvalidAppInputException(String field, String code, String message) {
        super(message);
        this.field = field;
        this.code = code;
    }

    public String getField() {
        return field;
    }

    public String getCode() {
        return code;
    }
}
