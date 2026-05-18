package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class OrderServiceTest {

    @Test
    void createOrderRejectsInvestorOwnedByAnotherDistributor() {
        UUID investorDistributorId = UUID.randomUUID();
        UUID authenticatedDistributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = new Investor();
        investor.setDistributorId(investorDistributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);

        OrderService orderService = new OrderService(
                null,
                null,
                new FixedInvestorService(investor),
                null,
                null,
                null
        );

        OrderCreateRequest request = new OrderCreateRequest(
                investorId,
                UUID.randomUUID(),
                TransactionType.LUMPSUM_PURCHASE,
                BigDecimal.TEN,
                null,
                "NET_BANKING",
                null
        );

        assertThatThrownBy(() -> orderService.createOrder(request, authenticatedDistributorId))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static class FixedInvestorService extends InvestorService {

        private final Investor investor;

        FixedInvestorService(Investor investor) {
            super(null, null, null, null, null);
            this.investor = investor;
        }

        @Override
        public Investor getInvestor(UUID investorId) {
            return investor;
        }
    }
}
