package com.platizio.wealthtech.integration.arn;

import com.platizio.wealthtech.domain.ArnValidationStatus;
import java.time.LocalDate;

/**
 * Abstraction over the external ARN / KYD validation provider (e.g. AMFI / a KYD aggregator).
 * Mirrors the {@code CybrillaClient} interface + Real/Mock implementation pattern so the real
 * provider can be plugged in later behind the {@code arn-validation.real-client-enabled} flag
 * without touching callers. The default wiring uses {@link MockArnValidationClient}.
 */
public interface ArnValidationClient {

    /**
     * Validates a (format-checked, normalised) ARN and returns the provider's verdict plus any
     * distributor compliance details it exposes. Implementations must never throw for a simply
     * "invalid" ARN — they return a {@link ArnValidationStatus#REJECTED}/{@code EXPIRED}/
     * {@code KYD_INCOMPLETE} result. They may throw {@code CybrillaUnavailableException} (provider
     * unreachable → 503) or {@code CybrillaApiException} (provider error → 502).
     */
    ArnValidationResult validateArn(String arnNumber);

    /**
     * Provider verdict for one ARN. {@code arnExpiryDate}, {@code distributorName}, {@code firmName},
     * {@code kydStatus} and {@code euin} are best-effort — populated when the provider returns them.
     */
    record ArnValidationResult(
            ArnValidationStatus status,
            String arnNumber,
            String distributorName,
            String firmName,
            LocalDate arnExpiryDate,
            String kydStatus,
            String euin,
            String source,
            String message
    ) {
        public boolean isVerified() {
            return status == ArnValidationStatus.VERIFIED;
        }
    }
}
