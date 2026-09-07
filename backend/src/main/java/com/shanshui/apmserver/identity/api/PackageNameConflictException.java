package com.shanshui.apmserver.identity.api;

public class PackageNameConflictException extends RuntimeException {

    public PackageNameConflictException() {
        super("应用包名已存在");
    }
}
