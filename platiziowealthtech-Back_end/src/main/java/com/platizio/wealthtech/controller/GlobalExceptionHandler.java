package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.common.AccountNotApprovedException;
import com.platizio.wealthtech.common.ConflictException;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

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
        logger.warn("Bad request at {}: {}", request.getRequestURI(), msg);
        return new ApiErrorResponse(OffsetDateTime.now(), 400, "BAD_REQUEST", msg, request.getRequestURI());
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

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiErrorResponse handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        logger.warn("Access denied for {} at {}", request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous", request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 403, "FORBIDDEN", "Access denied", request.getRequestURI());
    }

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
