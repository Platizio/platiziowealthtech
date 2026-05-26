package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.CapitalGainsExportFormat;
import com.platizio.wealthtech.dto.CapitalGainsReportResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.CapitalGainsReportService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/distributor/{distributorId}")
public class ReportsController {

    private final CapitalGainsReportService capitalGainsReportService;

    public ReportsController(CapitalGainsReportService capitalGainsReportService) {
        this.capitalGainsReportService = capitalGainsReportService;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/capital-gains")
    public CapitalGainsReportResponse capitalGainsStatement(
            @PathVariable UUID distributorId,
            @RequestParam(required = false) String financialYear,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        return capitalGainsReportService.generate(distributorId, financialYear);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/capital-gains/export")
    public ResponseEntity<String> exportCapitalGainsStatement(
            @PathVariable UUID distributorId,
            @RequestParam(required = false) String financialYear,
            @RequestParam(defaultValue = "QUICKO") String format,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        assertDistributorAccess(principal, distributorId);
        CapitalGainsExportFormat exportFormat = parseFormat(format);
        String csv = capitalGainsReportService.exportCsv(distributorId, financialYear, exportFormat);
        String fy = financialYear == null || financialYear.isBlank() ? "current-fy" : financialYear.replaceAll("[^0-9A-Za-z-]", "");
        String filename = "capital-gains-" + fy + "-" + exportFormat.name().toLowerCase(Locale.ROOT) + ".csv";

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(csv);
    }

    private void assertDistributorAccess(AuthenticatedDistributorPrincipal principal, UUID distributorId) {
        if (principal == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        if (principal.role() == DistributorRole.ADMIN) {
            return;
        }
        if (!distributorId.equals(principal.distributorId())) {
            throw new AccessDeniedException("Cannot access reports for another distributor");
        }
    }

    private CapitalGainsExportFormat parseFormat(String format) {
        try {
            return CapitalGainsExportFormat.valueOf(format.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("format must be QUICKO or CLEARTAX");
        }
    }
}
