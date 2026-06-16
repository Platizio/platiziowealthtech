package com.platizio.wealthtech.dto;

/**
 * FP cancel-a-purchase-plan requires {@code cancellation_code}.
 * @see <a href="https://fintechprimitives.com/docs/api/">Cancel a purchase plan</a>
 */
public record SipCancelRequest(
        String cancellationCode,
        String cancellationReason
) {
    public static final String DEFAULT_CANCELLATION_CODE = "invest_later";
}
