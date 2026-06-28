package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response to an OTP request. {@code devCode} is populated only when
 * {@code app.otp.expose-dev-code=true} (local profile only) and real email
 * delivery is disabled, so developers can test the flow without an SMTP server.
 * The flag defaults to {@code false}, so the live code is never returned in
 * deployed or demo environments (DF-13).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpRequestResponse(
        String message,
        long expiresInSeconds,
        long resendInSeconds,
        String devCode
) {}
