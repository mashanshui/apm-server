package com.shanshui.apmserver.identity.api;

public class AppRoleDeniedException extends RuntimeException {

    public AppRoleDeniedException() {
        super("当前角色没有执行该操作的权限");
    }
}
