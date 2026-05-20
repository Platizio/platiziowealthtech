package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * B-39: guards the security posture of the external-auth debug controller.
 *
 * This is a fast, context-free reflective test (same style as
 * NotificationControllerTest / LeadAuthorizationTest) — the project has no
 * @SpringBootTest / test-database infrastructure, so booting the full filter
 * chain is not viable here. The two invariants asserted below are the actual
 * security guarantees:
 *
 *  1. The controller bean is disabled by default — it is only registered when
 *     external-auth.debug.enabled=true is explicitly set (matchIfMissing=false).
 *  2. Its request-mapping path is the known /api/v1/debug/external-auth. This
 *     is deliberately NOT in SecurityConfig's permitAll() list; pinning the
 *     path here means any future remap forces a reviewer back to the security
 *     config note rather than silently changing the exposed surface.
 */
class ExternalAuthDebugControllerTest {

    @Test
    void debugControllerIsDisabledByDefault() {
        ConditionalOnProperty gate =
                ExternalAuthDebugController.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(gate)
                .as("ExternalAuthDebugController must be gated by @ConditionalOnProperty")
                .isNotNull();
        assertThat(gate.prefix()).isEqualTo("external-auth.debug");
        assertThat(gate.name()).containsExactly("enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
        // The critical default-deny guarantee: if the property is absent the
        // condition does NOT match, so the bean is never created in prod
        // unless someone deliberately opts in.
        assertThat(gate.matchIfMissing())
                .as("debug controller must NOT be created when the property is missing")
                .isFalse();
    }

    @Test
    void debugControllerPathIsTheKnownProtectedPath() {
        RequestMapping mapping =
                ExternalAuthDebugController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .as("path is intentionally excluded from SecurityConfig.permitAll(); "
                        + "a remap must trigger a security review")
                .containsExactly("/api/v1/debug/external-auth");
    }
}
