package com.shanshui.apmserver.identity.internal.persistence;

public interface AppCredentialIdentity {

    java.util.UUID getAppId();

    String getPackageName();
}
