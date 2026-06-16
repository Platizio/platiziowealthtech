package com.platizio.wealthtech.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import org.junit.jupiter.api.Test;

class CybrillaIntegrationEnvironmentTest {

    @Test
    void detectsSandboxFromUrlsAndTestCredentials() {
        CybrillaPreVerificationProperties poa = new CybrillaPreVerificationProperties();
        poa.setBaseUrl("https://api.sandbox.cybrilla.com");
        poa.getAuth().setClientId("mfdptnr_platizio_test_abc");

        FinprimTenantProperties finprim = new FinprimTenantProperties();
        finprim.setBaseUrl("https://s.finprim.com");
        finprim.getTenant().getAuth().setClientId("platizio_test_xyz");

        CybrillaIntegrationEnvironment environment = new CybrillaIntegrationEnvironment(poa, finprim, "sandbox", "");

        assertThat(environment.isSandboxMode()).isTrue();
        assertThat(environment.enforceSandboxPanPatterns()).isTrue();
        assertThat(environment.usesTestCredentials()).isTrue();
    }

    @Test
    void productionEnvironmentOverridesSandboxSignals() {
        CybrillaPreVerificationProperties poa = new CybrillaPreVerificationProperties();
        poa.setBaseUrl("https://api.sandbox.cybrilla.com");
        poa.getAuth().setClientId("mfdptnr_platizio_test_abc");

        FinprimTenantProperties finprim = new FinprimTenantProperties();
        finprim.setBaseUrl("https://s.finprim.com");
        finprim.getTenant().getAuth().setClientId("platizio_test_xyz");

        CybrillaIntegrationEnvironment environment = new CybrillaIntegrationEnvironment(poa, finprim, "production", "");

        assertThat(environment.isProductionMode()).isTrue();
        assertThat(environment.isSandboxMode()).isFalse();
        assertThat(environment.enforceSandboxPanPatterns()).isFalse();
    }

    @Test
    void productionUrlsDisableSandboxPanPatternEnforcementByDefault() {
        CybrillaPreVerificationProperties poa = new CybrillaPreVerificationProperties();
        poa.setBaseUrl("https://api.cybrilla.com");
        poa.getAuth().setClientId("mfdptnr_platizio_live_abc");

        FinprimTenantProperties finprim = new FinprimTenantProperties();
        finprim.setBaseUrl("https://api.fintechprimitives.com");
        finprim.getTenant().getAuth().setClientId("platizio_live_xyz");

        CybrillaIntegrationEnvironment environment = new CybrillaIntegrationEnvironment(poa, finprim, "sandbox", "");

        assertThat(environment.isSandboxMode()).isFalse();
        assertThat(environment.enforceSandboxPanPatterns()).isFalse();
    }

    @Test
    void explicitOverrideCanDisableSandboxPanEnforcement() {
        CybrillaPreVerificationProperties poa = new CybrillaPreVerificationProperties();
        poa.setBaseUrl("https://api.sandbox.cybrilla.com");

        FinprimTenantProperties finprim = new FinprimTenantProperties();
        finprim.setBaseUrl("https://s.finprim.com");

        CybrillaIntegrationEnvironment environment = new CybrillaIntegrationEnvironment(poa, finprim, "sandbox", "false");

        assertThat(environment.isSandboxMode()).isTrue();
        assertThat(environment.enforceSandboxPanPatterns()).isFalse();
    }
}
