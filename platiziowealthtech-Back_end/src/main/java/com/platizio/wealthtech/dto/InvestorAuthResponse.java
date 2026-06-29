package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorAccount;
import java.util.UUID;

/**
 * Investor-portal session view returned by signup / login-verify / me. Never
 * exposes the PAN or any raw OTP. {@code investorLinked} is true once the account
 * is linked to a distributor-created {@link com.platizio.wealthtech.domain.Investor}
 * profile (by PAN), which gates the onboarding-review and contact-self endpoints.
 */
public record InvestorAuthResponse(
        UUID accountId,
        String email,
        String fullName,
        String status,
        boolean emailVerified,
        boolean mobileVerified,
        boolean investorLinked,
        // Linked investor's KYC status from the local DB (null until linked). DB-sourced so
        // it shows even when the live Cybrilla call is unavailable.
        String kycStatus,
        // The investor's distributor name (from the local DB); null until a distributor is linked.
        String distributorName
) {
    public static InvestorAuthResponse from(InvestorAccount account) {
        return from(account, null, null);
    }

    public static InvestorAuthResponse from(InvestorAccount account, String kycStatus) {
        return from(account, kycStatus, null);
    }

    public static InvestorAuthResponse from(InvestorAccount account, String kycStatus, String distributorName) {
        return new InvestorAuthResponse(
                account.getId(),
                account.getEmail(),
                account.getFullName(),
                account.getStatus() == null ? null : account.getStatus().name(),
                Boolean.TRUE.equals(account.getEmailVerified()),
                Boolean.TRUE.equals(account.getMobileVerified()),
                account.getInvestorId() != null,
                kycStatus,
                distributorName);
    }
}
