package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void usesIncomingRequestIdInMdcAndResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/investors");
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("request-123");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/investors");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
