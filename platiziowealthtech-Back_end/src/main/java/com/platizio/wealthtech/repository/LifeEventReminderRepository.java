package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.LifeEventReminder;
import com.platizio.wealthtech.domain.LifeEventReminderStatus;
import com.platizio.wealthtech.domain.LifeEventType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface LifeEventReminderRepository extends JpaRepository<LifeEventReminder, UUID> {
    boolean existsByInvestorIdAndEventTypeAndEventDate(UUID investorId, LifeEventType eventType, LocalDate eventDate);

    List<LifeEventReminder> findByDistributorIdAndStatusOrderByEventDateAsc(
            UUID distributorId,
            LifeEventReminderStatus status,
            Pageable pageable);

    long countByDistributorIdAndStatus(UUID distributorId, LifeEventReminderStatus status);
}
