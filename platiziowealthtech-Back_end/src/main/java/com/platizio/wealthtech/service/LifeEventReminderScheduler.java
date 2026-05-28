package com.platizio.wealthtech.service;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LifeEventReminderScheduler {

    static final int LOCK_NAMESPACE = 0x504C545A; // PLTZ
    static final int LOCK_ID = 0x4C455652; // LEVR

    private static final Logger logger = LoggerFactory.getLogger(LifeEventReminderScheduler.class);

    private final LifeEventReminderService reminderService;
    private final PostgresAdvisoryLockService advisoryLockService;

    public LifeEventReminderScheduler(
            LifeEventReminderService reminderService,
            PostgresAdvisoryLockService advisoryLockService
    ) {
        this.reminderService = reminderService;
        this.advisoryLockService = advisoryLockService;
    }

    @Scheduled(
            cron = "${app.life-event-reminders.cron:0 10 6 * * *}",
            zone = "${app.life-event-reminders.zone:Asia/Kolkata}"
    )
    public void createUpcomingLifeEventReminders() {
        runScan();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createUpcomingLifeEventRemindersOnStartup() {
        runScan();
    }

    private void runScan() {
        try {
            boolean executed = advisoryLockService.runWithLock(LOCK_NAMESPACE, LOCK_ID, () -> {
                int created = reminderService.generateUpcomingReminders(LocalDate.now());
                if (created > 0) {
                    logger.info("life_event_reminders status='completed' created='{}'", created);
                }
            });
            if (!executed) {
                logger.debug("life_event_reminders status='skipped' reason='lock_not_acquired'");
            }
        } catch (RuntimeException ex) {
            logger.warn("life_event_reminders status='failed' reason='{}'", ex.getMessage());
        }
    }
}
