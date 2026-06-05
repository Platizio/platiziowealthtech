package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response to an OTP request. {@code devCode} is populated only on the local
 * profile when real email delivery is disabled, so developers can test the
 * flow without an SMTP server. It is never returned in deployed environments.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpRequestResponse(
        String message,
        long expiresInSeconds,
        long resendInSeconds,
        String devCode
) {}
