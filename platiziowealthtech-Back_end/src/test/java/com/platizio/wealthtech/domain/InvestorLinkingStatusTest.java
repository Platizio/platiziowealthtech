package com.platizio.wealthtech.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PA1 (V61 data backbone): the linking-state enum + the additive Investor fields.
 * A fresh Investor defaults to READY so existing-code paths (and the V61 column default)
 * agree; distributor_id is now nullable; pending_distributor_id holds the initiating
 * distributor before the investor approves (R5).
 */
class InvestorLinkingStatusTest {

    @Test
    void freshInvestorDefaultsToReadyLinkingStatus() {
        assertThat(new Investor().getLinkingStatus()).isEqualTo(InvestorLinkingStatus.READY);
    }

    @Test
    void distributorIdIsNullableAndPendingHoldsTheInitiatingDistributor() {
        Investor inv = new Investor();
        UUID dist = UUID.randomUUID();

        // R5: before approval — no distributor_id, but the pending distributor is recorded.
        inv.setDistributorId(null);
        inv.setPendingDistributorId(dist);
        inv.setLinkingStatus(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);

        assertThat(inv.getDistributorId()).isNull();
        assertThat(inv.getPendingDistributorId()).isEqualTo(dist);
        assertThat(inv.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
    }

    @Test
    void enumCoversTheFullStateMachine() {
        assertThat(InvestorLinkingStatus.values()).containsExactlyInAnyOrder(
                InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL,
                InvestorLinkingStatus.INVESTOR_APPROVED,
                InvestorLinkingStatus.INVESTOR_FILLING,
                InvestorLinkingStatus.INVESTOR_SKIPPED,
                InvestorLinkingStatus.DISTRIBUTOR_FILLING,
                InvestorLinkingStatus.PENDING_PROFILE_APPROVAL,
                InvestorLinkingStatus.READY,
                InvestorLinkingStatus.REJECTED);
    }
}
