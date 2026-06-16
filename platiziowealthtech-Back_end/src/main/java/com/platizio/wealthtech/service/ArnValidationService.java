package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ArnValidationStatus;
import com.platizio.wealthtech.dto.ArnValidationResponse;
import com.platizio.wealthtech.integration.arn.ArnValidationClient;
import com.platizio.wealthtech.integration.arn.ArnValidationClient.ArnValidationResult;
import com.platizio.wealthtech.validation.ArnFormat;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ARN/KYD validation: local format check first (cheap, avoids a provider round-trip for
 * obviously-malformed input), then delegates to the configured {@link ArnValidationClient} (mock by
 * default, real provider when enabled). Logs each attempt's outcome (ARN is a public registration id;
 * no PII such as mobile/email is logged here).
 */
@Service
public class ArnValidationService {

    private static final Logger logger = LoggerFactory.getLogger(ArnValidationService.class);

    private final ArnValidationClient client;

    public ArnValidationService(ArnValidationClient client) {
        this.client = client;
    }

    public ArnValidationResult validate(String rawArn) {
        String arn = ArnFormat.normalize(rawArn);
        if (!ArnFormat.isValid(arn)) {
            logger.info("arn_validation status='rejected' reason='invalid_format' arn='{}'", arn);
            return new ArnValidationResult(
                    ArnValidationStatus.REJECTED, arn, null, null, null, null, null, "format-check", ArnFormat.ARN_MESSAGE);
        }
        logger.info("arn_validation status='request' arn='{}'", arn);
        ArnValidationResult result = client.validateArn(arn);
        logger.info(
                "arn_validation status='response' arn='{}' result='{}' source='{}'",
                arn, result.status(), result.source());
        return result;
    }

    public ArnValidationResponse toResponse(ArnValidationResult result) {
        return new ArnValidationResponse(
                result.isVerified(),
                result.status(),
                result.arnNumber(),
                result.distributorName(),
                result.firmName(),
                result.arnExpiryDate(),
                result.kydStatus(),
                result.euin(),
                result.source(),
                OffsetDateTime.now(),
                result.message()
        );
    }
}
