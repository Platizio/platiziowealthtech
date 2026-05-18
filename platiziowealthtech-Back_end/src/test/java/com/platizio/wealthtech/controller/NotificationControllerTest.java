package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.NotificationService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class NotificationControllerTest {

    @Test
    void notificationEndpointsRequirePreAuthorization() throws NoSuchMethodException {
        Method list = NotificationController.class.getMethod("list", UUID.class);
        Method markRead = NotificationController.class.getMethod("markRead", UUID.class, Authentication.class);

        assertThat(list.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("#distributorId == principal.distributorId");
        assertThat(markRead.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("isAuthenticated()");
    }

    @Test
    void markReadAllowsOwningDistributor() {
        UUID distributorId = UUID.randomUUID();
        Notification notification = notification(distributorId);
        RecordingNotificationService notificationService = new RecordingNotificationService(notification);
        NotificationController controller = new NotificationController(notificationService);

        controller.markRead(UUID.randomUUID(), auth(distributorId));

        assertThat(notificationService.markedNotification).isSameAs(notification);
    }

    @Test
    void markReadRejectsAnotherDistributorNotification() {
        RecordingNotificationService notificationService = new RecordingNotificationService(notification(UUID.randomUUID()));
        NotificationController controller = new NotificationController(notificationService);

        assertThatThrownBy(() -> controller.markRead(UUID.randomUUID(), auth(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(notificationService.markedNotification).isNull();
    }

    private Notification notification(UUID distributorId) {
        Notification notification = new Notification();
        notification.setDistributorId(distributorId);
        return notification;
    }

    private Authentication auth(UUID distributorId) {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static class RecordingNotificationService extends NotificationService {

        private final Notification notification;
        private Notification markedNotification;

        RecordingNotificationService(Notification notification) {
            super(null);
            this.notification = notification;
        }

        @Override
        public Notification getNotification(UUID notificationId) {
            return notification;
        }

        @Override
        public Notification markRead(Notification notification) {
            markedNotification = notification;
            return notification;
        }
    }
}
