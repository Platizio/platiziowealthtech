package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.repository.NotificationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public List<Notification> listByDistributor(UUID distributorId) {
        return notificationRepository.findByDistributorIdOrderByCreatedAtDesc(distributorId);
    }

    public Notification getNotification(UUID notificationId) {
        // EntityNotFoundException is the codebase-wide convention for 404s and is
        // already mapped to HTTP 404 by GlobalExceptionHandler. Including the id
        // makes the 404 response and server logs actionable.
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Notification not found with id: " + notificationId));
    }

    @Transactional
    public Notification createForDistributor(UUID distributorId, UUID investorId, NotificationType type, String title, String message) {
        Notification notification = new Notification();
        notification.setDistributorId(distributorId);
        notification.setInvestorId(investorId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        return notificationRepository.save(notification);
    }

    @Transactional
    public Notification markRead(Notification notification) {
        notification.setReadFlag(Boolean.TRUE);
        return notificationRepository.save(notification);
    }
}
