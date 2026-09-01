package com.shanshui.apmserver.domain;

public enum AppRole {
    OWNER,
    ADMIN,
    DEVELOPER,
    VIEWER;

    public boolean canEditApp() {
        return this == OWNER || this == ADMIN;
    }
}
