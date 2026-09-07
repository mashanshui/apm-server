package com.shanshui.apmserver.identity.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.auth")
public class AuthProperties {

    private String bootstrapAdminEmail = "";
    private String bootstrapAdminPassword = "";
    private String bootstrapAdminDisplayName = "平台管理员";

    public String getBootstrapAdminEmail() {
        return bootstrapAdminEmail;
    }

    public void setBootstrapAdminEmail(String bootstrapAdminEmail) {
        this.bootstrapAdminEmail = bootstrapAdminEmail;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public String getBootstrapAdminDisplayName() {
        return bootstrapAdminDisplayName;
    }

    public void setBootstrapAdminDisplayName(String bootstrapAdminDisplayName) {
        this.bootstrapAdminDisplayName = bootstrapAdminDisplayName;
    }
}
