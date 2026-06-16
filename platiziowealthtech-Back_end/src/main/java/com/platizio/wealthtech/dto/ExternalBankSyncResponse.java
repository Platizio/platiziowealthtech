package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import java.util.UUID;

public record ExternalBankSyncResponse(
        String status,
        String eventType,
        String externalId,
        UUID investorId,
        BankVerificationStatus bankVerificationStatus
) {}
