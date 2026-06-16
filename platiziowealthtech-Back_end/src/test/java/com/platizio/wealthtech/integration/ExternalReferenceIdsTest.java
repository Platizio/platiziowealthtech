package com.platizio.wealthtech.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExternalReferenceIdsTest {

    @Test
    void acceptsRealFpInvestorProfileIds() {
        assertThat(ExternalReferenceIds.isFpInvestorProfileId("invp_a735794281be4df2a1fbeb1680f41003")).isTrue();
    }

    @Test
    void rejectsDemoPlaceholderInvestorProfileIds() {
        assertThat(ExternalReferenceIds.isFpInvestorProfileId("invp_demo_rahul")).isFalse();
        assertThat(ExternalReferenceIds.isFpInvestorProfileId("CYB-INV-1")).isFalse();
    }

    @Test
    void rejectsDemoPlaceholderMfInvestmentAccountIds() {
        assertThat(ExternalReferenceIds.isFpMfInvestmentAccountId("mfia_demo_anita")).isFalse();
    }
}
