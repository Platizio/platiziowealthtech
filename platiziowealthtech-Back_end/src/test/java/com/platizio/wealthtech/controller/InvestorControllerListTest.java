package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.InvestorService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class InvestorControllerListTest {

    @Test
    void listInvestorsUsesAuthenticatedDistributorWhenQueryDistributorIsAbsent() {
        RecordingInvestorService investorService = new RecordingInvestorService();
        InvestorController controller = new InvestorController(investorService, null, null);
        UUID actorId = UUID.randomUUID();

        controller.listAll(null, 0, 20, auth(actorId));

        assertThat(investorService.requesterId).isEqualTo(actorId);
        assertThat(investorService.distributorId).isNull();
        assertThat(investorService.page).isZero();
        assertThat(investorService.size).isEqualTo(20);
    }

    @Test
    void searchInvestorsUsesAuthenticatedDistributorInsteadOfRequesterQueryParam() {
        RecordingInvestorService investorService = new RecordingInvestorService();
        InvestorController controller = new InvestorController(investorService, null, null);
        UUID actorId = UUID.randomUUID();

        controller.search("jane", null, 10, auth(actorId));

        assertThat(investorService.requesterId).isEqualTo(actorId);
        assertThat(investorService.query).isEqualTo("jane");
        assertThat(investorService.distributorId).isNull();
    }

    private Authentication auth(UUID distributorId) {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static class RecordingInvestorService extends InvestorService {

        private UUID requesterId;
        private UUID distributorId;
        private int page;
        private int size;
        private String query;

        RecordingInvestorService() {
            super(null, null, null, null, null);
        }

        @Override
        public Page<Investor> listVisibleToRequesterPage(UUID requesterId, UUID distributorId, int page, int size) {
            this.requesterId = requesterId;
            this.distributorId = distributorId;
            this.page = page;
            this.size = size;
            return new PageImpl<>(List.of());
        }

        @Override
        public List<Investor> search(String query, UUID distributorId, UUID requesterId, int limit) {
            this.query = query;
            this.distributorId = distributorId;
            this.requesterId = requesterId;
            return List.of();
        }
    }
}
