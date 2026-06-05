package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import java.util.List;

public record InvestorOnboardingResumeResponse(
        Investor investor,
        List<InvestorBankAccount> bankAccounts,
        String nextStep,
        boolean canApplyKyc,
        boolean canRefreshKyc,
        boolean canReKyc,
        boolean canAddBankAccount,
        boolean canRefreshBankVerification,
        boolean readyForTransactions,
        String message
) {}
