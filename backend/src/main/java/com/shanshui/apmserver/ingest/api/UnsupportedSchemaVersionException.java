package com.shanshui.apmserver.ingest.api;

public class UnsupportedSchemaVersionException extends RuntimeException {

    public UnsupportedSchemaVersionException(String message) {
        super(message);
    }
}
