package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.ActionItemDto;
import com.platizio.wealthtech.dto.OnboardingPipelineDto;
import com.platizio.wealthtech.dto.SipDashboardDto;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.DashboardService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard/distributor")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/{distributorId}/sips")
    public SipDashboardDto getSipDashboard(
            @PathVariable UUID distributorId,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        return dashboardService.getSipDashboard(distributorId);
    }

    @GetMapping("/{distributorId}/actions")
    public List<ActionItemDto> getActionCenter(
            @PathVariable UUID distributorId,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        return dashboardService.getActionCenter(distributorId);
    }

    @GetMapping("/{distributorId}/onboarding")
    public OnboardingPipelineDto getOnboardingPipeline(
            @PathVariable UUID distributorId,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        return dashboardService.getOnboardingPipeline(distributorId);
    }

    private void assertDistributorAccess(AuthenticatedDistributorPrincipal principal, UUID distributorId) {
        if (principal == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        if (principal.role() == DistributorRole.ADMIN) {
            return;
        }
        if (!distributorId.equals(principal.distributorId())) {
            throw new AccessDeniedException("Cannot access dashboard for another distributor");
        }
    }
}
