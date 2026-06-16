package com.platizio.wealthtech.dto;

/**
 * Optional body for starting a KYC modify form. The frontend should pass its own
 * {@code callbackBaseUrl} (typically {@code window.location.origin}) so Digilocker/eSign
 * redirects return to the dev server port actually in use.
 */
public record KycFormCreateRequest(String callbackBaseUrl) {
}
