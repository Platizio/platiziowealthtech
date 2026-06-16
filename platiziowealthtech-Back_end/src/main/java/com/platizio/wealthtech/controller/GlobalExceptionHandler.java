package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.common.AccountNotApprovedException;
import com.platizio.wealthtech.common.ConflictException;
import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.integration.auth.ExternalApiAuthenticationException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps PostgreSQL unique-constraint names to human-readable messages.
     * Constraint names follow Postgres' default pattern: {table}_{column}_key.
     * Add a new entry here whenever a new unique column is introduced.
     */
    private static final Map<String, String> CONSTRAINT_MESSAGES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // investors
        m.put("investors_pan_key",                        "An investor with this PAN already exists");
        m.put("investors_email_key",                      "An investor with this email is already registered");
        m.put("uq_investor_email",                        "An investor with this email address already exists");
        // distributors
        m.put("distributors_email_key",                   "A distributor with this email address already exists");
        m.put("distributors_mobile_number_key",           "A distributor with this mobile number already exists");
        m.put("distributors_arn_number_key",              "A distributor with this ARN number already exists");
        m.put("distributors_e_uin_number_key",            "A distributor with this EUIN number already exists");
        // product_schemes
        m.put("product_schemes_external_scheme_code_key", "A product scheme with this scheme code already exists");
        CONSTRAINT_MESSAGES = Collections.unmodifiableMap(m);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        logger.warn("Entity not found: {} at {}", ex.getMessage(), request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 404, "NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse(ex.getMessage());
        logger.warn("Validation failed for {}: {}", request.getRequestURI(), errors);
        return new ApiErrorResponse(OffsetDateTime.now(), 400, "VALIDATION_FAILED", errors, request.getRequestURI());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleBadRequest(Exception ex, HttpServletRequest request) {
        String msg = ex.getMessage();
        if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException) {
            msg = "Invalid data format provided: " + ex.getCause().getMessage();
        }
        String code = "BAD_REQUEST";
        if (msg != null && (msg.contains("Sandbox mode is active") || msg.contains("Sandbox KYC readiness checks require"))) {
            code = "SANDBOX_SIMULATOR_PAN_REQUIRED";
        } else if (msg != null && msg.contains("Invalid PAN format")) {
            code = "INVALID_PAN_FORMAT";
        }
        logger.warn("Bad request at {}: {}", request.getRequestURI(), msg);
        return new ApiErrorResponse(OffsetDateTime.now(), 400, code, msg, request.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        logger.warn("Resource not found: {}", request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 404, "NOT_FOUND", "Endpoint does not exist: " + request.getRequestURI(), request.getRequestURI());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiErrorResponse handleUnauthorized(BadCredentialsException ex, HttpServletRequest request) {
        logger.warn("Unauthorized access attempt: {} at {}", ex.getMessage(), request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 401, "UNAUTHORIZED", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccountNotApprovedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> handleAccountNotApproved(AccountNotApprovedException ex, HttpServletRequest request) {
        logger.warn("Login blocked for account status {} at {}", ex.getAccountStatus(), request.getRequestURI());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", OffsetDateTime.now());
        response.put("status", 403);
        response.put("error", "ACCOUNT_NOT_APPROVED");
        response.put("message", ex.getMessage());
        response.put("accountStatus", ex.getAccountStatus());
        response.put("path", request.getRequestURI());
        return response;
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleConflict(ConflictException ex, HttpServletRequest request) {
        logger.warn("Conflict at {}: {}", request.getRequestURI(), ex.getMessage());
        return new ApiErrorResponse(OffsetDateTime.now(), 409, "CONFLICT", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            logger.error("Response status {} at {}: {}", status.value(), request.getRequestURI(), reason, ex);
        } else {
            logger.warn("Response status {} at {}: {}", status.value(), request.getRequestURI(), reason);
        }
        String errorCode = switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case FORBIDDEN -> "FORBIDDEN";
            default -> status.is4xxClientError() ? "CLIENT_ERROR" : "INTERNAL_SERVER_ERROR";
        };
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                errorCode,
                reason,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiErrorResponse handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        logger.warn("Access denied for {} at {}", request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous", request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 403, "FORBIDDEN", "Access denied", request.getRequestURI());
    }

    /**
     * Thrown by service-layer duplicate checks (application-level guard).
     * Returns 409 with the human-readable message set by the caller.
     *
     * <p>BUG-001: when the exception carries the existing resource id (e.g. a
     * resume-blocked duplicate PAN), the 409 body additionally exposes
     * {@code resourceId}/{@code conflictField} so the client can resume the
     * existing record. When no id is present the body is the standard
     * {@link ApiErrorResponse} (unchanged).
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Object> handleDuplicateResource(DuplicateResourceException ex, HttpServletRequest request) {
        logger.warn("Duplicate resource at {}: {}", request.getRequestURI(), ex.getMessage());
        if (ex.getResourceId() != null) {
            ConflictErrorResponse body = new ConflictErrorResponse(
                    OffsetDateTime.now(),
                    409,
                    "CONFLICT",
                    ex.getMessage(),
                    request.getRequestURI(),
                    ex.getResourceId(),
                    ex.getConflictField()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(), 409, "CONFLICT", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 409 body variant that mirrors {@link ApiErrorResponse} but adds the existing
     * resource id (and the conflicting field) so a client can resume the record
     * that caused the conflict. Used only when those details are available.
     */
    public record ConflictErrorResponse(
            OffsetDateTime timestamp,
            int status,
            String error,
            String message,
            String path,
            java.util.UUID resourceId,
            String conflictField
    ) {}

    /**
     * Handles unique-constraint and not-null violations from the DB layer.
     * Walks the full cause chain to find the constraint name, then maps it to a
     * friendly message. Never leaks raw SQL or internal exception text to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String causeChain = buildCauseChainMessage(ex);
        String friendly = CONSTRAINT_MESSAGES.entrySet().stream()
                .filter(entry -> causeChain.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("A duplicate or invalid record was detected");
        logger.warn("Data integrity violation at {}: {}", request.getRequestURI(), causeChain);
        return new ApiErrorResponse(OffsetDateTime.now(), 409, "CONFLICT", friendly, request.getRequestURI());
    }

    @ExceptionHandler({UnexpectedRollbackException.class, TransactionSystemException.class})
    public ResponseEntity<ApiErrorResponse> handleTransactionFailure(RuntimeException ex, HttpServletRequest request) {
        String causeChain = buildCauseChainMessage(ex);
        logger.warn("Transaction failure at {}: {}", request.getRequestURI(), causeChain);
        // Only emit the investor-sync 409 when the rollback was actually caused by the
        // investor/Finprim sync constraint it describes. A recognized DB constraint is a
        // genuine 409 conflict. Anything else is an opaque rollback -> neutral 500.
        if (isInvestorSyncFailure(causeChain)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    OffsetDateTime.now(),
                    409,
                    "CONFLICT",
                    "Investor sync could not complete because one or more Finprim profiles failed to save. "
                            + "Retry Restore from Cybrilla after restarting the backend.",
                    request.getRequestURI()));
        }
        String constraintMessage = matchConstraintMessage(causeChain);
        if (constraintMessage != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    OffsetDateTime.now(), 409, "CONFLICT", constraintMessage, request.getRequestURI()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                OffsetDateTime.now(),
                500,
                "INTERNAL_SERVER_ERROR",
                "The operation could not be completed because the transaction was rolled back. Please retry.",
                request.getRequestURI()));
    }

    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<ApiErrorResponse> handlePersistence(PersistenceException ex, HttpServletRequest request) {
        String causeChain = buildCauseChainMessage(ex);
        logger.warn("Persistence failure at {}: {}", request.getRequestURI(), causeChain);
        String constraintMessage = matchConstraintMessage(causeChain);
        if (constraintMessage != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    OffsetDateTime.now(), 409, "CONFLICT", constraintMessage, request.getRequestURI()));
        }
        if (isInvestorSyncFailure(causeChain)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    OffsetDateTime.now(),
                    409,
                    "CONFLICT",
                    "Investor data could not be saved while reconciling from Finprim. "
                            + "The local row may be incomplete — retry Restore from Cybrilla after restarting the backend.",
                    request.getRequestURI()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                OffsetDateTime.now(),
                500,
                "INTERNAL_SERVER_ERROR",
                "The record could not be saved due to a persistence error. Please retry.",
                request.getRequestURI()));
    }

    /** Returns the friendly message for a recognized unique/not-null constraint, or {@code null}. */
    private String matchConstraintMessage(String causeChain) {
        return CONSTRAINT_MESSAGES.entrySet().stream()
                .filter(entry -> causeChain.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * True only when the cause chain shows the rollback originated from the
     * investor/Finprim sync (Restore from Cybrilla) path — not an unrelated
     * order save, lead update, or generic constraint failure.
     */
    private boolean isInvestorSyncFailure(String causeChain) {
        if (causeChain == null) {
            return false;
        }
        String lower = causeChain.toLowerCase(Locale.ROOT);
        return lower.contains("finprim") || lower.contains("restore from cybrilla");
    }

    /**
     * Provider is unreachable (DNS/connection/timeout). This is transient and
     * the caller already persisted a pending/deferred state, so we return 503
     * with a clear retry message and log at WARN (no noisy full stack trace).
     * Declared separately from the generic Cybrilla handler so Spring routes the
     * connectivity subtype here.
     */
    @ExceptionHandler(CybrillaUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiErrorResponse handleCybrillaUnavailable(CybrillaUnavailableException ex, HttpServletRequest request) {
        logger.warn("External investment platform unreachable at {}: {}", request.getRequestURI(), ex.getMessage());
        String message = request.getRequestURI() != null && request.getRequestURI().contains("/products")
                ? "Cannot sync the product catalogue: Cybrilla/Fintech Primitives is unreachable (network/DNS). "
                        + "Check FINPRIM_TENANT_CLIENT_ID, FINPRIM_TENANT_CLIENT_SECRET, and that s.finprim.com resolves, then retry Sync from Cybrilla."
                : "Cybrilla/Fintech Primitives is currently unreachable (network/DNS). "
                        + "Your changes were saved and the action can be retried in a moment.";
        return new ApiErrorResponse(
                OffsetDateTime.now(),
                503,
                "EXTERNAL_PLATFORM_UNAVAILABLE",
                message,
                request.getRequestURI()
        );
    }

    @ExceptionHandler({CybrillaApiException.class, ExternalApiAuthenticationException.class})
    public ResponseEntity<ApiErrorResponse> handleCybrillaApiException(RuntimeException ex, HttpServletRequest request) {
        logger.error("External investment platform call failed at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        String rawChain = buildCauseChainMessage(ex);
        String causeChain = rawChain.toLowerCase(Locale.ROOT);

        String message;
        if (causeChain.contains("is not configured") || causeChain.contains("configuration error")) {
            // Missing/blank external credentials. POA pre-verification (KYC checks
            // and bank verification) authenticates with a DIFFERENT audience than
            // the product-catalogue/tenant calls, so it can fail even when product
            // sync works. Name the exact env vars so the operator can fix it.
            message = "Cybrilla/Fintech Primitives credentials are not fully configured ("
                    + safeExternalReason(rawChain)
                    + "). KYC pre-verification needs the POA credentials "
                    + "CYBRILLA_PRE_VERIFICATION_CLIENT_ID and CYBRILLA_PRE_VERIFICATION_CLIENT_SECRET "
                    + "(separate from the tenant FINPRIM_TENANT_CLIENT_ID / FINPRIM_TENANT_CLIENT_SECRET). "
                    + "Set them in the backend .env and restart, then retry.";
        } else if (causeChain.contains("unable to authenticate")
                || causeChain.contains("401")
                || causeChain.contains("unauthorized")) {
            message = "Could not authenticate with Cybrilla/Fintech Primitives. Verify the client id, "
                    + "client secret, and token URL for the POA pre-verification audience "
                    + "(CYBRILLA_PRE_VERIFICATION_*), then retry.";
        } else if (causeChain.contains("too many requests") || causeChain.contains("429")) {
            message = "Cybrilla/Fintech Primitives is rate-limiting live requests right now. Please wait a few minutes; cached local data is still available where supported.";
        } else if (causeChain.contains("422") || causeChain.contains("unprocessable")) {
            message = "Cybrilla rejected the verification payload (validation failed). "
                    + "In sandbox, use simulator PANs (e.g. GYAPS3751D, pattern XXXPXNNNNX), "
                    + "match name/DOB to PAN records, and ensure POA credentials "
                    + "(CYBRILLA_PRE_VERIFICATION_*) are configured. "
                    + "Real PAN verification requires production credentials from Cybrilla. Detail: "
                    + safeExternalReason(rawChain);
        } else if (causeChain.contains("400 bad request")
                && (causeChain.contains("not a valid pan") || causeChain.contains("invalid investor identifier"))) {
            message = "Cybrilla sandbox rejected this PAN. Use a simulator PAN with P as the 4th character "
                    + "(pattern XXXPXNNNNX), e.g. BBBPB3753B (fresh KYC) or AAAPA3751A (KYC ready). "
                    + "Real PANs are not accepted in sandbox. Detail: "
                    + safeExternalReason(rawChain);
        } else if (causeChain.contains("pre verification") || causeChain.contains("pre_verification")) {
            message = "Cybrilla POA pre-verification call failed ("
                    + safeExternalReason(rawChain)
                    + "). Verify CYBRILLA_PRE_VERIFICATION_CLIENT_ID/SECRET, sandbox PAN pattern "
                    + "(XXXPXNNNNX), and retry. Finprim tenant credentials are not required for this step.";
        } else if (causeChain.contains("identity document already exist")) {
            message = "An Aadhaar identity document is already in progress or completed for this KYC request. "
                    + "Use Refresh Aadhaar status or open the existing Digilocker link instead of starting again. "
                    + "Detail: " + safeExternalReason(rawChain);
        } else if ((causeChain.contains("403") || causeChain.contains("forbidden"))
                && causeChain.contains("kyc_form")) {
            message = "Cybrilla has not enabled the POA kyc_forms API for this partner account. "
                    + "Ask Cybrilla support to enable kyc_forms on your sandbox tenant, or set "
                    + "CYBRILLA_KYC_FORM_MOCK_FALLBACK=true (enabled by default on the local profile) "
                    + "to continue with a mock KYC modify form locally. Detail: "
                    + safeExternalReason(rawChain);
        } else {
            message = "Unable to post data to Cybrilla/Fintech Primitives right now ("
                    + safeExternalReason(rawChain)
                    + "). Please check external platform connectivity and retry.";
        }
        HttpStatus httpStatus = resolveCybrillaClientErrorStatus(causeChain);
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                httpStatus.value(),
                httpStatus == HttpStatus.BAD_REQUEST
                        ? "BAD_REQUEST"
                        : httpStatus == HttpStatus.FORBIDDEN ? "FORBIDDEN" : "EXTERNAL_PLATFORM_ERROR",
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(httpStatus).body(body);
    }

    private static HttpStatus resolveCybrillaClientErrorStatus(String causeChain) {
        if (causeChain == null || causeChain.isBlank()) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (causeChain.contains("400 bad request")
                && (causeChain.contains("not a valid pan")
                || causeChain.contains("validation failed")
                || causeChain.contains("invalid investor identifier"))) {
            return HttpStatus.BAD_REQUEST;
        }
        if (causeChain.contains("422") || causeChain.contains("unprocessable")) {
            return HttpStatus.BAD_REQUEST;
        }
        if ((causeChain.contains("403") || causeChain.contains("forbidden"))
                && causeChain.contains("kyc_form")) {
            return HttpStatus.FORBIDDEN;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /**
     * Extracts a client-safe reason from an external-call exception chain. Drops
     * everything from " response=" onward because provider response bodies can
     * contain PII (PAN, names); the prefix only carries the operation + HTTP
     * status, which is safe and actionable.
     */
    private String safeExternalReason(String message) {
        if (message == null || message.isBlank()) {
            return "no further detail available";
        }
        String trimmed = message;
        int idx = trimmed.toLowerCase(Locale.ROOT).indexOf(" response=");
        if (idx >= 0) {
            trimmed = trimmed.substring(0, idx);
        }
        trimmed = trimmed.trim();
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200) + "…";
        }
        return trimmed.isBlank() ? "no further detail available" : trimmed;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleGeneric(Exception ex, HttpServletRequest request) {
        // Log the full stack trace internally but never expose raw exception messages to the client.
        logger.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return new ApiErrorResponse(OffsetDateTime.now(), 500, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred", request.getRequestURI());
    }

    /**
     * Concatenates messages from the entire cause chain into a single string.
     * Required because JDBC drivers wrap DB exceptions several levels deep,
     * so the constraint name only appears in an inner cause, not in ex.getMessage().
     */
    private String buildCauseChainMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            t = t.getCause();
        }
        return sb.toString();
    }
}
