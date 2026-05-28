package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.CapitalGainsExportFormat;
import com.platizio.wealthtech.dto.CapitalGainsReportResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.CapitalGainsReportService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ReportsControllerTest {

    @Test
    void rejectsDistributorTryingToExportAnotherDistributorsCapitalGains() {
        ReportsController controller = new ReportsController(new FakeCapitalGainsReportService(""));

        assertThatThrownBy(() -> controller.exportCapitalGainsStatement(
                UUID.randomUUID(),
                "2024-2025",
                "quicko",
                principal(UUID.randomUUID(), DistributorRole.SUB_DISTRIBUTOR)
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminCanExportAnotherDistributorsCapitalGainsCsv() {
        UUID distributorId = UUID.randomUUID();
        ReportsController controller = new ReportsController(new FakeCapitalGainsReportService("Financial Year\n2024-2025\n"));

        ResponseEntity<String> response = controller.exportCapitalGainsStatement(
                distributorId,
                "2024-2025",
                "quicko",
                principal(UUID.randomUUID(), DistributorRole.ADMIN)
        );

        assertThat(response.getBody()).isEqualTo("Financial Year\n2024-2025\n");
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("capital-gains-2024-2025-quicko.csv");
    }

    private AuthenticatedDistributorPrincipal principal(UUID distributorId, DistributorRole role) {
        return new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "secret",
                role,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }

    private static class FakeCapitalGainsReportService extends CapitalGainsReportService {

        private final String csv;

        FakeCapitalGainsReportService(String csv) {
            super(null, null, null, null);
            this.csv = csv;
        }

        @Override
        public CapitalGainsReportResponse generate(UUID distributorId, String financialYear) {
            return null;
        }

        @Override
        public String exportCsv(UUID distributorId, String financialYear, CapitalGainsExportFormat format) {
            return csv;
        }
    }
}
