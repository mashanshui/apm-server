package com.shanshui.apmserver.service;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("邮箱或密码错误");
    }
}
