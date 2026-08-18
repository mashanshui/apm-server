package com.shanshui.apmserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.clickhouse")
public class ClickHouseProperties {

    private boolean enabled;
    private String url = "http://localhost:8123";
    private String database = "apm";
    private String username = "apm_readonly";
    private String password = "";
    private String readOnlyUser = "apm_readonly";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getReadOnlyUser() {
        return readOnlyUser;
    }

    public void setReadOnlyUser(String readOnlyUser) {
        this.readOnlyUser = readOnlyUser;
    }
}
