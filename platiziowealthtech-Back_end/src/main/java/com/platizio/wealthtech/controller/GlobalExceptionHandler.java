package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.common.AccountNotApprovedException;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiErrorResponse handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        logger.warn("Access denied for {} at {}", request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous", request.getRequestURI());
        return new ApiErrorResponse(OffsetDateTime.now(), 403, "FORBIDDEN", "Access denied", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleGeneric(Exception ex, HttpServletRequest request) {
        logger.error("Internal server error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return new ApiErrorResponse(OffsetDateTime.now(), 500, "INTERNAL_SERVER_ERROR", ex.getMessage(), request.getRequestURI());
    }
}
