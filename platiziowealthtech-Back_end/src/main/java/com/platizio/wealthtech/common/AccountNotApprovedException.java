package com.platizio.wealthtech.common;

import com.platizio.wealthtech.domain.DistributorStatus;

public class AccountNotApprovedException extends RuntimeException {

    private final DistributorStatus accountStatus;

    public AccountNotApprovedException(DistributorStatus accountStatus) {
        super(messageFor(accountStatus));
        this.accountStatus = accountStatus;
    }

    public DistributorStatus getAccountStatus() {
        return accountStatus;
    }

    private static String messageFor(DistributorStatus accountStatus) {
        if (accountStatus == DistributorStatus.PENDING_APPROVAL) {
            return "Approval remaining. Your account is pending admin approval.";
        }
        return "Only approved users can login. Current account status: " + accountStatus;
    }
}
