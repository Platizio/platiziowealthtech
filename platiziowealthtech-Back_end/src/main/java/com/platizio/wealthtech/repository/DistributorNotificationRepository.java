package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.DistributorNotification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DistributorNotificationRepository extends JpaRepository<DistributorNotification, UUID> {

    /** All notifications for a distributor, newest first. */
    List<DistributorNotification> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId);

    /** Unread notifications for a distributor, newest first. */
    List<DistributorNotification> findByDistributorIdAndReadAtIsNullOrderByCreatedAtDesc(UUID distributorId);
}
