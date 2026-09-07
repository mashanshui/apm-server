package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.internal.application.AppMembershipService;
import com.shanshui.apmserver.identity.api.AppRoleDeniedException;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppAuthorizationTests {

    @Test
    void rejectsCrossAppRequestWithoutMembership() {
        AppMemberRepository repository = mock(AppMemberRepository.class);
        AppMembershipService service = new AppMembershipService(repository);
        UUID userId = UUID.randomUUID();
        when(repository.findRole(TestAppIds.id("app-a"), userId)).thenReturn(Optional.empty());

        assertThrows(AppNotFoundException.class, () -> service.requireView(TestAppIds.id("app-a"), userId));
    }

    @Test
    void acceptsViewerMembershipWithoutTrustingRequestHeaders() {
        AppMemberRepository repository = mock(AppMemberRepository.class);
        AppMembershipService service = new AppMembershipService(repository);
        UUID userId = UUID.randomUUID();
        when(repository.findRole(TestAppIds.id("app-a"), userId)).thenReturn(Optional.of(AppRole.VIEWER));

        assertDoesNotThrow(() -> service.requireView(TestAppIds.id("app-a"), userId));
    }

    @Test
    void rejectsViewerAppEdit() {
        AppMemberRepository repository = mock(AppMemberRepository.class);
        AppMembershipService service = new AppMembershipService(repository);
        UUID userId = UUID.randomUUID();
        when(repository.findRole(TestAppIds.id("app-a"), userId)).thenReturn(Optional.of(AppRole.VIEWER));

        assertThrows(AppRoleDeniedException.class, () -> service.requireEdit(TestAppIds.id("app-a"), userId));
    }
}
