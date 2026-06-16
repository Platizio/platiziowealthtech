package com.platizio.wealthtech.integration.arn;

import com.platizio.wealthtech.domain.ArnValidationStatus;
import com.platizio.wealthtech.validation.ArnFormat;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder ARN validation provider used until a real AMFI/KYD provider is configured
 * (active when {@code arn-validation.real-client-enabled} is false, which is the default).
 *
 * It does NOT fabricate real distributor identities — it returns only a deterministic compliance
 * verdict derived from the ARN's trailing digit, so the full signup flow (and every failure branch)
 * can be exercised end-to-end with well-formed ARNs ({@code ARN-<digits>}):
 * <ul>
 *   <li>ends in {@code 9} → {@link ArnValidationStatus#EXPIRED} (e.g. ARN-102949)</li>
 *   <li>ends in {@code 8} → {@link ArnValidationStatus#KYD_INCOMPLETE} (e.g. ARN-102948)</li>
 *   <li>ends in {@code 7} → {@link ArnValidationStatus#REJECTED} (e.g. ARN-102947)</li>
 *   <li>otherwise → {@link ArnValidationStatus#VERIFIED} (e.g. ARN-102943)</li>
 * </ul>
 * Malformed ARNs (failing {@link ArnFormat}) are always REJECTED.
 */
@Component
@ConditionalOnProperty(prefix = "arn-validation", name = "real-client-enabled", havingValue = "false", matchIfMissing = true)
public class MockArnValidationClient implements ArnValidationClient {

    private static final Logger logger = LoggerFactory.getLogger(MockArnValidationClient.class);
    private static final String SOURCE = "MOCK";

    public MockArnValidationClient() {
        logger.info("arn_validation_client mode='mock' note='placeholder provider active; set arn-validation.real-client-enabled=true to use a real provider'");
    }

    @Override
    public ArnValidationResult validateArn(String arnNumber) {
        String arn = ArnFormat.normalize(arnNumber);
        if (!ArnFormat.isValid(arn)) {
            return new ArnValidationResult(ArnValidationStatus.REJECTED, arn, null, null, null, null, null, SOURCE,
                    ArnFormat.ARN_MESSAGE);
        }
        char lastDigit = arn.charAt(arn.length() - 1);
        return switch (lastDigit) {
            case '9' -> new ArnValidationResult(ArnValidationStatus.EXPIRED, arn, null, null, LocalDate.now().minusDays(30),
                    "EXPIRED", null, SOURCE, "ARN registration has expired. Please renew with AMFI and retry.");
            case '8' -> new ArnValidationResult(ArnValidationStatus.KYD_INCOMPLETE, arn, null, null, LocalDate.now().plusYears(3),
                    "INCOMPLETE", null, SOURCE, "ARN is valid but KYD is incomplete. Complete KYD before onboarding investors.");
            case '7' -> new ArnValidationResult(ArnValidationStatus.REJECTED, arn, null, null, null, null, null, SOURCE,
                    "ARN could not be verified with the registry.");
            default -> new ArnValidationResult(ArnValidationStatus.VERIFIED, arn, null, null, LocalDate.now().plusYears(3),
                    "COMPLETE", null, SOURCE, "ARN verified.");
        };
    }
}
