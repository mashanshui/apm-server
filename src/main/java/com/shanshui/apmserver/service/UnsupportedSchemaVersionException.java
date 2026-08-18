package com.shanshui.apmserver.service;

public class UnsupportedSchemaVersionException extends RuntimeException {

    public UnsupportedSchemaVersionException(String message) {
        super(message);
    }
}
