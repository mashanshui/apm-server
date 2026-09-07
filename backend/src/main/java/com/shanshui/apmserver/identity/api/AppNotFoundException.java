package com.shanshui.apmserver.identity.api;

public class AppNotFoundException extends RuntimeException {

    public AppNotFoundException() {
        super("应用不存在或无查看权限");
    }
}
