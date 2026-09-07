package com.shanshui.apmserver.identity.api;

/** 根据上报 App Key 解析不可变应用身份。 */
public interface AppKeyAuthentication {

    AuthenticatedApp authenticate(String appKey);
}
