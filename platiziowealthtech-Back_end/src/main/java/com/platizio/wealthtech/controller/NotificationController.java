package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PreAuthorize("#distributorId == principal.distributorId")
    @GetMapping("/distributor/{distributorId}")
    public List<Notification> list(@PathVariable UUID distributorId) {
        return notificationService.listByDistributor(distributorId);
    }

    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/{notificationId}/read")
    public Notification markRead(@PathVariable UUID notificationId, Authentication auth) {
        UUID callerDistributorId = callerDistributorId(auth);
        Notification notification = notificationService.getNotification(notificationId);
        if (!callerDistributorId.equals(notification.getDistributorId())) {
            throw new AccessDeniedException("Cannot update another distributor's notification");
        }
        return notificationService.markRead(notification);
    }

    private UUID callerDistributorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}
