package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.controller.AuthController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class BlockedTokenPurgeSchedulerTest {

    @Test
    void schedulerMethodHasFixedDelayAndJitteredInitialDelay() throws Exception {
        Method method = BlockedTokenPurgeScheduler.class.getMethod("purgeExpiredBlockedTokens");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.auth.blocked-token-purge-interval-ms:3600000}");
        assertThat(scheduled.initialDelayString())
                .contains("app.auth.blocked-token-purge-initial-delay-ms")
                .contains("random.int[30000,90000]");
    }

    @Test
    void authControllerDoesNotOwnScheduledWork() {
        assertThat(Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class)))
                .isEmpty();
    }

    @Test
    void purgeRunsOnlyWhenAdvisoryLockIsAcquired() {
        RecordingAuthService authService = new RecordingAuthService();
        RecordingLockService lockService = new RecordingLockService(true);
        BlockedTokenPurgeScheduler scheduler = new BlockedTokenPurgeScheduler(authService, lockService);

        scheduler.purgeExpiredBlockedTokens();

        assertThat(lockService.lockAttempts).isEqualTo(1);
        assertThat(lockService.namespace).isEqualTo(BlockedTokenPurgeScheduler.LOCK_NAMESPACE);
        assertThat(lockService.lockId).isEqualTo(BlockedTokenPurgeScheduler.LOCK_ID);
        assertThat(lockService.taskCompleted).isTrue();
        assertThat(authService.purgeCalls).isEqualTo(1);
    }

    @Test
    void purgeSkipsWhenAnotherInstanceOwnsTheLock() {
        RecordingAuthService authService = new RecordingAuthService();
        RecordingLockService lockService = new RecordingLockService(false);
        BlockedTokenPurgeScheduler scheduler = new BlockedTokenPurgeScheduler(authService, lockService);

        scheduler.purgeExpiredBlockedTokens();

        assertThat(lockService.lockAttempts).isEqualTo(1);
        assertThat(lockService.taskCompleted).isFalse();
        assertThat(authService.purgeCalls).isZero();
    }

    private static class RecordingAuthService extends AuthService {
        private int purgeCalls;

        RecordingAuthService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public long purgeExpiredBlockedTokens() {
            purgeCalls++;
            return 3;
        }
    }

    private static class RecordingLockService extends PostgresAdvisoryLockService {
        private final boolean lockAvailable;
        private int lockAttempts;
        private int namespace;
        private int lockId;
        private boolean taskCompleted;

        RecordingLockService(boolean lockAvailable) {
            super(null);
            this.lockAvailable = lockAvailable;
        }

        @Override
        public boolean runWithLock(int namespace, int lockId, Runnable task) {
            lockAttempts++;
            this.namespace = namespace;
            this.lockId = lockId;
            if (!lockAvailable) {
                return false;
            }
            task.run();
            taskCompleted = true;
            return true;
        }
    }
}
