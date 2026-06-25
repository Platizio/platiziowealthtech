package com.platizio.wealthtech.dto;

import java.util.UUID;

/**
 * Response for {@code POST /api/v1/investors/send-to-investor} (investor.md §3 T1, §6.1,
 * R2). Confirms the investor was parked PENDING and surfaces the {@code approvalToken} so
 * the email-link flow is demoable/testable without SMTP (the real email send is a later
 * shared task). The approval URL is also logged at INFO server-side.
 */
public record SendToInvestorResponse(
        String status,
        String message,
        UUID investorId,
        String approvalToken
) {}
