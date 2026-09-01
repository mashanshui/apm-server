package com.shanshui.apmserver.repository;

public interface AppCredentialIdentity {

    java.util.UUID getAppId();

    String getPackageName();
}
