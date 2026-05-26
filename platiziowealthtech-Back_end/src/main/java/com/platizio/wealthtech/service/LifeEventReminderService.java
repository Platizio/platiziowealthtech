package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.LifeEventReminder;
import com.platizio.wealthtech.domain.LifeEventReminderStatus;
import com.platizio.wealthtech.domain.LifeEventType;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.LifeEventReminderRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifeEventReminderService {

    private static final int LOOKAHEAD_DAYS = 7;
    private static final int SCAN_PAGE_SIZE = 500;

    private final InvestorRepository investorRepository;
    private final LifeEventReminderRepository reminderRepository;
    private final NotificationService notificationService;

    public LifeEventReminderService(
            InvestorRepository investorRepository,
            LifeEventReminderRepository reminderRepository,
            NotificationService notificationService
    ) {
        this.investorRepository = investorRepository;
        this.reminderRepository = reminderRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public int generateUpcomingReminders(LocalDate today) {
        int created = 0;
        int pageNumber = 0;
        Page<Investor> page;

        do {
            page = investorRepository.findInvestorsWithLifeEventDates(
                    PageRequest.of(pageNumber++, SCAN_PAGE_SIZE, Sort.by("id")));
            for (Investor investor : page.getContent()) {
                created += createReminderIfDue(investor, LifeEventType.BIRTHDAY, investor.getDateOfBirth(), today, true);
                created += createReminderIfDue(investor, LifeEventType.ANNIVERSARY, investor.getAnniversaryDate(), today, true);
                created += createReminderIfDue(investor, LifeEventType.GOAL_MATURITY, investor.getGoalMaturityDate(), today, false);
            }
        } while (page.hasNext());

        return created;
    }

    @Transactional(readOnly = true)
    public List<LifeEventReminder> listOpenForDistributor(UUID distributorId, int limit) {
        return reminderRepository.findByDistributorIdAndStatusOrderByEventDateAsc(
                distributorId,
                LifeEventReminderStatus.OPEN,
                PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
    }

    @Transactional(readOnly = true)
    public long countOpenForDistributor(UUID distributorId) {
        return reminderRepository.countByDistributorIdAndStatus(distributorId, LifeEventReminderStatus.OPEN);
    }

    private int createReminderIfDue(
            Investor investor,
            LifeEventType eventType,
            LocalDate sourceDate,
            LocalDate today,
            boolean recurring
    ) {
        if (sourceDate == null) {
            return 0;
        }

        LocalDate eventDate = recurring
                ? nextAnnualOccurrence(sourceDate, today)
                : sourceDate;
        if (!isWithinWindow(eventDate, today)) {
            return 0;
        }
        if (reminderRepository.existsByInvestorIdAndEventTypeAndEventDate(investor.getId(), eventType, eventDate)) {
            return 0;
        }

        LifeEventReminder reminder = new LifeEventReminder();
        reminder.setDistributorId(investor.getDistributorId());
        reminder.setInvestorId(investor.getId());
        reminder.setEventType(eventType);
        reminder.setEventDate(eventDate);
        reminder.setReminderDate(today);
        reminder.setStatus(LifeEventReminderStatus.OPEN);
        reminder.setTitle(titleFor(eventType, investor.getFullName()));
        reminder.setMessage(messageFor(eventType, investor.getFullName(), eventDate));

        LifeEventReminder saved = reminderRepository.save(reminder);
        notificationService.createForDistributor(
                saved.getDistributorId(),
                saved.getInvestorId(),
                NotificationType.LIFE_EVENT_REMINDER,
                saved.getTitle(),
                saved.getMessage());
        return 1;
    }

    private boolean isWithinWindow(LocalDate eventDate, LocalDate today) {
        long daysUntil = ChronoUnit.DAYS.between(today, eventDate);
        return daysUntil >= 0 && daysUntil <= LOOKAHEAD_DAYS;
    }

    private LocalDate nextAnnualOccurrence(LocalDate sourceDate, LocalDate today) {
        MonthDay monthDay = MonthDay.from(sourceDate);
        LocalDate candidate = atYearOrFeb28(monthDay, today.getYear());
        if (candidate.isBefore(today)) {
            candidate = atYearOrFeb28(monthDay, today.getYear() + 1);
        }
        return candidate;
    }

    private LocalDate atYearOrFeb28(MonthDay monthDay, int year) {
        try {
            return monthDay.atYear(year);
        } catch (DateTimeException ex) {
            return LocalDate.of(year, 2, 28);
        }
    }

    private String titleFor(LifeEventType eventType, String investorName) {
        return switch (eventType) {
            case BIRTHDAY -> investorName + "'s birthday is coming up";
            case ANNIVERSARY -> investorName + "'s anniversary is coming up";
            case GOAL_MATURITY -> investorName + "'s goal is maturing soon";
        };
    }

    private String messageFor(LifeEventType eventType, String investorName, LocalDate eventDate) {
        return switch (eventType) {
            case BIRTHDAY -> "Wish " + investorName + " on " + eventDate + " and review any birthday-linked financial goals.";
            case ANNIVERSARY -> "Reach out to " + investorName + " before the anniversary on " + eventDate + ".";
            case GOAL_MATURITY -> "Review maturity proceeds and reinvestment options with " + investorName + " before " + eventDate + ".";
        };
    }
}
