package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.service.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/distributor/{distributorId}")
    public List<Notification> list(@PathVariable UUID distributorId) {
        return notificationService.listByDistributor(distributorId);
    }

    @PatchMapping("/{notificationId}/read")
    public Notification markRead(@PathVariable UUID notificationId) {
        return notificationService.markRead(notificationId);
    }
}