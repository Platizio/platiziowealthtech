package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BulkOrderUploadRowResult(
        int rowNumber,
        UUID investorId,
        UUID productSchemeId,
        String schemeCode,
        String schemeName,
        TransactionType transactionType,
        BigDecimal amount,
        BigDecimal units,
        BulkOrderUploadRowStatus status,
        UUID orderId,
        String externalOrderId,
        String investorActionUrl,
        List<String> errors
) {
}
