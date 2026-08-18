package com.shanshui.apmserver.service;

public class ProjectAccessDeniedException extends RuntimeException {

    public ProjectAccessDeniedException() {
        super("项目不存在或无查看权限");
    }
}
