package com.shanshui.apmserver.platform.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.storage")
public class StorageProperties {

    private String mode = "memory";
    private boolean inMemoryAvailable = true;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isInMemoryAvailable() {
        return inMemoryAvailable;
    }

    public void setInMemoryAvailable(boolean inMemoryAvailable) {
        this.inMemoryAvailable = inMemoryAvailable;
    }
}
