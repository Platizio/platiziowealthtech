package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.common.ConflictException;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;

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
}
