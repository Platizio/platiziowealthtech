package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.HouseholdReportResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.HouseholdReportService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/distributor/{distributorId}")
public class HouseholdReportController {

    private final HouseholdReportService householdReportService;

    public HouseholdReportController(HouseholdReportService householdReportService) {
        this.householdReportService = householdReportService;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/households")
    public HouseholdReportResponse householdReport(
            @PathVariable UUID distributorId,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        return householdReportService.generate(distributorId);
    }

    private void assertDistributorAccess(AuthenticatedDistributorPrincipal principal, UUID distributorId) {
        if (principal == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        if (principal.role() == DistributorRole.ADMIN) {
            return;
        }
        if (!distributorId.equals(principal.distributorId())) {
            throw new AccessDeniedException("Cannot access household report for another distributor");
        }
    }
}
