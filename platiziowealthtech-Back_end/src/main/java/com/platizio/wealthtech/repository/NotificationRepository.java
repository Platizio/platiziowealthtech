package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId);
}