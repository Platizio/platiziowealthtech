package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.common.ConflictException;
import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import com.platizio.wealthtech.integration.CybrillaApiException;
import jakarta.persistence.PersistenceException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    @Test
    void conflictExceptionReturnsHttp409Response() throws NoSuchMethodException {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors");

        ApiErrorResponse response = handler.handleConflict(
                new ConflictException("An investor with this email is already registered"),
                request);

        ResponseStatus responseStatus = GlobalExceptionHandler.class
                .getDeclaredMethod("handleConflict", ConflictException.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(ResponseStatus.class);
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.error()).isEqualTo("CONFLICT");
        assertThat(response.message()).isEqualTo("An investor with this email is already registered");
        assertThat(response.path()).isEqualTo("/api/v1/investors");
    }

    @Test
    void duplicateResourceWithResourceIdReturns409BodyWithResourceId() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors");
        UUID existingId = UUID.randomUUID();

        ResponseEntity<Object> response = handler.handleDuplicateResource(
                new DuplicateResourceException("An investor with this PAN already exists", existingId, "pan"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(GlobalExceptionHandler.ConflictErrorResponse.class);
        GlobalExceptionHandler.ConflictErrorResponse body =
                (GlobalExceptionHandler.ConflictErrorResponse) response.getBody();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("CONFLICT");
        assertThat(body.message()).isEqualTo("An investor with this PAN already exists");
        assertThat(body.path()).isEqualTo("/api/v1/investors");
        assertThat(body.resourceId()).isEqualTo(existingId);
        assertThat(body.conflictField()).isEqualTo("pan");
    }

    @Test
    void duplicateResourceWithoutResourceIdReturnsPlainApiErrorResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors");

        ResponseEntity<Object> response = handler.handleDuplicateResource(
                new DuplicateResourceException("An investor with this PAN already exists"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(ApiErrorResponse.class);
        ApiErrorResponse body = (ApiErrorResponse) response.getBody();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("CONFLICT");
        assertThat(body.message()).isEqualTo("An investor with this PAN already exists");
        assertThat(body.path()).isEqualTo("/api/v1/investors");
    }

    @Test
    void responseStatusExceptionReturnsMatchingHttpStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/investors/7f3a2b1c-9e8d-4a7b-8c6d-5e4f3a2b1c01/kyc-form");

        ResponseEntity<ApiErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An on-going KYC modify form already exists for this investor (status=created)."),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).contains("on-going KYC modify form");
    }

    @Test
    void kycFormAccessDeniedReturnsHttp403WithActionableMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/investors/7f3a2b1c-9e8d-4a7b-8c6d-5e4f3a2b1c01/kyc-form");

        ResponseEntity<ApiErrorResponse> response = handler.handleCybrillaApiException(
                new CybrillaApiException(
                        "Unable to create_kyc_form with Cybrilla POA: 403 FORBIDDEN response={\"error\":\"Partner not allowed to access kyc_forms.\"}"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().error()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().message()).contains("kyc_forms");
        assertThat(response.getBody().message()).contains("CYBRILLA_KYC_FORM_MOCK_FALLBACK");
    }

    @Test
    void unexpectedRollbackWithUnrelatedCauseReturns500NeutralMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");

        ResponseEntity<ApiErrorResponse> response = handler.handleTransactionFailure(
                new UnexpectedRollbackException(
                        "Transaction silently rolled back because it has been marked as rollback-only"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).doesNotContain("Investor sync");
        assertThat(response.getBody().message()).doesNotContain("Finprim");
        assertThat(response.getBody().message()).contains("transaction was rolled back");
    }

    @Test
    void unexpectedRollbackFromInvestorSyncStillReturns409InvestorSyncMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors/restore");

        ResponseEntity<ApiErrorResponse> response = handler.handleTransactionFailure(
                new UnexpectedRollbackException(
                        "Transaction rolled back",
                        new IllegalStateException("Failed to persist Finprim profile during restore")),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).contains("Investor sync could not complete");
    }

    @Test
    void transactionFailureWithKnownConstraintReturns409FriendlyMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors");

        ResponseEntity<ApiErrorResponse> response = handler.handleTransactionFailure(
                new UnexpectedRollbackException(
                        "Transaction rolled back",
                        new IllegalStateException(
                                "duplicate key value violates unique constraint \"investors_pan_key\"")),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("An investor with this PAN already exists");
    }

    @Test
    void persistenceFailureWithUnrelatedCauseReturns500NeutralMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");

        ResponseEntity<ApiErrorResponse> response = handler.handlePersistence(
                new PersistenceException("could not execute statement"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).doesNotContain("Finprim");
        assertThat(response.getBody().message()).doesNotContain("Restore from Cybrilla");
    }

    @Test
    void persistenceFailureWithKnownConstraintReturns409FriendlyMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/investors");

        ResponseEntity<ApiErrorResponse> response = handler.handlePersistence(
                new PersistenceException(
                        "violates unique constraint \"investors_email_key\""),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("An investor with this email is already registered");
    }
}
