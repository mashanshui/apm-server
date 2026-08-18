package com.shanshui.apmserver.repository;

public class EventStoreUnavailableException extends RuntimeException {

    public EventStoreUnavailableException() {
        super("事件分析存储暂时不可用");
    }
}
