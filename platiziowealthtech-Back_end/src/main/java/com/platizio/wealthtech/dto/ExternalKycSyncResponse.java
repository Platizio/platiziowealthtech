package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.KycStatus;
import java.util.UUID;

public record ExternalKycSyncResponse(
        String status,
        String eventType,
        String externalId,
        UUID investorId,
        KycStatus kycStatus
) {}
