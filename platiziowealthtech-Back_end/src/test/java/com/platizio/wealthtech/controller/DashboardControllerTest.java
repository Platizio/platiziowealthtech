package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.ActionItemDto;
import com.platizio.wealthtech.dto.OnboardingPipelineDto;
import com.platizio.wealthtech.dto.SipDashboardDto;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.dto.PortfolioDto;
import com.platizio.wealthtech.service.DashboardService;
import com.platizio.wealthtech.service.PortfolioService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DashboardControllerTest {

    @Test
    void ownDistributorCanReadSipDashboard() {
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        DashboardController controller = new DashboardController(dashboardService, new RecordingPortfolioService());
        UUID distributorId = UUID.randomUUID();

        controller.getSipDashboard(distributorId, principal(distributorId, DistributorRole.SUB_DISTRIBUTOR));

        assertThat(dashboardService.sipDistributorId).isEqualTo(distributorId);
        assertThat(dashboardService.calls).isEqualTo(1);
    }

    @Test
    void adminCanReadAnotherDistributorDashboard() {
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        DashboardController controller = new DashboardController(dashboardService, new RecordingPortfolioService());
        UUID adminId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();

        controller.getActionCenter(distributorId, principal(adminId, DistributorRole.ADMIN));

        assertThat(dashboardService.actionsDistributorId).isEqualTo(distributorId);
        assertThat(dashboardService.calls).isEqualTo(1);
    }

    @Test
    void nonAdminCannotReadAnotherDistributorSipDashboard() {
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        DashboardController controller = new DashboardController(dashboardService, new RecordingPortfolioService());

        assertThatThrownBy(() -> controller.getSipDashboard(
                UUID.randomUUID(),
                principal(UUID.randomUUID(), DistributorRole.MASTER_DISTRIBUTOR)
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(dashboardService.calls).isZero();
    }

    @Test
    void nonAdminCannotReadAnotherDistributorActions() {
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        DashboardController controller = new DashboardController(dashboardService, new RecordingPortfolioService());

        assertThatThrownBy(() -> controller.getActionCenter(
                UUID.randomUUID(),
                principal(UUID.randomUUID(), DistributorRole.SUB_DISTRIBUTOR)
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(dashboardService.calls).isZero();
    }

    @Test
    void nonAdminCannotReadAnotherDistributorOnboardingPipeline() {
        RecordingDashboardService dashboardService = new RecordingDashboardService();
        DashboardController controller = new DashboardController(dashboardService, new RecordingPortfolioService());

        assertThatThrownBy(() -> controller.getOnboardingPipeline(
                UUID.randomUUID(),
                principal(UUID.randomUUID(), DistributorRole.SUB_DISTRIBUTOR)
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(dashboardService.calls).isZero();
    }

    private AuthenticatedDistributorPrincipal principal(UUID distributorId, DistributorRole role) {
        return new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                role,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }

    private static class RecordingPortfolioService extends PortfolioService {
        RecordingPortfolioService() {
            super(null, null, null, null);
        }

        @Override
        public PortfolioDto getPortfolio(UUID distributorId, String categoryFilter) {
            return new PortfolioDto(null, List.of(), List.of(), List.of(), List.of());
        }
    }

    private static class RecordingDashboardService extends DashboardService {

        private int calls;
        private UUID sipDistributorId;
        private UUID actionsDistributorId;

        RecordingDashboardService() {
            super(null, null, null);
        }

        @Override
        public SipDashboardDto getSipDashboard(UUID distributorId) {
            calls++;
            sipDistributorId = distributorId;
            return null;
        }

        @Override
        public List<ActionItemDto> getActionCenter(UUID distributorId) {
            calls++;
            actionsDistributorId = distributorId;
            return List.of();
        }

        @Override
        public OnboardingPipelineDto getOnboardingPipeline(UUID distributorId) {
            calls++;
            return null;
        }
    }
}
