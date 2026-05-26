package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.Investor;

public record InvestorDocumentUploadResponse(
        Investor investor,
        UploadedInvestorDocumentResponse document
) {
}
