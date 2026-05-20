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
// Class-level baseline: every endpoint requires an authenticated principal.
// Method-level @PreAuthorize annotations are more specific and take
// precedence (Spring uses the most specific one), so existing per-method
// ownership checks are unchanged. This only backstops any future endpoint
// added without its own @PreAuthorize so it can never default to open.
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PreAuthorize("#distributorId == principal.distributorId")
    @GetMapping("/distributor/{distributorId}")
    public List<Notification> list(@PathVariable UUID distributorId, Authentication auth) {
        // B-53: the data scope is derived from the authenticated JWT principal,
        // NOT from the client-supplied {distributorId} path segment. The path
        // segment is retained only for backward compatibility with existing
        // clients and is deliberately ignored for the lookup, so a caller can
        // only ever see their own notifications even if the @PreAuthorize guard
        // above were removed or weakened. (The annotation is kept as an extra,
        // independent defence-in-depth layer.)
        UUID callerDistributorId = callerDistributorId(auth);
        return notificationService.listByDistributor(callerDistributorId);
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
