package com.shanshui.apmserver;

import com.shanshui.apmserver.config.QueryProperties;
import com.shanshui.apmserver.service.ProjectAccessDeniedException;
import com.shanshui.apmserver.service.ProjectAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectAuthorizationTests {

    @Test
    void rejectsCrossProjectRequestWithoutMembership() {
        ProjectAuthorizationService service = new ProjectAuthorizationService(new QueryProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "project-b");
        request.addHeader("X-User-Project-Ids", "project-b");

        assertThrows(ProjectAccessDeniedException.class, () -> service.requireView("project-a", request));
    }

    @Test
    void acceptsProjectHeaderAndMembership() {
        ProjectAuthorizationService service = new ProjectAuthorizationService(new QueryProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "project-a");
        request.addHeader("X-User-Project-Ids", "project-a,project-b");

        assertDoesNotThrow(() -> service.requireView("project-a", request));
    }
}
