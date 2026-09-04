package com.shanshui.apmserver.identity.api;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("邮箱或密码错误");
    }
}
