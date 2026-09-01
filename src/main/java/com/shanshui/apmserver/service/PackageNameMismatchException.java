package com.shanshui.apmserver.service;

public class PackageNameMismatchException extends RuntimeException {

    public PackageNameMismatchException() {
        super("上报应用与 App Key 绑定的包名不一致");
    }
}
