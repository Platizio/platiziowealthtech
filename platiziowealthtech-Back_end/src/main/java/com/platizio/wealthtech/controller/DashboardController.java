package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.ActionItemDto;
import com.platizio.wealthtech.dto.OnboardingPipelineDto;
import com.platizio.wealthtech.dto.SipDashboardDto;
import com.platizio.wealthtech.service.DashboardService;
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
    public SipDashboardDto getSipDashboard(@PathVariable UUID distributorId) {
        return dashboardService.getSipDashboard(distributorId);
    }

    @GetMapping("/{distributorId}/actions")
    public List<ActionItemDto> getActionCenter(@PathVariable UUID distributorId) {
        return dashboardService.getActionCenter(distributorId);
    }

    @GetMapping("/{distributorId}/onboarding")
    public OnboardingPipelineDto getOnboardingPipeline(@PathVariable UUID distributorId) {
        return dashboardService.getOnboardingPipeline(distributorId);
    }
}
