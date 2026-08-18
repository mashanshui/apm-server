package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class ProjectKeyAuthenticator {

    private final IngestProperties properties;

    public ProjectKeyAuthenticator(IngestProperties properties) {
        this.properties = properties;
    }

    public String authenticate(String projectKey) {
        if (!properties.isEnabled() || projectKey == null || properties.getProjectKey() == null
                || !MessageDigest.isEqual(projectKey.getBytes(StandardCharsets.UTF_8),
                properties.getProjectKey().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidProjectKeyException();
        }
        return properties.getProjectId();
    }
}
