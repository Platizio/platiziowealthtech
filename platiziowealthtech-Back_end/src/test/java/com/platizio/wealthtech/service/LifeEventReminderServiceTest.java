package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.LifeEventReminder;
import com.platizio.wealthtech.domain.LifeEventType;
import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.LifeEventReminderRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class LifeEventReminderServiceTest {

    @Test
    void generatesOpenRemindersForLifeEventsSevenDaysAhead() {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setFullName("Priya Investor");
        investor.setDateOfBirth(LocalDate.of(1990, 5, 29));
        investor.setAnniversaryDate(LocalDate.of(2020, 5, 28));
        investor.setGoalMaturityDate(LocalDate.of(2026, 5, 27));

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        LifeEventReminderRepository reminderRepository = mock(LifeEventReminderRepository.class);
        RecordingNotificationService notificationService = new RecordingNotificationService();
        LifeEventReminderService service = new LifeEventReminderService(
                investorRepository,
                reminderRepository,
                notificationService);

        when(investorRepository.findInvestorsWithLifeEventDates(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(investor)));
        when(reminderRepository.existsByInvestorIdAndEventTypeAndEventDate(any(), any(), any()))
                .thenReturn(false);
        when(reminderRepository.save(any(LifeEventReminder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int created = service.generateUpcomingReminders(LocalDate.of(2026, 5, 22));

        assertThat(created).isEqualTo(3);
        ArgumentCaptor<LifeEventReminder> reminderCaptor = ArgumentCaptor.forClass(LifeEventReminder.class);
        verify(reminderRepository, times(3)).save(reminderCaptor.capture());
        assertThat(reminderCaptor.getAllValues())
                .extracting(LifeEventReminder::getEventType)
                .containsExactly(
                        LifeEventType.BIRTHDAY,
                        LifeEventType.ANNIVERSARY,
                        LifeEventType.GOAL_MATURITY);
        assertThat(reminderCaptor.getAllValues())
                .allSatisfy(reminder -> {
                    assertThat(reminder.getDistributorId()).isEqualTo(distributorId);
                    assertThat(reminder.getInvestorId()).isEqualTo(investorId);
                    assertThat(reminder.getReminderDate()).isEqualTo(LocalDate.of(2026, 5, 22));
                });
        assertThat(notificationService.created.get()).isEqualTo(3);
    }

    private static class RecordingNotificationService extends NotificationService {
        private final AtomicInteger created = new AtomicInteger();

        RecordingNotificationService() {
            super(null);
        }

        @Override
        public Notification createForDistributor(
                UUID distributorId,
                UUID investorId,
                NotificationType type,
                String title,
                String message
        ) {
            created.incrementAndGet();
            return new Notification();
        }
    }
}
