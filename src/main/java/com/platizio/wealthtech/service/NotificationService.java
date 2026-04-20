package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.repository.NotificationRepository;
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
    public Notification markRead(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElseThrow();
        notification.setReadFlag(Boolean.TRUE);
        return notificationRepository.save(notification);
    }
}