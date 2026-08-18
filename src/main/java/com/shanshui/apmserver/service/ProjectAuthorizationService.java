package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.QueryProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class ProjectAuthorizationService {

    private final QueryProperties properties;

    public ProjectAuthorizationService(QueryProperties properties) {
        this.properties = properties;
    }

    public void requireView(String projectId, HttpServletRequest request) {
        if (!properties.isRequireProjectHeader()) {
            return;
        }
        String requestedProject = request.getHeader("X-Project-Id");
        String allowedProjects = request.getHeader("X-User-Project-Ids");
        boolean matchesRequested = projectId != null && projectId.equals(requestedProject);
        boolean belongsToUser = allowedProjects == null || Arrays.stream(allowedProjects.split(","))
                .map(String::trim)
                .anyMatch(projectId::equals);
        if (!matchesRequested || !belongsToUser) {
            throw new ProjectAccessDeniedException();
        }
    }
}
