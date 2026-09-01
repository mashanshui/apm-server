package com.shanshui.apmserver.service;

public class AppRoleDeniedException extends RuntimeException {

    public AppRoleDeniedException() {
        super("当前角色没有执行该操作的权限");
    }
}
