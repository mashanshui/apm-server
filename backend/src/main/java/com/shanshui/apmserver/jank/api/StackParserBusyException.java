package com.shanshui.apmserver.jank.api;

public class StackParserBusyException extends RuntimeException {

    public StackParserBusyException() {
        super("堆栈解析资源繁忙，请稍后重试");
    }
}
