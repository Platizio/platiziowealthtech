package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.DistributorNotification;
import com.platizio.wealthtech.domain.DistributorNotificationType;
import com.platizio.wealthtech.repository.DistributorNotificationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The distributor in-app notification feed (investor.md R8/M5). Records an event row
 * at each linking-choreography milestone (investor approved, form submitted, form
 * skipped, profile change approved, link rejected) and exposes a read/mark-read feed.
 *
 * <p>This is the single home for notification creation in new (M3+) code;
 * {@code InvestorLinkService} writes the same table directly for its M2 events.
 */
@Service
public class DistributorNotificationService {

    private final DistributorNotificationRepository repository;

    public DistributorNotificationService(DistributorNotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DistributorNotification createForDistributor(
            UUID distributorId, UUID investorId, DistributorNotificationType type, String title, String body) {
        DistributorNotification n = new DistributorNotification();
        n.setDistributorId(distributorId);
        n.setInvestorId(investorId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        return repository.save(n);
    }

    @Transactional(readOnly = true)
    public List<DistributorNotification> list(UUID distributorId) {
        return repository.findByDistributorIdOrderByCreatedAtDesc(distributorId);
    }

    @Transactional(readOnly = true)
    public List<DistributorNotification> listUnread(UUID distributorId) {
        return repository.findByDistributorIdAndReadAtIsNullOrderByCreatedAtDesc(distributorId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID distributorId) {
        return repository.findByDistributorIdAndReadAtIsNullOrderByCreatedAtDesc(distributorId).size();
    }

    /** Marks one notification read, but only if it belongs to the given distributor. */
    @Transactional
    public void markRead(UUID distributorId, UUID notificationId) {
        repository.findById(notificationId)
                .filter(n -> distributorId.equals(n.getDistributorId()))
                .filter(n -> n.getReadAt() == null)
                .ifPresent(n -> {
                    n.setReadAt(OffsetDateTime.now());
                    repository.save(n);
                });
    }

    @Transactional
    public int markAllRead(UUID distributorId) {
        List<DistributorNotification> unread = repository.findByDistributorIdAndReadAtIsNullOrderByCreatedAtDesc(distributorId);
        OffsetDateTime now = OffsetDateTime.now();
        unread.forEach(n -> n.setReadAt(now));
        repository.saveAll(unread);
        return unread.size();
    }
}
