package com.platizio.wealthtech.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class InvestorCreateRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void acceptsValidPan() {
        assertThat(VALIDATOR.validate(requestWithPan("ABCDE1234F"))).isEmpty();
    }

    @Test
    void rejectsPanWithInvalidFormat() {
        Set<String> messages = messagesFor(requestWithPan("abcde1234f"));

        assertThat(messages).contains("Invalid PAN format. Expected format: AAAAA9999A");
    }

    @Test
    void rejectsPanWithInvalidLength() {
        Set<String> messages = messagesFor(requestWithPan("ABCDE123F"));

        assertThat(messages).contains("PAN must be exactly 10 characters");
    }

    @Test
    void investorCreatePanFieldHasFormatAndSizeAnnotations() throws NoSuchFieldException {
        java.lang.reflect.Field pan = InvestorCreateRequest.class.getDeclaredField("pan");
        Size size = pan.getAnnotation(Size.class);
        Pattern pattern = pan.getAnnotation(Pattern.class);

        assertThat(size).isNotNull();
        assertThat(size.min()).isEqualTo(10);
        assertThat(size.max()).isEqualTo(10);
        assertThat(pattern).isNotNull();
        assertThat(pattern.regexp()).isEqualTo("^[A-Z]{5}[0-9]{4}[A-Z]$");
        assertThat(pattern.message()).isEqualTo("Invalid PAN format. Expected format: AAAAA9999A");
    }

    @Test
    void authSignupRequestDoesNotCurrentlyAcceptPan() {
        assertThat(recordComponentNames(AuthSignupRequest.class)).doesNotContain("pan");
    }

    private static Set<String> messagesFor(InvestorCreateRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.toSet());
    }

    private static InvestorCreateRequest requestWithPan(String pan) {
        return new InvestorCreateRequest(
                UUID.randomUUID(),
                "Priya Sharma",
                "9876543210",
                "priya@example.com",
                pan,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Set<String> recordComponentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
