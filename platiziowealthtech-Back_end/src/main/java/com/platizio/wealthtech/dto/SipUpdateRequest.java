package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * Edits an established SIP via FP "Update a Purchase Plan" ({@code PATCH /v2/mf_purchase_plans}).
 * FP supports editing the {@code amount} and {@code installment_day} of an active plan (applies to all
 * remaining installments; must be done at least 2 calendar days before the next installment).
 *
 * <p>Both fields are optional individually; the service requires at least one to be present.
 *
 * @see <a href="https://docs.fintechprimitives.com/mf-transactions/purchase-plans/">FP MF purchase plans</a>
 */
public record SipUpdateRequest(
        @DecimalMin(value = "0", inclusive = false, message = "SIP amount must be greater than 0")
        BigDecimal amount,

        @Min(value = 1, message = "SIP installment day must be between 1 and 28")
        @Max(value = 28, message = "SIP installment day must be between 1 and 28")
        Integer installmentDay
) {
}
