package com.shanshui.apmserver.ingest.api;

public class InvalidBatchException extends RuntimeException {

    public InvalidBatchException(String message) {
        super(message);
    }
}
