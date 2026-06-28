package com.platizio.wealthtech.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
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

        assertThat(messages).contains(PanFormat.INDIAN_PAN_MESSAGE);
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
        assertThat(pattern.regexp()).isEqualTo(PanFormat.INDIAN_PAN_REGEX);
        assertThat(pattern.message()).isEqualTo(PanFormat.INDIAN_PAN_MESSAGE);
    }

    @Test
    void authSignupRequestDoesNotCurrentlyAcceptPan() {
        assertThat(recordComponentNames(AuthSignupRequest.class)).doesNotContain("pan");
    }

    @Test
    void acceptsValidIndianMobile() {
        assertThat(messagesFor(requestWithMobile("9876543210")))
                .doesNotContain(MobileFormat.INDIAN_MOBILE_MESSAGE);
    }

    @Test
    void rejectsMobileStartingBelowSix() {
        assertThat(messagesFor(requestWithMobile("1234567890")))
                .contains(MobileFormat.INDIAN_MOBILE_MESSAGE);
    }

    @Test
    void rejectsMobileOfWrongLength() {
        assertThat(messagesFor(requestWithMobile("98765")))
                .contains(MobileFormat.INDIAN_MOBILE_MESSAGE);
    }

    private static Set<String> messagesFor(InvestorCreateRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.toSet());
    }

    private static InvestorCreateRequest requestWithMobile(String mobile) {
        return new InvestorCreateRequest(
                UUID.randomUUID(),
                "Priya Sharma",
                mobile,
                "priya@example.com",
                "ABCDE1234F",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
                null,
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
